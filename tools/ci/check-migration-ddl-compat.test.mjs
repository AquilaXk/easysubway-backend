import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  stripSqlNoise,
  scanSqlForViolations,
  parseVersion,
  loadMigrationFiles,
  evaluateMigrationSet,
  loadJson,
} from "./check-migration-ddl-compat.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../..");
const fixturesDir = path.join(here, "fixtures/migration-ddl");
const realMigrationDir = path.join(repoRoot, "backend/src/main/resources/db/migration/postgresql");

function readFixture(kind, name) {
  return readFileSync(path.join(fixturesDir, kind, name), "utf8");
}

// 각 파괴 범주 fixture가 탐지되는지(파일명 → 기대 라벨).
const destructiveCases = [
  ["V100__drop_table.sql", "DROP TABLE"],
  ["V101__drop_column.sql", "DROP COLUMN"],
  ["V102__drop_view.sql", "DROP VIEW"],
  ["V103__drop_sequence.sql", "DROP SEQUENCE"],
  ["V104__rename_column.sql", "RENAME"],
  ["V105__alter_column_type.sql", "ALTER COLUMN TYPE"],
  ["V106__set_not_null.sql", "SET NOT NULL"],
  ["V107__add_column_not_null_no_default.sql", "DEFAULT 없는 NOT NULL 컬럼 추가"],
  ["V108__truncate.sql", "TRUNCATE"],
  ["V109__add_constraint_existing.sql", "기존 테이블에 제약(ADD CONSTRAINT) 추가"],
  ["V110__create_unique_index_existing.sql", "기존 테이블에 UNIQUE INDEX 추가"],
  ["V111__dynamic_execute.sql", "동적 EXECUTE"],
  ["V112__do_block_hidden_drop.sql", "DROP TABLE"],
];

for (const [name, expectedLabel] of destructiveCases) {
  test(`파괴적 fixture ${name}는 "${expectedLabel}"로 탐지된다`, () => {
    const findings = scanSqlForViolations(readFixture("destructive", name));
    assert.ok(
      findings.includes(expectedLabel),
      `기대 라벨 없음. findings=${JSON.stringify(findings)}`,
    );
  });
}

const additiveCases = [
  "V200__create_table.sql",
  "V201__add_column_nullable.sql",
  "V202__add_column_default_not_null.sql",
  "V203__create_index.sql",
  "V204__new_table_constraint.sql",
  "V205__relaxations_and_dml.sql",
  "V206__do_block_raise_message.sql",
];

for (const name of additiveCases) {
  test(`additive fixture ${name}는 통과한다(위반 없음)`, () => {
    const findings = scanSqlForViolations(readFixture("additive", name));
    assert.deepEqual(findings, [], `예상치 못한 findings=${JSON.stringify(findings)}`);
  });
}

test("DO 블록 안 RAISE EXCEPTION 메시지 문자열은 오탐을 내지 않는다", () => {
  const findings = scanSqlForViolations(readFixture("additive", "V206__do_block_raise_message.sql"));
  assert.deepEqual(findings, []);
});

test("DO 블록 본문 안에 숨은 파괴적 DDL은 사각지대 없이 탐지된다", () => {
  const findings = scanSqlForViolations(readFixture("destructive", "V112__do_block_hidden_drop.sql"));
  assert.ok(findings.includes("DROP TABLE"));
});

test("stripSqlNoise는 라인/블록 주석과 문자열을 제거하고 dollar 본문은 인라인한다", () => {
  const cleaned = stripSqlNoise(
    "-- DROP TABLE in a comment\n/* DROP TABLE block */\nSELECT 'DROP TABLE in string';\nDO $$ DROP TABLE hidden; $$;",
  );
  // 주석·문자열 속 DROP TABLE은 사라지고, dollar 본문 속 DROP TABLE만 남는다.
  assert.equal((cleaned.match(/DROP TABLE/gi) ?? []).length, 1);
  assert.match(cleaned, /DROP TABLE hidden/);
});

test("위치 파라미터($1)는 dollar-quote로 오인하지 않는다", () => {
  const findings = scanSqlForViolations(
    "UPDATE service_notices SET title = $1 WHERE id = $2;",
  );
  assert.deepEqual(findings, []);
});

test("trigger의 EXECUTE FUNCTION/PROCEDURE는 동적 EXECUTE로 오탐하지 않는다", () => {
  const findings = scanSqlForViolations(
    "CREATE TRIGGER t BEFORE INSERT ON snapshots FOR EACH ROW EXECUTE FUNCTION guard();",
  );
  assert.deepEqual(findings, []);
});

test("parseVersion은 Vn__ 접두어에서 버전을 추출한다", () => {
  assert.equal(parseVersion("V66__service_notice_lifecycle.sql"), 66);
  assert.equal(parseVersion("V7__facility_report_public_receipt_code.sql"), 7);
  assert.equal(parseVersion("R__repeatable.sql"), null);
});

test("evaluateMigrationSet은 baseline 이하 버전을 건너뛴다", () => {
  const files = [
    { file: "V66__x.sql", version: 66, sql: "DROP TABLE t;" },
    { file: "V67__y.sql", version: 67, sql: "DROP TABLE t;" },
  ];
  const violations = evaluateMigrationSet(files, { baselineVersion: 66, allowlist: [] });
  assert.equal(violations.length, 1);
  assert.equal(violations[0].file, "V67__y.sql");
});

test("evaluateMigrationSet은 사유·승인이 명기된 allowlist 항목만 통과시킨다", () => {
  const files = [{ file: "V67__contract.sql", version: 67, sql: "DROP TABLE t;" }];
  const approved = evaluateMigrationSet(files, {
    baselineVersion: 66,
    allowlist: [{ file: "V67__contract.sql", reason: "의도적 contract 단계", approval: "#2365" }],
  });
  assert.deepEqual(approved, []);

  const incomplete = evaluateMigrationSet(files, {
    baselineVersion: 66,
    allowlist: [{ file: "V67__contract.sql", reason: "사유만 있고 승인 근거 없음" }],
  });
  assert.equal(incomplete.length, 1);
  assert.match(incomplete[0].why, /allowlist/);
});

test("evaluateMigrationSet은 공백만 있는 사유·승인 allowlist 항목을 거부한다", () => {
  const files = [{ file: "V67__contract.sql", version: 67, sql: "DROP TABLE t;" }];
  for (const bogus of [
    { reason: "   ", approval: "#2365" },
    { reason: "의도적 contract 단계", approval: "\t\n " },
    { reason: "", approval: "" },
  ]) {
    const violations = evaluateMigrationSet(files, {
      baselineVersion: 66,
      allowlist: [{ file: "V67__contract.sql", ...bogus }],
    });
    assert.equal(violations.length, 1, `공백 통과: ${JSON.stringify(bogus)}`);
    assert.match(violations[0].why, /allowlist/);
  }
});

test("현재 트리의 실제 마이그레이션은 baseline(V66)·공식 allowlist에서 위반이 없다(회귀 없음)", () => {
  const policy = loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json"));
  const files = loadMigrationFiles(realMigrationDir);
  const violations = evaluateMigrationSet(files, {
    baselineVersion: policy.baselineVersion,
    allowlist: policy.allowlist ?? [],
  });
  assert.deepEqual(
    violations,
    [],
    `baseline ${policy.baselineVersion}에서 위반 발생: ${JSON.stringify(violations)}`,
  );
});

test("baseline을 0으로 낮추면 기존 파괴 패턴(V64 UNIQUE INDEX 등)이 탐지되어 검사기 동작을 증명한다", () => {
  const files = loadMigrationFiles(realMigrationDir);
  const violations = evaluateMigrationSet(files, { baselineVersion: 0, allowlist: [] });
  assert.ok(violations.length > 0, "baseline 0에서 기존 파괴 패턴이 하나도 탐지되지 않았다");
  const v64 = violations.find((v) => v.file.startsWith("V64__"));
  assert.ok(v64, "V64가 탐지되지 않았다");
  assert.ok(v64.findings.includes("기존 테이블에 UNIQUE INDEX 추가"));
});
