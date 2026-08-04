#!/usr/bin/env node
// backend Flyway 마이그레이션의 파괴적 DDL을 정적으로 탐지하는 게이트.
//
// 배경(AquilaXk/easysubway#2365): blue/green standby+promotion 배포(배포 스크립트는 hub 레포 소유)는
// standby 컨테이너가 구 canonical과 동일한 live Postgres에 대해 부팅하며, 구 canonical이
// 라이브 트래픽을 서빙하는 동안 Flyway 마이그레이션을 커밋한다. 따라서 모든 마이그레이션은
// expand/contract(순수 additive) 계약을 지켜야 안전하다. 이 게이트는 baseline 초과 버전의
// postgresql 마이그레이션에서 파괴적 DDL을 탐지해 fail closed 시킨다.
//
// h2 디렉토리(db/migration/h2)는 테스트 전용 스키마이므로 이 게이트의 검사 대상에서 제외한다
// (라이브 배포 대상이 아니며 standby 부팅 윈도우와 무관).
//
// 스캔 범위: Flyway는 location을 재귀 탐색하므로 이 게이트도 postgresql 디렉토리를 재귀
// 스캔한다. 버전 스크립트(V<버전>__*.sql, 소수점·언더스코어 구분자 포함)는 baseline 비교로,
// repeatable(R__*.sql)과 SQL callback은 버전이 없어 재실행될 수 있으므로 항상 스캔한다.
// 유형을 판정할 수 없는 .sql은 스캐너 사각지대이므로 위반으로 보고해 fail closed 시킨다.
import { isMainModule } from "../lib/is-main-module.mjs";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const POLICY_PATH = "backend/quality/migration-ddl-gate.json";
const MIGRATION_DIR = "backend/src/main/resources/db/migration/postgresql";

export function loadJson(filePath) {
  return JSON.parse(readFileSync(filePath, "utf8"));
}

const POLICY_TOP_LEVEL_KEYS = new Set([
  "schemaVersion",
  "gateId",
  "issue",
  "baselineVersion",
  "scannedDirectory",
  "excludedDirectories",
  "note",
  "allowlistEntryFormat",
  "inventory",
  "allowlist",
]);
const INVENTORY_KEYS = new Set(["file", "sha256"]);
const ALLOWLIST_KEYS = new Set(["file", "reason", "approval", "expiresAt", "sha256"]);
const SHA256 = /^[a-f0-9]{64}$/;
const GITHUB_ISSUE_URL = /^https:\/\/github\.com\/[^/]+\/[^/]+\/issues\/[1-9]\d*$/;
const POLICY_ISSUE = "https://github.com/AquilaXk/easysubway/issues/2365";
const V69_ALLOWLIST_FILE = "V69__admin_error_events_permission.sql";
const V69_APPROVAL = "https://github.com/AquilaXk/easysubway/issues/2433";

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function unknownKeys(value, allowed) {
  return Object.keys(value).filter((key) => !allowed.has(key));
}

function sha256(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function semanticVersionKey(versionParts) {
  const parts = [...versionParts];
  while (parts.length > 1 && parts.at(-1) === 0) parts.pop();
  return parts.join(".");
}

// 정책·inventory·allowlist는 실제 migration set과 함께 검증한다. 누락·오타·만료 승인으로
// 검사 사각지대가 생기지 않도록, 정책 오류도 DDL 위반과 동일하게 fail closed 한다.
export function validateMigrationPolicy(policy, files, now = Date.now()) {
  const violations = [];
  const report = (why) => violations.push({ file: POLICY_PATH, findings: ["정책 검증 실패"], why });
  if (!isPlainObject(policy)) {
    report("정책 최상위가 object가 아님");
    return violations;
  }
  const topUnknown = unknownKeys(policy, POLICY_TOP_LEVEL_KEYS);
  if (topUnknown.length) report(`정책 최상위 unknown field: ${topUnknown.join(", ")}`);
  if (policy.schemaVersion !== 2) report("schemaVersion은 2여야 함");
  for (const key of ["gateId", "issue", "scannedDirectory", "note"]) {
    if (isBlank(policy[key])) report(`${key}는 비어 있지 않은 문자열이어야 함`);
  }
  if (!GITHUB_ISSUE_URL.test(policy.issue ?? "")) {
    report("issue는 GitHub issue URL이어야 함");
  }
  if (policy.issue !== POLICY_ISSUE) {
    report(`issue는 ${POLICY_ISSUE}이어야 함`);
  }
  if (!Number.isInteger(policy.baselineVersion) || policy.baselineVersion < 0) {
    report("baselineVersion은 0 이상의 정수여야 함");
  }
  if (policy.baselineVersion !== 66) report("baselineVersion은 66이어야 함");
  if (policy.scannedDirectory !== MIGRATION_DIR) {
    report(`scannedDirectory는 ${MIGRATION_DIR}이어야 함`);
  }
  if (!Array.isArray(policy.excludedDirectories) || policy.excludedDirectories.some(isBlank)) {
    report("excludedDirectories는 비어 있지 않은 문자열 배열이어야 함");
  }
  if (!isPlainObject(policy.allowlistEntryFormat)) {
    report("allowlistEntryFormat은 object여야 함");
  } else {
    const extra = unknownKeys(policy.allowlistEntryFormat, ALLOWLIST_KEYS);
    if (extra.length) report(`allowlistEntryFormat unknown field: ${extra.join(", ")}`);
    if ([...ALLOWLIST_KEYS].some((key) => isBlank(policy.allowlistEntryFormat[key]))) {
      report("allowlistEntryFormat은 allowlist의 모든 field 설명을 가져야 함");
    }
  }
  const hasInventory = Array.isArray(policy.inventory);
  const hasAllowlist = Array.isArray(policy.allowlist);
  if (!hasInventory) report("inventory는 배열이어야 함");
  if (!hasAllowlist) report("allowlist는 배열이어야 함");
  if (!hasInventory || !hasAllowlist) return violations;

  const actualByFile = new Map(files.map((file) => [file.file, file]));
  const expectedInventoryFiles = new Set();
  const unrecognizedFiles = [];
  const versions = new Map();
  for (const file of files) {
    const { kind, versionParts } = classifyScript(file.file);
    if (kind === "unrecognized") {
      unrecognizedFiles.push(file.file);
      continue;
    }
    if (kind !== "versioned") continue;
    const version = semanticVersionKey(versionParts);
    const previous = versions.get(version);
    if (previous) {
      report(`중복 semantic migration version V${version}: ${previous}, ${file.file}`);
    } else {
      versions.set(version, file.file);
    }
    if (isAboveBaseline(versionParts, policy.baselineVersion)) expectedInventoryFiles.add(file.file);
  }

  const inventoryFiles = new Set();
  for (const entry of policy.inventory) {
    if (!isPlainObject(entry)) {
      report("inventory entry는 object여야 함");
      continue;
    }
    const extra = unknownKeys(entry, INVENTORY_KEYS);
    if (extra.length) report(`inventory unknown field: ${extra.join(", ")}`);
    if (isBlank(entry.file) || !SHA256.test(entry.sha256 ?? "")) {
      report("inventory entry는 file과 소문자 SHA-256을 가져야 함");
      continue;
    }
    if (inventoryFiles.has(entry.file)) report(`inventory duplicate entry: ${entry.file}`);
    inventoryFiles.add(entry.file);
    const actual = actualByFile.get(entry.file);
    if (!actual || !expectedInventoryFiles.has(entry.file)) {
      report(`inventory unknown entry: ${entry.file}`);
    } else if (sha256(actual.sql) !== entry.sha256) {
      report(`inventory SHA-256 drift: ${entry.file}`);
    }
  }
  for (const file of expectedInventoryFiles) {
    if (!inventoryFiles.has(file)) report(`baseline 초과 migration inventory 누락: ${file}`);
  }

  const allowlistFiles = new Set();
  const validAllowlistFiles = new Set();
  for (const entry of policy.allowlist) {
    if (!isPlainObject(entry)) {
      report("allowlist entry는 object여야 함");
      continue;
    }
    let valid = true;
    const extra = unknownKeys(entry, ALLOWLIST_KEYS);
    if (extra.length) {
      report(`allowlist unknown field: ${extra.join(", ")}`);
      valid = false;
    }
    if (isBlank(entry.file) || isBlank(entry.reason) || !SHA256.test(entry.sha256 ?? "")) {
      report("allowlist entry는 file, reason, 소문자 SHA-256을 가져야 함");
      continue;
    }
    if (!GITHUB_ISSUE_URL.test(entry.approval ?? "")) {
      report(`allowlist approval은 GitHub issue URL이어야 함: ${entry.file}`);
      valid = false;
    }
    if (entry.file === V69_ALLOWLIST_FILE && entry.approval !== V69_APPROVAL) {
      report(`V69 allowlist approval은 ${V69_APPROVAL}이어야 함`);
      valid = false;
    }
    const expiry = new Date(entry.expiresAt);
    if (
      typeof entry.expiresAt !== "string" ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(entry.expiresAt) ||
      Number.isNaN(expiry.getTime()) ||
      expiry.toISOString() !== entry.expiresAt ||
      expiry.getTime() <= now
    ) {
      report(`allowlist expiresAt은 유효한 미래 ISO instant여야 함: ${entry.file}`);
      valid = false;
    }
    if (allowlistFiles.has(entry.file)) {
      report(`allowlist duplicate entry: ${entry.file}`);
      valid = false;
    }
    allowlistFiles.add(entry.file);
    const actual = actualByFile.get(entry.file);
    if (!actual) {
      report(`allowlist unknown file: ${entry.file}`);
      valid = false;
    } else if (sha256(actual.sql) !== entry.sha256) {
      report(`allowlist SHA-256 drift: ${entry.file}`);
      valid = false;
    }
    if (valid) validAllowlistFiles.add(entry.file);
  }
  for (const file of unrecognizedFiles) {
    if (!validAllowlistFiles.has(file)) {
      report(`안전하지 않거나 인식할 수 없는 migration filename: ${file}`);
    }
  }
  return violations;
}

const UNTERMINATED_QUOTED_BODY = "EASYSUBWAY_UNTERMINATED_QUOTED_BODY";
const PROCEDURAL_BODY_START = "EASYSUBWAY_PROCEDURAL_BODY_START";
const PROCEDURAL_BODY_END = "EASYSUBWAY_PROCEDURAL_BODY_END";

function isProceduralBodyPrefix(out) {
  const statement = out.slice(out.lastIndexOf(";") + 1);
  return (
    /\bDO(?:\s+LANGUAGE\s+[\w".]+)?\s*E?\s*$/i.test(statement) ||
    /\bCREATE\s+(?:OR\s+REPLACE\s+)?(?:FUNCTION|PROCEDURE)\b[\s\S]*\bAS\s*E?\s*$/i.test(
      statement,
    )
  );
}

// SQL 전처리: 주석과 일반 data literal은 제거한다. DO와 CREATE FUNCTION/PROCEDURE AS의
// quoted body만 재귀적으로 인라인해 실행되는 DDL을 검사한다. 모든 dollar literal을 코드로
// 취급하면 INSERT data 속 DDL 문자열이 오탐·new-table 면제를 만들기 때문에 문맥을 제한한다.
export function stripSqlNoise(sql) {
  let out = "";
  let i = 0;
  const n = sql.length;
  while (i < n) {
    // double-quoted identifier는 내부의 --, /*, $tag$를 SQL 구문으로 다시 해석하지
    // 않도록 하나의 원자로 소비한다. 원문은 뒤의 identifier parser가 계속 사용한다.
    if (sql[i] === '"') {
      const start = i++;
      let closed = false;
      while (i < n) {
        if (sql[i] === '"' && sql[i + 1] === '"') {
          i += 2;
        } else if (sql[i++] === '"') {
          closed = true;
          break;
        }
      }
      out += closed ? sql.slice(start, i) : ` ${UNTERMINATED_QUOTED_BODY} `;
      continue;
    }
    // 라인 주석
    if (sql[i] === "-" && sql[i + 1] === "-") {
      while (i < n && sql[i] !== "\n") i++;
      out += " ";
      continue;
    }
    // 블록 주석 (중첩 허용)
    if (sql[i] === "/" && sql[i + 1] === "*") {
      let depth = 1;
      i += 2;
      while (i < n && depth > 0) {
        if (sql[i] === "/" && sql[i + 1] === "*") {
          depth++;
          i += 2;
        } else if (sql[i] === "*" && sql[i + 1] === "/") {
          depth--;
          i += 2;
        } else {
          i++;
        }
      }
      out += " ";
      continue;
    }
    // 단일따옴표 문자열 ('' 및 E'...'의 backslash 이스케이프 인지)
    if (sql[i] === "'") {
      const preserveBody = isProceduralBodyPrefix(out);
      const backslashEscapes =
        i > 0 &&
        /[Ee]/.test(sql[i - 1]) &&
        (i === 1 || !/[A-Za-z0-9_]/.test(sql[i - 2]));
      let literal = "";
      let closed = false;
      i++;
      while (i < n) {
        if (backslashEscapes && sql[i] === "\\" && i + 1 < n) {
          literal += sql[i + 1];
          i += 2;
          continue;
        }
        if (sql[i] === "'" && sql[i + 1] === "'") {
          literal += "'";
          i += 2;
          continue;
        }
        if (sql[i] === "'") {
          i++;
          closed = true;
          break;
        }
        literal += sql[i];
        i++;
      }
      out += closed
        ? preserveBody
          ? ` ${PROCEDURAL_BODY_START} ${stripSqlNoise(literal)} ${PROCEDURAL_BODY_END} `
          : " "
        : ` ${UNTERMINATED_QUOTED_BODY} `;
      continue;
    }
    // dollar-quoted literal은 동일 tag의 다음 delimiter까지가 한 body다. 다른 tag는 본문
    // 데이터일 뿐 중첩 delimiter가 아니다.
    const tag = matchDollarTag(sql, i);
    if (tag !== null) {
      const preserveBody = isProceduralBodyPrefix(out);
      const delimiter = `$${tag}$`;
      const bodyStart = i + delimiter.length;
      const bodyEnd = sql.indexOf(delimiter, bodyStart);
      if (bodyEnd === -1) {
        out += ` ${UNTERMINATED_QUOTED_BODY} `;
        break;
      }
      out += preserveBody
        ? ` ${PROCEDURAL_BODY_START} ${stripSqlNoise(sql.slice(bodyStart, bodyEnd))} ${PROCEDURAL_BODY_END} `
        : " ";
      i = bodyEnd + delimiter.length;
      continue;
    }
    out += sql[i];
    i++;
  }
  return out;
}

// 위치 i에서 dollar-quote 태그($$ 또는 $tag$)를 인식한다. 태그(빈 문자열 허용)를 반환하고,
// 아니면 null. 위치 파라미터($1 등)는 null을 반환한다.
function matchDollarTag(sql, i) {
  if (sql[i] !== "$") return null;
  let j = i + 1;
  while (j < sql.length && /[A-Za-z0-9_]/.test(sql[j])) j++;
  if (sql[j] === "$") {
    const tag = sql.slice(i + 1, j);
    if (tag.length && /^[0-9]/.test(tag)) return null;
    return tag;
  }
  return null;
}

function normalizeId(id) {
  const parts = [];
  let i = 0;
  while (i < id.length) {
    while (/\s/.test(id[i])) i++;
    let part = "";
    if (id[i] === '"') {
      i++;
      while (i < id.length) {
        if (id[i] !== '"') {
          part += id[i++];
        } else if (id[i + 1] === '"') {
          part += '"';
          i += 2;
        } else {
          i++;
          break;
        }
      }
    } else {
      const start = i;
      while (i < id.length && /[\w$]/.test(id[i])) i++;
      part = id.slice(start, i).toLowerCase();
    }
    parts.push(part);
    while (/\s/.test(id[i])) i++;
    if (id[i] !== ".") break;
    i++;
  }
  return parts.join(".");
}

function shadowQuotedIdentifiers(sql) {
  let out = "";
  let i = 0;
  while (i < sql.length) {
    if (sql[i] !== '"') {
      out += sql[i++];
      continue;
    }
    out += " ";
    i++;
    while (i < sql.length) {
      out += " ";
      if (sql[i] === '"' && sql[i + 1] === '"') {
        out += " ";
        i += 2;
      } else if (sql[i] === '"') {
        i++;
        break;
      } else {
        i++;
      }
    }
  }
  return out;
}

const SQL_IDENTIFIER = String.raw`(?:"(?:[^"]|"")*"|[A-Za-z_][\w$]*)`;
const SQL_QUALIFIED_IDENTIFIER = String.raw`${SQL_IDENTIFIER}(?:\s*\.\s*${SQL_IDENTIFIER})*`;
const ALTER_TABLE_TARGET = String.raw`\bALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?(${SQL_QUALIFIED_IDENTIFIER})\s*\*?`;

// 문자열이 아니거나(누락·잘못된 타입) 공백만 있으면 비어있는 것으로 본다.
// non-string에 .trim()을 호출하지 않아 타입 안전하다.
function isBlank(value) {
  return typeof value !== "string" || value.trim() === "";
}

// statement order상 앞선 최상위 무조건 CREATE TABLE만 신규 테이블로 인정한다.
// IF NOT EXISTS와 procedural/conditional/후행 CREATE TABLE은 기존 테이블 면제를 만들 수 없다.
function unconditionalCreatedTable(statement) {
  const target = statement.match(new RegExp(
    String.raw`^CREATE\s+(?:UNLOGGED\s+|GLOBAL\s+|LOCAL\s+|TEMP\s+|TEMPORARY\s+)?TABLE\s+(IF\s+NOT\s+EXISTS\s+)?(${SQL_QUALIFIED_IDENTIFIER})`,
    "i",
  ));
  return target && !target[1] ? normalizeId(target[2]) : null;
}

function splitAlterActions(tail) {
  const actions = [];
  let start = 0;
  let i = 0;
  while (i < tail.length) {
    if (tail[i] !== '"') {
      if (tail[i] === ",") {
        actions.push(tail.slice(start, i).trim());
        start = i + 1;
      }
      i++;
      continue;
    }
    i++;
    while (i < tail.length) {
      if (tail[i] === '"' && tail[i + 1] === '"') i += 2;
      else if (tail[i++] === '"') break;
    }
  }
  actions.push(tail.slice(start).trim());
  return actions;
}

function splitStatements(cleaned) {
  const statements = [];
  let start = 0;
  let i = 0;
  while (i < cleaned.length) {
    if (cleaned[i] === '"') {
      i++;
      while (i < cleaned.length) {
        if (cleaned[i] === '"' && cleaned[i + 1] === '"') i += 2;
        else if (cleaned[i++] === '"') break;
      }
    } else {
      if (cleaned[i] === ";") {
        statements.push(cleaned.slice(start, i));
        start = i + 1;
      }
      i++;
    }
  }
  statements.push(cleaned.slice(start));
  return statements;
}

// 문장에서 index 위치부터 최상위(괄호 depth 0) 콤마 또는 끝까지의 절을 잘라낸다.
function clauseFrom(text, index) {
  let depth = 0;
  let i = index;
  for (; i < text.length; i++) {
    const ch = text[i];
    if (ch === '"') {
      i++;
      while (i < text.length) {
        if (text[i] === '"' && text[i + 1] === '"') i += 2;
        else if (text[i++] === '"') break;
      }
      i--;
    } else if (ch === "(") depth++;
    else if (ch === ")") {
      if (depth === 0) break;
      depth--;
    } else if (ch === "," && depth === 0) {
      break;
    }
  }
  return text.slice(index, i);
}

// 최상위 DROP <객체> 명령 중 구 canonical의 동작을 깨는 형태. 함수·트리거·타입·스키마는
// 기존 마이그레이션이 실제로 생성하는 객체이므로 제거 시 동일한 호환성 보증이 깨진다.
const DESTRUCTIVE_DROP_OBJECTS = new Map([
  ["table", "DROP TABLE"],
  ["view", "DROP VIEW"],
  ["sequence", "DROP SEQUENCE"],
  ["function", "DROP FUNCTION"],
  ["procedure", "DROP PROCEDURE"],
  ["routine", "DROP ROUTINE"],
  ["trigger", "DROP TRIGGER"],
  ["type", "DROP TYPE"],
  ["domain", "DROP DOMAIN"],
  ["schema", "DROP SCHEMA"],
]);

// DROP 절을 문맥별로 판정한다. ALTER TABLE 하위 DROP은 컬럼·제약·기본값 절이고, 그 밖의
// DROP은 최상위 객체 제거 명령이다. 알려진 파괴 형태에도, 알려진 완화 형태에도 해당하지
// 않는 DROP은 미지원으로 보고해 fail closed 시킨다(통과에는 allowlist 승인이 필요하다).
function scanDropClauses(s, keywordShadow, isAlterTable) {
  const labels = [];
  const re = /\bDROP\b/gi;
  for (const m of keywordShadow.matchAll(re)) {
    const token = s.slice(m.index + m[0].length).match(new RegExp(
      String.raw`^\s*(?:MATERIALIZED\s+)?(?:IF\s+EXISTS\s+)?(${SQL_IDENTIFIER})`,
      "i",
    ));
    if (!token) continue;
    const rawWord = token[1];
    const word = normalizeId(rawWord);
    const isKeyword = !rawWord.startsWith('"');
    // 인덱스 제거는 구 canonical의 읽기·쓰기를 거부하지 않는 완화형이다.
    if (isKeyword && word === "index") continue;
    if (isAlterTable) {
      if (isKeyword && (word === "constraint" || word === "not")) continue; // 제약·NOT NULL 완화
      // DEFAULT 제거는 그 컬럼을 생략하는 구 인스턴스의 insert를 실패시킨다.
      labels.push(isKeyword && word === "default" ? "DROP DEFAULT" : "DROP COLUMN");
      continue;
    }
    const label = isKeyword ? DESTRUCTIVE_DROP_OBJECTS.get(word) : null;
    labels.push(label ?? `미지원 DROP 형태(DROP ${word.toUpperCase()})`);
  }
  return labels;
}

// ADD 절에서 컬럼 추가가 아닌 형태(제약·생성 컬럼 추가)를 걸러내는 선행 키워드.
const ADD_NON_COLUMN_KEYWORD =
  /^(?:CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK|EXCLUDE|GENERATED|IDENTITY)\b/i;

function hasNullDefault(clause) {
  const tail = clause.match(/\bDEFAULT\s+([\s\S]*)/i)?.[1];
  if (!tail) return false;
  const boundary = /\s+(?:NOT\s+NULL|UNIQUE|PRIMARY\s+KEY|REFERENCES|CHECK|CONSTRAINT|COLLATE|GENERATED)\b/i.exec(tail);
  const unwrapOuterParentheses = (value) => {
    let unwrapped = value.trim();
    while (unwrapped.startsWith("(") && unwrapped.endsWith(")")) {
      let depth = 0;
      let closesAt = -1;
      for (let i = 0; i < unwrapped.length; i++) {
        if (unwrapped[i] === "(") depth++;
        if (unwrapped[i] === ")" && --depth === 0) {
          closesAt = i;
          break;
        }
      }
      if (closesAt !== unwrapped.length - 1) break;
      unwrapped = unwrapped.slice(1, -1).trim();
    }
    return unwrapped;
  };
  let expression = (boundary ? tail.slice(0, boundary.index) : tail).trim();
  const type = String.raw`${SQL_QUALIFIED_IDENTIFIER}(?:\s+(?:VARYING|PRECISION|WITH(?:OUT)?\s+TIME\s+ZONE))*?(?:\s*\([^()]*\))?(?:\s*\[\s*\])*`;
  const castLhs = expression.match(new RegExp(String.raw`^([\s\S]*?)\s*::\s*${type}$`, "i"))?.[1];
  if (castLhs !== undefined && unwrapOuterParentheses(castLhs).toUpperCase() === "NULL") return true;
  expression = unwrapOuterParentheses(expression);
  return new RegExp(
    String.raw`^(?:NULL\b(?:\s*::\s*${type})?|CAST\s*\(\s*NULL\s+AS\s+${type}\s*\))$`,
    "i",
  ).test(expression);
}

// ADD [COLUMN] <컬럼> ... 절을 분석한다. PostgreSQL은 COLUMN 키워드를 생략할 수 있으므로
// (`ALTER TABLE t ADD c text NOT NULL`) 두 형태를 동일하게 본다. 생략형은 ALTER TABLE
// 문맥에서만 컬럼 추가로 해석하고, 제약 추가 구문은 별도 규칙이 담당하므로 제외한다.
function scanAddColumnClauses(s, isAlterTable, isNewTable) {
  const labels = [];
  for (const m of s.matchAll(/\bADD\s+(?:(COLUMN)\s+)?(?:IF\s+NOT\s+EXISTS\s+)?/gi)) {
    const at = m.index + m[0].length;
    if (!m[1]) {
      if (!isAlterTable) continue;
      if (ADD_NON_COLUMN_KEYWORD.test(s.slice(at))) continue;
    }
    const clause = clauseFrom(s, at);
    if (!isNewTable && /\bPRIMARY\s+KEY\b/i.test(clause)) labels.push("PRIMARY KEY 컬럼 추가");
    if (!isNewTable && /\bNOT\s+NULL\b/i.test(clause)) {
      if (!/\bDEFAULT\b/i.test(clause)) labels.push("DEFAULT 없는 NOT NULL 컬럼 추가");
      if (hasNullDefault(clause)) labels.push("DEFAULT NULL인 NOT NULL 컬럼 추가");
    }
  }
  return labels;
}

// 동적 SQL 실행(EXECUTE '...') 탐지. trigger의 `EXECUTE FUNCTION|PROCEDURE`와 권한 부여
// 문법(`GRANT EXECUTE ON FUNCTION f() TO role`)의 EXECUTE는 동적 실행이 아니다.
function hasDynamicExecute(keywordShadow) {
  const withoutPrivilege = /\b(?:GRANT|REVOKE)\b/i.test(keywordShadow)
    ? keywordShadow.replace(/\bEXECUTE(?=\s+ON\s+(?:ALL\s+)?(?:FUNCTION|PROCEDURE|ROUTINE)S?\b)/gi, " ")
    : keywordShadow;
  return /\bEXECUTE\b(?!\s+(?:FUNCTION|PROCEDURE)\b)/i.test(withoutPrivilege);
}

function hasAlterSequenceRestart(s) {
  const target = s.match(new RegExp(
    String.raw`\bALTER\s+SEQUENCE\s+(?:IF\s+EXISTS\s+)?${SQL_QUALIFIED_IDENTIFIER}`,
    "i",
  ));
  return target !== null && /\bRESTART\b/i.test(shadowQuotedIdentifiers(s.slice(target.index + target[0].length)));
}

function markerCount(s, marker) {
  return s.split(marker).length - 1;
}

// 단일 SQL 텍스트를 스캔해 파괴적 DDL 규칙 위반 라벨의 (중복 제거된) 배열을 반환한다.
export function scanSqlForViolations(rawSql) {
  const cleaned = stripSqlNoise(rawSql);
  const createdTables = new Set();
  const findings = [];
  const add = (label) => findings.push(label);
  let proceduralBodyDepth = 0;
  for (const stmt of splitStatements(cleaned)) {
    const s = stmt.trim();
    if (!s) continue;

    const bodyStarts = markerCount(s, PROCEDURAL_BODY_START);
    const bodyEnds = markerCount(s, PROCEDURAL_BODY_END);
    const isProceduralBody = proceduralBodyDepth > 0 || bodyStarts > 0;

    const isAlterTable = /\bALTER\s+TABLE\b/i.test(s);
    const alterTargetMatch = isAlterTable
      ? s.match(new RegExp(ALTER_TABLE_TARGET, "i"))
      : null;
    const alterTarget = alterTargetMatch ? normalizeId(alterTargetMatch[1]) : null;
    const altersExistingTable = !alterTarget || !createdTables.has(alterTarget);
    const isNewTable = alterTarget !== null && createdTables.has(alterTarget);
    const alterActionTail = alterTargetMatch
      ? s.slice(alterTargetMatch.index + alterTargetMatch[0].length)
      : "";
    const alterActions = splitAlterActions(alterActionTail);
    const keywordShadow = shadowQuotedIdentifiers(s);

    if (s.includes(UNTERMINATED_QUOTED_BODY)) add("미종결 quoted body");
    if (/\bTRUNCATE\b/i.test(keywordShadow)) add("TRUNCATE");
    if (/\bREVOKE\b/i.test(keywordShadow)) add("REVOKE 권한");
    if (hasDynamicExecute(keywordShadow)) add("동적 EXECUTE");
    if (/\bCREATE\s+OR\s+REPLACE\s+(?:FUNCTION|PROCEDURE|(?:RECURSIVE\s+)?VIEW)\b/i.test(keywordShadow)) {
      add("CREATE OR REPLACE 기존 객체");
    }
    const ruleStarter = /\bCREATE\s+(OR\s+REPLACE\s+)?RULE\b/i.exec(keywordShadow);
    if (ruleStarter) {
      const ruleTarget = s.slice(ruleStarter.index).match(new RegExp(
        String.raw`^\s*CREATE\s+(?:OR\s+REPLACE\s+)?RULE\s+${SQL_IDENTIFIER}\s+AS\s+ON\s+[A-Za-z_]+\s+TO\s+(?:ONLY\s+)?(${SQL_QUALIFIED_IDENTIFIER})`,
        "i",
      ));
      if (!ruleTarget || !createdTables.has(normalizeId(ruleTarget[1]))) {
        add(ruleStarter[1] ? "CREATE OR REPLACE RULE" : "CREATE RULE ON 기존 테이블");
      }
    }
    const policyStarter = /\b(?:CREATE|ALTER)\s+POLICY\b/i.exec(keywordShadow);
    if (policyStarter) {
      const policyTarget = s.slice(policyStarter.index).match(new RegExp(
        String.raw`^\s*(?:CREATE|ALTER)\s+POLICY\s+${SQL_IDENTIFIER}\s+ON\s+(?:ONLY\s+)?(${SQL_QUALIFIED_IDENTIFIER})`,
        "i",
      ));
      if (!policyTarget || !createdTables.has(normalizeId(policyTarget[1]))) {
        add("CREATE/ALTER POLICY ON 기존 테이블");
      }
    }
    const triggerMatch = s.match(
      new RegExp(
        String.raw`\bCREATE\s+(?:OR\s+REPLACE\s+)?(?:CONSTRAINT\s+)?TRIGGER\b[\s\S]*?\bON\s+(?:ONLY\s+)?(${SQL_QUALIFIED_IDENTIFIER})`,
        "i",
      ),
    );
    if (triggerMatch && !createdTables.has(normalizeId(triggerMatch[1]))) {
      add("CREATE TRIGGER ON 기존 테이블");
    }
    if (/\bRENAME\b/i.test(keywordShadow)) add("RENAME");
    if (/\bSET\s+NOT\s+NULL\b/i.test(keywordShadow)) add("SET NOT NULL");
    if (alterActions.some((action) =>
      new RegExp(String.raw`^ALTER\s+(?:COLUMN\s+)?${SQL_IDENTIFIER}\s+SET\s+DEFAULT\b`, "i").test(action) &&
      hasNullDefault(action),
    )) {
      add("SET DEFAULT NULL");
    }
    if (hasAlterSequenceRestart(s)) {
      add("ALTER SEQUENCE RESTART");
    }
    if (
      new RegExp(
        String.raw`\bALTER\s+(?:(?:MATERIALIZED\s+)?VIEW|TABLE|SEQUENCE|TYPE|DOMAIN|SCHEMA)\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?${SQL_QUALIFIED_IDENTIFIER}\s+SET\s+SCHEMA\b`,
        "i",
      ).test(s)
    ) {
      add("SET SCHEMA");
    }
    if (alterActions.some((action) => new RegExp(
      String.raw`^ALTER\s+(?:COLUMN\s+)?${SQL_IDENTIFIER}\s+(?:SET\s+DATA\s+)?TYPE\b`,
      "i",
    ).test(action))) {
      add("ALTER COLUMN TYPE");
    }
    if (
      /\bALTER\s+DOMAIN\b/i.test(keywordShadow) &&
      new RegExp(
        String.raw`\bALTER\s+DOMAIN\s+(?:IF\s+EXISTS\s+)?${SQL_QUALIFIED_IDENTIFIER}\s+ADD\s+(?:CONSTRAINT\s+${SQL_IDENTIFIER}\s+)?CHECK\b`,
        "i",
      ).test(s)
    ) {
      add("ALTER DOMAIN ADD CONSTRAINT");
    }

    for (const label of scanDropClauses(s, keywordShadow, isAlterTable)) add(label);
    for (const label of scanAddColumnClauses(s, isAlterTable, isNewTable)) add(label);

    if (isAlterTable) {
      if (/\bENABLE\s+ROW\s+LEVEL\s+SECURITY\b/i.test(s)) {
        add("ENABLE ROW LEVEL SECURITY");
      }
      if (
        altersExistingTable &&
        alterActions.some((action) => /^(?:ENABLE|DISABLE)(?:\s+(?:REPLICA|ALWAYS))?\s+TRIGGER\b/i.test(action))
      ) {
        add("ALTER TABLE TRIGGER 활성화/비활성화");
      }
      if (alterActions.some((action) => /^DETACH\s+PARTITION\b/i.test(action))) {
        add("ALTER TABLE DETACH PARTITION");
      }
      if (alterActions.some((action) => new RegExp(
        String.raw`^ALTER\s+(?:COLUMN\s+)?${SQL_IDENTIFIER}\s+RESTART\b`,
        "i",
      ).test(action))) {
        add("ALTER TABLE ALTER COLUMN RESTART");
      }
      if (
        /\bFORCE\s+ROW\s+LEVEL\s+SECURITY\b/i.test(s) &&
        !/\bNO\s+FORCE\s+ROW\s+LEVEL\s+SECURITY\b/i.test(s)
      ) {
        add("FORCE ROW LEVEL SECURITY");
      }
      if (new RegExp(
        String.raw`\bALTER\s+(?:COLUMN\s+)?${SQL_IDENTIFIER}\s+SET\s+GENERATED\s+ALWAYS\b`,
        "i",
      ).test(s)) {
        add("SET GENERATED ALWAYS");
      }
      if (
        altersExistingTable &&
        new RegExp(
          String.raw`\bALTER\s+(?:COLUMN\s+)?${SQL_IDENTIFIER}\s+ADD\s+GENERATED\s+ALWAYS\s+AS\s+IDENTITY\b`,
          "i",
        ).test(s)
      ) {
        add("ADD GENERATED ALWAYS AS IDENTITY");
      }

      if (
        /\bADD\s+(?:CONSTRAINT\b|PRIMARY\s+KEY\b|UNIQUE\b|FOREIGN\s+KEY\b|CHECK\b|EXCLUDE\b)/i.test(s) ||
        new RegExp(
          String.raw`\bADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?${SQL_IDENTIFIER}\s+[^;]*\bUNIQUE\b`,
          "i",
        ).test(s)
      ) {
        if (!alterTarget || !createdTables.has(alterTarget)) {
          add("기존 테이블에 제약(ADD CONSTRAINT) 추가");
        }
      }
    }

    if (/\bCREATE\s+UNIQUE\s+(?:NULLS\s+(?:NOT\s+)?DISTINCT\s+)?INDEX\b/i.test(s)) {
      const onMatch = s.match(
        new RegExp(String.raw`\bON\s+(?:ONLY\s+)?(${SQL_QUALIFIED_IDENTIFIER})`, "i"),
      );
      const target = onMatch ? normalizeId(onMatch[1]) : null;
      if (!target || !createdTables.has(target)) {
        add("기존 테이블에 UNIQUE INDEX 추가");
      }
    }

    const createdTable = isProceduralBody ? null : unconditionalCreatedTable(s);
    if (createdTable) createdTables.add(createdTable);
    proceduralBodyDepth += bodyStarts - bodyEnds;
  }
  return [...new Set(findings)];
}

// Flyway 스크립트 유형을 파일명으로 판정한다.
// - versioned: `V<버전>__<설명>.sql` (버전은 숫자 파트를 `.` 또는 `_`로 구분)
// - repeatable: `R__<설명>.sql`
// - callback: `beforeMigrate`, `afterEachMigrate`, `createSchema` 등(선택적 `__<설명>`)
// - ignored: .sql 이 아닌 리소스(예: `<script>.sql.conf` 설정 파일)
// - unrecognized: 그 밖의 .sql — 스캐너가 실행 조건을 판정할 수 없어 fail closed 대상이다
//   (undo 스크립트 `U<버전>__*.sql`도 여기 포함된다).
export function classifyScript(filePath) {
  const base = path.basename(filePath);
  if (!/\.sql$/i.test(base)) return { kind: "ignored", versionParts: null };
  const stem = base.slice(0, -".sql".length);
  const versioned = stem.match(/^V(\d+(?:[._]\d+)*)__.+$/);
  if (versioned) {
    return {
      kind: "versioned",
      versionParts: versioned[1].split(/[._]/).map((part) => Number.parseInt(part, 10)),
    };
  }
  if (/^R__.+$/.test(stem)) return { kind: "repeatable", versionParts: null };
  if (/^(?:(?:before|after)[A-Za-z]*|createSchema)(?:__.+)?$/i.test(stem)) {
    return { kind: "callback", versionParts: null };
  }
  return { kind: "unrecognized", versionParts: null };
}

// 기존 API: 버전의 최상위 파트를 반환한다(버전 비교는 isAboveBaseline을 쓴다).
export function parseVersion(fileName) {
  const { versionParts } = classifyScript(fileName);
  return versionParts ? versionParts[0] : null;
}

// 숫자 파트 배열 버전이 정수 baseline을 초과하는지 판단한다. `V66.1__`처럼 최상위 파트가
// baseline과 같고 하위 파트가 있으면 baseline 초과(신규 파일)로 본다.
export function isAboveBaseline(versionParts, baselineVersion) {
  const [major, ...rest] = versionParts;
  if (major !== baselineVersion) return major > baselineVersion;
  return rest.some((part) => part > 0);
}

// migration 디렉토리를 재귀 스캔해 Flyway가 실행할 수 있는 .sql 리소스를 전부 읽는다
// (중첩 디렉토리·repeatable·callback 포함). 파일명은 dir 기준 상대 경로로 보고한다.
export function loadMigrationFiles(dir) {
  const files = [];
  const walk = (current, prefix) => {
    // 로케일에 흔들리지 않는 코드포인트 순서로 정렬해 보고 순서를 재현 가능하게 유지한다.
    const entries = readdirSync(current, { withFileTypes: true }).sort((a, b) =>
      a.name < b.name ? -1 : a.name > b.name ? 1 : 0,
    );
    for (const entry of entries) {
      const absolute = path.join(current, entry.name);
      const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) {
        walk(absolute, relative);
        continue;
      }
      if (!/\.sql$/i.test(entry.name)) continue;
      files.push({ file: relative, sql: readFileSync(absolute, "utf8") });
    }
  };
  walk(dir, "");
  return files;
}

// 유형별 스캔 범위를 적용하고 allowlist로 fail closed를 적용한다.
// - versioned: baseline 초과 버전만 스캔(그 이하는 게이트 도입 전 grandfather)
// - repeatable/callback: 버전 비교 기준이 없고 재실행될 수 있으므로 항상 스캔
// - unrecognized: 스캐너 사각지대이므로 스캔 결과와 무관하게 위반으로 보고
export function evaluateMigrationSet(files, { baselineVersion = 0, allowlist = [] } = {}) {
  const allow = new Map(allowlist.map((entry) => [entry.file, entry]));
  const violations = [];
  const report = (file, findings, why) => {
    const entry = allow.get(file);
    if (entry) {
      if (isBlank(entry.reason) || isBlank(entry.approval)) {
        violations.push({ file, findings, why: "allowlist reason/approval 누락" });
      }
      return;
    }
    violations.push(why === undefined ? { file, findings } : { file, findings, why });
  };
  for (const f of files) {
    const { kind, versionParts } = classifyScript(f.file);
    if (kind === "ignored") continue;
    if (kind === "unrecognized") {
      report(f.file, ["미지원 스크립트 유형"], "Flyway 스크립트 유형 판정 불가 — 스캔 사각지대");
      continue;
    }
    if (kind === "versioned" && !isAboveBaseline(versionParts, baselineVersion)) continue;
    const findings = scanSqlForViolations(f.sql);
    if (findings.length === 0) continue;
    report(f.file, findings);
  }
  return violations;
}

if (isMainModule(import.meta.url)) {
  const policy = loadJson(path.join(repoRoot, POLICY_PATH));
  const files = loadMigrationFiles(path.join(repoRoot, MIGRATION_DIR));
  const policyViolations = validateMigrationPolicy(policy, files);
  const violations = policyViolations.length
    ? policyViolations
    : evaluateMigrationSet(files, {
    baselineVersion: policy.baselineVersion,
    allowlist: policy.allowlist ?? [],
    });
  if (violations.length) {
    console.error("파괴적 DDL 마이그레이션 위반:");
    for (const v of violations) {
      const why = v.why ? ` (${v.why})` : "";
      console.error(`- ${v.file}: ${v.findings.join(", ")}${why}`);
    }
    console.error(
      "\nexpand/contract(순수 additive) 계약 위반입니다. 의도적 contract 단계라면",
    );
    console.error(`${POLICY_PATH}의 inventory/allowlist와 승인 만료 시각을 실제 migration과 일치시키세요.`);
    process.exit(1);
  }
}
