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
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const POLICY_PATH = "backend/quality/migration-ddl-gate.json";
const MIGRATION_DIR = "backend/src/main/resources/db/migration/postgresql";

export function loadJson(filePath) {
  return JSON.parse(readFileSync(filePath, "utf8"));
}

// SQL 전처리: 라인 주석(--), 블록 주석(/* */, 중첩 허용), 단일따옴표 문자열의 내용을 제거한다.
// dollar-quoted 블록($$...$$, $tag$...$tag$)은 델리미터만 제거하고 본문은 인라인으로 남겨,
// DO/함수 본문 안의 파괴적 DDL이 사각지대 없이 스캔되도록 한다. 본문 내부의 단일따옴표
// 문자열(예: RAISE EXCEPTION 메시지)은 본문 인라인 후에도 동일 규칙으로 제거되어 오탐을 막는다.
export function stripSqlNoise(sql) {
  let out = "";
  let i = 0;
  const n = sql.length;
  const dollarStack = [];
  while (i < n) {
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
    // 단일따옴표 문자열 ('' 이스케이프 인지)
    if (sql[i] === "'") {
      i++;
      while (i < n) {
        if (sql[i] === "'" && sql[i + 1] === "'") {
          i += 2;
          continue;
        }
        if (sql[i] === "'") {
          i++;
          break;
        }
        i++;
      }
      out += " ";
      continue;
    }
    // dollar-quoted 델리미터
    const tag = matchDollarTag(sql, i);
    if (tag !== null) {
      if (dollarStack.length && dollarStack[dollarStack.length - 1] === tag) {
        dollarStack.pop();
      } else {
        dollarStack.push(tag);
      }
      out += " ";
      i += tag.length + 2;
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
  return id.replace(/"/g, "").toLowerCase();
}

// 문자열이 아니거나(누락·잘못된 타입) 공백만 있으면 비어있는 것으로 본다.
// non-string에 .trim()을 호출하지 않아 타입 안전하다.
function isBlank(value) {
  return typeof value !== "string" || value.trim() === "";
}

// 같은 파일에서 새로 만든 테이블 집합. `CREATE TABLE IF NOT EXISTS`는 운영에 테이블이
// 이미 있으면 no-op으로 지나가므로 "신규 생성"으로 볼 수 없다(뒤따르는 제약·UNIQUE INDEX
// 추가가 구 canonical의 쓰기를 거부할 수 있다). 무조건적 CREATE TABLE만 면제 대상이다.
function collectCreatedTables(cleaned) {
  const set = new Set();
  const re =
    /\bCREATE\s+(?:UNLOGGED\s+|GLOBAL\s+|LOCAL\s+|TEMP\s+|TEMPORARY\s+)?TABLE\s+(IF\s+NOT\s+EXISTS\s+)?([\w".]+)/gi;
  for (const m of cleaned.matchAll(re)) {
    if (m[1]) continue;
    set.add(normalizeId(m[2]));
  }
  return set;
}

function splitStatements(cleaned) {
  return cleaned.split(";");
}

// 문장에서 index 위치부터 최상위(괄호 depth 0) 콤마 또는 끝까지의 절을 잘라낸다.
function clauseFrom(text, index) {
  let depth = 0;
  let i = index;
  for (; i < text.length; i++) {
    const ch = text[i];
    if (ch === "(") depth++;
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
function scanDropClauses(s, isAlterTable) {
  const labels = [];
  const re = /\bDROP\s+(?:MATERIALIZED\s+)?(?:IF\s+EXISTS\s+)?([A-Za-z_][\w".]*)/gi;
  for (const m of s.matchAll(re)) {
    const word = normalizeId(m[1]);
    // 인덱스 제거는 구 canonical의 읽기·쓰기를 거부하지 않는 완화형이다.
    if (word === "index") continue;
    if (isAlterTable) {
      if (word === "constraint" || word === "not") continue; // 제약·NOT NULL 완화
      // DEFAULT 제거는 그 컬럼을 생략하는 구 인스턴스의 insert를 실패시킨다.
      labels.push(word === "default" ? "DROP DEFAULT" : "DROP COLUMN");
      continue;
    }
    const label = DESTRUCTIVE_DROP_OBJECTS.get(word);
    labels.push(label ?? `미지원 DROP 형태(DROP ${word.toUpperCase()})`);
  }
  return labels;
}

// ADD 절에서 컬럼 추가가 아닌 형태(제약·생성 컬럼 추가)를 걸러내는 선행 키워드.
const ADD_NON_COLUMN_KEYWORD =
  /^(?:CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK|EXCLUDE|GENERATED|IDENTITY)\b/i;

// ADD [COLUMN] <컬럼> ... 절을 분석한다. PostgreSQL은 COLUMN 키워드를 생략할 수 있으므로
// (`ALTER TABLE t ADD c text NOT NULL`) 두 형태를 동일하게 본다. 생략형은 ALTER TABLE
// 문맥에서만 컬럼 추가로 해석하고, 제약 추가 구문은 별도 규칙이 담당하므로 제외한다.
function scanAddColumnClauses(s, isAlterTable) {
  const labels = [];
  for (const m of s.matchAll(/\bADD\s+(?:(COLUMN)\s+)?(?:IF\s+NOT\s+EXISTS\s+)?/gi)) {
    const at = m.index + m[0].length;
    if (!m[1]) {
      if (!isAlterTable) continue;
      if (ADD_NON_COLUMN_KEYWORD.test(s.slice(at))) continue;
    }
    const clause = clauseFrom(s, at);
    if (/\bNOT\s+NULL\b/i.test(clause) && !/\bDEFAULT\b/i.test(clause)) {
      labels.push("DEFAULT 없는 NOT NULL 컬럼 추가");
    }
  }
  return labels;
}

// 동적 SQL 실행(EXECUTE '...') 탐지. trigger의 `EXECUTE FUNCTION|PROCEDURE`와 권한 부여
// 문법(`GRANT EXECUTE ON FUNCTION f() TO role`)의 EXECUTE는 동적 실행이 아니다.
function hasDynamicExecute(s) {
  const withoutPrivilege = /\b(?:GRANT|REVOKE)\b/i.test(s)
    ? s.replace(/\bEXECUTE(?=\s+ON\s+(?:ALL\s+)?(?:FUNCTION|PROCEDURE|ROUTINE)S?\b)/gi, " ")
    : s;
  return /\bEXECUTE\b(?!\s+(?:FUNCTION|PROCEDURE)\b)/i.test(withoutPrivilege);
}

// 단일 SQL 텍스트를 스캔해 파괴적 DDL 규칙 위반 라벨의 (중복 제거된) 배열을 반환한다.
export function scanSqlForViolations(rawSql) {
  const cleaned = stripSqlNoise(rawSql);
  const createdTables = collectCreatedTables(cleaned);
  const findings = [];
  const add = (label) => findings.push(label);
  for (const stmt of splitStatements(cleaned)) {
    const s = stmt.trim();
    if (!s) continue;

    const isAlterTable = /\bALTER\s+TABLE\b/i.test(s);

    if (/\bTRUNCATE\b/i.test(s)) add("TRUNCATE");
    if (hasDynamicExecute(s)) add("동적 EXECUTE");
    if (/\bRENAME\b/i.test(s)) add("RENAME");
    if (/\bSET\s+NOT\s+NULL\b/i.test(s)) add("SET NOT NULL");
    if (/\bALTER\s+(?:COLUMN\s+)?(?!TABLE\b)"?\w+"?\s+(?:SET\s+DATA\s+)?TYPE\b/i.test(s)) {
      add("ALTER COLUMN TYPE");
    }

    for (const label of scanDropClauses(s, isAlterTable)) add(label);
    for (const label of scanAddColumnClauses(s, isAlterTable)) add(label);

    if (isAlterTable) {
      const targetMatch = s.match(
        /\bALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?([\w".]+)/i,
      );
      const alterTarget = targetMatch ? normalizeId(targetMatch[1]) : null;

      if (/\bADD\s+(?:CONSTRAINT\b|PRIMARY\s+KEY\b|UNIQUE\b|FOREIGN\s+KEY\b|CHECK\b)/i.test(s)) {
        if (!alterTarget || !createdTables.has(alterTarget)) {
          add("기존 테이블에 제약(ADD CONSTRAINT) 추가");
        }
      }
    }

    if (/\bCREATE\s+UNIQUE\s+INDEX\b/i.test(s)) {
      const onMatch = s.match(/\bON\s+(?:ONLY\s+)?([\w".]+)/i);
      const target = onMatch ? normalizeId(onMatch[1]) : null;
      if (!target || !createdTables.has(target)) {
        add("기존 테이블에 UNIQUE INDEX 추가");
      }
    }
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
  const violations = evaluateMigrationSet(files, {
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
    console.error(`${POLICY_PATH}의 allowlist에 사유(reason)와 승인 근거(approval)를 명기하세요.`);
    process.exit(1);
  }
}
