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

function collectCreatedTables(cleaned) {
  const set = new Set();
  const re =
    /\bCREATE\s+(?:UNLOGGED\s+|GLOBAL\s+|LOCAL\s+|TEMP\s+|TEMPORARY\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w".]+)/gi;
  for (const m of cleaned.matchAll(re)) set.add(normalizeId(m[1]));
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

// 단일 SQL 텍스트를 스캔해 파괴적 DDL 규칙 위반 라벨의 (중복 제거된) 배열을 반환한다.
export function scanSqlForViolations(rawSql) {
  const cleaned = stripSqlNoise(rawSql);
  const createdTables = collectCreatedTables(cleaned);
  const findings = [];
  const add = (label) => findings.push(label);
  for (const stmt of splitStatements(cleaned)) {
    const s = stmt.trim();
    if (!s) continue;

    if (/\bTRUNCATE\b/i.test(s)) add("TRUNCATE");
    if (/\bEXECUTE\b(?!\s+(?:FUNCTION|PROCEDURE)\b)/i.test(s)) add("동적 EXECUTE");
    if (/\bRENAME\b/i.test(s)) add("RENAME");
    if (/\bDROP\s+TABLE\b/i.test(s)) add("DROP TABLE");
    if (/\bDROP\s+(?:MATERIALIZED\s+)?VIEW\b/i.test(s)) add("DROP VIEW");
    if (/\bDROP\s+SEQUENCE\b/i.test(s)) add("DROP SEQUENCE");
    if (/\bSET\s+NOT\s+NULL\b/i.test(s)) add("SET NOT NULL");
    if (/\bALTER\s+(?:COLUMN\s+)?(?!TABLE\b)"?\w+"?\s+(?:SET\s+DATA\s+)?TYPE\b/i.test(s)) {
      add("ALTER COLUMN TYPE");
    }

    for (const m of s.matchAll(/\bADD\s+COLUMN\b/gi)) {
      const clause = clauseFrom(s, m.index + m[0].length);
      if (/\bNOT\s+NULL\b/i.test(clause) && !/\bDEFAULT\b/i.test(clause)) {
        add("DEFAULT 없는 NOT NULL 컬럼 추가");
      }
    }

    const isAlterTable = /\bALTER\s+TABLE\b/i.test(s);
    if (isAlterTable) {
      const targetMatch = s.match(
        /\bALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?([\w".]+)/i,
      );
      const alterTarget = targetMatch ? normalizeId(targetMatch[1]) : null;

      for (const m of s.matchAll(/\bDROP\s+(?:IF\s+EXISTS\s+)?([A-Za-z_][\w".]*)/gi)) {
        const word = m[1].toLowerCase();
        if (word === "constraint" || word === "default" || word === "not" || word === "index") {
          continue;
        }
        add("DROP COLUMN");
        break;
      }

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

export function parseVersion(fileName) {
  const m = fileName.match(/^V(\d+)__/);
  return m ? Number.parseInt(m[1], 10) : null;
}

export function loadMigrationFiles(dir) {
  return readdirSync(dir)
    .filter((name) => /^V\d+__.*\.sql$/.test(name))
    .map((name) => ({
      file: name,
      version: parseVersion(name),
      sql: readFileSync(path.join(dir, name), "utf8"),
    }));
}

// baseline 초과 버전 파일만 스캔하고 allowlist로 fail closed를 적용한다.
export function evaluateMigrationSet(files, { baselineVersion = 0, allowlist = [] } = {}) {
  const allow = new Map(allowlist.map((entry) => [entry.file, entry]));
  const violations = [];
  for (const f of files) {
    if (f.version == null || f.version <= baselineVersion) continue;
    const findings = scanSqlForViolations(f.sql);
    if (findings.length === 0) continue;
    const entry = allow.get(f.file);
    if (entry) {
      if (isBlank(entry.reason) || isBlank(entry.approval)) {
        violations.push({ file: f.file, findings, why: "allowlist reason/approval 누락" });
      }
      continue;
    }
    violations.push({ file: f.file, findings });
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
