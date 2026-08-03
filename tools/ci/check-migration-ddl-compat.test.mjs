import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  stripSqlNoise,
  scanSqlForViolations,
  classifyScript,
  parseVersion,
  isAboveBaseline,
  loadMigrationFiles,
  evaluateMigrationSet,
  validateMigrationPolicy,
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
  ["V113__drop_trigger.sql", "DROP TRIGGER"],
  ["V114__drop_type.sql", "DROP TYPE"],
  ["V115__drop_schema.sql", "DROP SCHEMA"],
  ["V116__drop_function_cascade.sql", "DROP FUNCTION"],
  ["V117__add_column_implicit_not_null.sql", "DEFAULT 없는 NOT NULL 컬럼 추가"],
  ["V118__if_not_exists_table_constraint.sql", "기존 테이블에 제약(ADD CONSTRAINT) 추가"],
  ["V119__drop_default.sql", "DROP DEFAULT"],
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
  "V207__grant_execute_function.sql",
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

test("GRANT/REVOKE EXECUTE ON FUNCTION은 동적 EXECUTE로 오탐하지 않는다", () => {
  assert.deepEqual(
    scanSqlForViolations("GRANT EXECUTE ON FUNCTION guard_lineage() TO easysubway_app;"),
    [],
  );
  assert.deepEqual(
    scanSqlForViolations("REVOKE EXECUTE ON ALL PROCEDURES IN SCHEMA public FROM readonly;"),
    [],
  );
});

test("권한 부여 문장이 있어도 같은 파일의 동적 EXECUTE는 탐지된다", () => {
  const findings = scanSqlForViolations(
    "GRANT EXECUTE ON FUNCTION guard_lineage() TO easysubway_app;\n" +
      "DO $m$ BEGIN EXECUTE 'ALTER TABLE t DROP COLUMN c'; END $m$;",
  );
  assert.ok(findings.includes("동적 EXECUTE"), `findings=${JSON.stringify(findings)}`);
});

test("COLUMN 생략형 ADD도 DEFAULT가 있으면 통과하고 제약 추가로 오탐하지 않는다", () => {
  assert.deepEqual(
    scanSqlForViolations("ALTER TABLE t ADD required_value TEXT NOT NULL DEFAULT 'x';"),
    [],
  );
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE t (id BIGSERIAL PRIMARY KEY);\nALTER TABLE t ADD CHECK (id > 0);"),
    [],
  );
});

test("기존 테이블 inline ADD PRIMARY KEY·NOT NULL DEFAULT NULL과 ALTER NULL default·schema 이동은 fail closed 한다", () => {
  assert.ok(
    scanSqlForViolations("ALTER TABLE t ADD COLUMN id BIGINT PRIMARY KEY;").includes(
      "PRIMARY KEY 컬럼 추가",
    ),
  );
  assert.ok(
    scanSqlForViolations("ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT NULL;").includes(
      "DEFAULT NULL인 NOT NULL 컬럼 추가",
    ),
  );
  assert.ok(
    scanSqlForViolations("ALTER TABLE t ALTER COLUMN value SET DEFAULT NULL;").includes(
      "SET DEFAULT NULL",
    ),
  );
  assert.ok(
    scanSqlForViolations("ALTER TABLE t ALTER value SET DEFAULT NULL;").includes(
      "SET DEFAULT NULL",
    ),
  );
  assert.ok(
    scanSqlForViolations("ALTER TABLE t SET SCHEMA archived;").includes("SET SCHEMA"));
});

test("새 테이블 PRIMARY KEY와 nullable/default non-null column은 계속 통과한다", () => {
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE new_table (id BIGINT PRIMARY KEY, value TEXT);"),
    [],
  );
  assert.deepEqual(scanSqlForViolations("ALTER TABLE t ADD COLUMN optional_value TEXT;"), []);
  assert.deepEqual(
    scanSqlForViolations("ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT 'x';"),
    [],
  );
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE t (value TEXT); ALTER TABLE t ADD COLUMN id BIGINT PRIMARY KEY;"),
    [],
  );
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE t (value TEXT); ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT NULL;"),
    [],
  );
  assert.ok(
    scanSqlForViolations("CREATE TABLE IF NOT EXISTS t (value TEXT); ALTER TABLE t ADD COLUMN id BIGINT PRIMARY KEY;").includes(
      "PRIMARY KEY 컬럼 추가",
    ),
  );
});

test("무조건적 CREATE TABLE 대상 제약 추가는 계속 면제되고 IF NOT EXISTS만 위반이 된다", () => {
  const unconditional =
    "CREATE TABLE t (id BIGINT);\nALTER TABLE t ADD CONSTRAINT t_unique UNIQUE (id);";
  const conditional =
    "CREATE TABLE IF NOT EXISTS t (id BIGINT);\nALTER TABLE t ADD CONSTRAINT t_unique UNIQUE (id);";
  assert.deepEqual(scanSqlForViolations(unconditional), []);
  assert.deepEqual(scanSqlForViolations(conditional), ["기존 테이블에 제약(ADD CONSTRAINT) 추가"]);
});

test("완화형 DROP(INDEX·CONSTRAINT·NOT NULL)은 통과하고 미지원 DROP 형태는 fail closed 된다", () => {
  assert.deepEqual(scanSqlForViolations("DROP INDEX IF EXISTS ux_legacy;"), []);
  assert.deepEqual(
    scanSqlForViolations("ALTER TABLE t ALTER COLUMN c DROP NOT NULL;"),
    [],
  );
  assert.deepEqual(scanSqlForViolations("DROP POLICY report_read ON facility_reports;"), [
    "미지원 DROP 형태(DROP POLICY)",
  ]);
});

test("classifyScript는 Flyway 스크립트 유형을 판정한다", () => {
  assert.deepEqual(classifyScript("V67__admin_user_roles.sql"), {
    kind: "versioned",
    versionParts: [67],
  });
  assert.deepEqual(classifyScript("nested/V70.1__patch.sql"), {
    kind: "versioned",
    versionParts: [70, 1],
  });
  assert.equal(classifyScript("R__refresh_view.sql").kind, "repeatable");
  assert.equal(classifyScript("afterMigrate__cleanup.sql").kind, "callback");
  assert.equal(classifyScript("beforeEachMigrate.sql").kind, "callback");
  assert.equal(classifyScript("V70__patch.sql.conf").kind, "ignored");
  assert.equal(classifyScript("legacy_manual_patch.sql").kind, "unrecognized");
  assert.equal(classifyScript("U67__undo.sql").kind, "unrecognized");
});

test("isAboveBaseline은 하위 파트가 있는 동일 major 버전을 baseline 초과로 본다", () => {
  assert.equal(isAboveBaseline([66], 66), false);
  assert.equal(isAboveBaseline([66, 1], 66), true);
  assert.equal(isAboveBaseline([65, 9], 66), false);
  assert.equal(isAboveBaseline([67], 66), true);
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

const scanScopeDir = path.join(fixturesDir, "scan-scope");

test("loadMigrationFiles는 중첩·repeatable·callback·소수점 버전 스크립트까지 재귀 수집한다", () => {
  const files = loadMigrationFiles(scanScopeDir).map((f) => f.file);
  assert.deepEqual(files, [
    "R__refresh_reporting_view.sql",
    "V10__below_baseline_drop_table.sql",
    "V70.1__decimal_version_drop_table.sql",
    "V72__additive_column.sql",
    "afterMigrate__cleanup_cache.sql",
    "legacy_manual_patch.sql",
    "nested/V71__nested_drop_column.sql",
  ]);
});

test("evaluateMigrationSet은 Flyway가 실행하는 모든 스크립트 유형을 스캔한다", () => {
  const violations = evaluateMigrationSet(loadMigrationFiles(scanScopeDir), {
    baselineVersion: 66,
    allowlist: [],
  });
  const byFile = new Map(violations.map((v) => [v.file, v]));
  assert.deepEqual(
    [...byFile.keys()].sort(),
    [
      "R__refresh_reporting_view.sql",
      "V70.1__decimal_version_drop_table.sql",
      "afterMigrate__cleanup_cache.sql",
      "legacy_manual_patch.sql",
      "nested/V71__nested_drop_column.sql",
    ],
    `위반 목록 불일치: ${JSON.stringify(violations)}`,
  );
  assert.deepEqual(byFile.get("R__refresh_reporting_view.sql").findings, ["DROP VIEW"]);
  assert.deepEqual(byFile.get("afterMigrate__cleanup_cache.sql").findings, ["TRUNCATE"]);
  assert.deepEqual(byFile.get("nested/V71__nested_drop_column.sql").findings, ["DROP COLUMN"]);
  assert.deepEqual(byFile.get("V70.1__decimal_version_drop_table.sql").findings, ["DROP TABLE"]);
  assert.match(byFile.get("legacy_manual_patch.sql").why, /판정 불가/);
});

test("Flyway 유형 판정 불가 스크립트는 allowlist 사유·승인으로만 통과한다", () => {
  const files = loadMigrationFiles(scanScopeDir).filter(
    (f) => f.file === "legacy_manual_patch.sql",
  );
  const approved = evaluateMigrationSet(files, {
    baselineVersion: 66,
    allowlist: [
      { file: "legacy_manual_patch.sql", reason: "수동 패치 기록", approval: "AquilaXk/easysubway#2365" },
    ],
  });
  assert.deepEqual(approved, []);

  const incomplete = evaluateMigrationSet(files, {
    baselineVersion: 66,
    allowlist: [{ file: "legacy_manual_patch.sql", reason: "승인 근거 없음" }],
  });
  assert.equal(incomplete.length, 1);
  assert.match(incomplete[0].why, /allowlist/);
});

function copy(value) {
  return JSON.parse(JSON.stringify(value));
}

test("정책 validator는 unknown field와 inventory/allowlist schema mismatch를 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = loadMigrationFiles(realMigrationDir);
  policy.unexpected = true;
  policy.inventory[0].unexpected = true;
  policy.allowlist[0].unexpected = true;
  policy.inventory[1] = "not-an-object";
  policy.schemaVersion = "2";
  policy.issue = "AquilaXk/easysubway#2365";
  policy.allowlist[0].file = "V69__not_the_actual_file.sql";
  const reasons = validateMigrationPolicy(policy, files).map((v) => v.why);
  assert.ok(reasons.some((why) => why.includes("최상위 unknown field")));
  assert.ok(reasons.some((why) => why.includes("inventory unknown field")));
  assert.ok(reasons.some((why) => why.includes("allowlist unknown field")));
  assert.ok(reasons.some((why) => why.includes("inventory entry는 object")));
  assert.ok(reasons.some((why) => why.includes("schemaVersion은 2여야 함")));
  assert.ok(reasons.some((why) => why.includes("issue는 GitHub issue URL이어야 함")));
  assert.ok(reasons.some((why) => why.includes("allowlist unknown file: V69__not_the_actual_file.sql")));
});

test("정책 validator는 duplicate semantic version과 인식 불가 filename을 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = [
    ...loadMigrationFiles(realMigrationDir),
    { file: "V67.0__same_semantic_version.sql", sql: "CREATE TABLE duplicate_version (id bigint);" },
    { file: "unsafe_manual_patch.sql", sql: "CREATE TABLE unsafe_name (id bigint);" },
  ];
  const reasons = validateMigrationPolicy(policy, files).map((v) => v.why);
  assert.ok(reasons.some((why) => why.includes("중복 semantic migration version V67")));
  assert.ok(reasons.some((why) => why.includes("인식할 수 없는 migration filename: unsafe_manual_patch.sql")));
});

test("정책 validator는 baseline 초과 inventory 누락·unknown entry·SHA drift를 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = loadMigrationFiles(realMigrationDir);
  policy.inventory = policy.inventory.filter((entry) => entry.file !== "V68__create_error_events.sql");
  policy.inventory[0].sha256 = "0".repeat(64);
  policy.inventory.push({ file: "V70__unknown.sql", sha256: "1".repeat(64) });
  const reasons = validateMigrationPolicy(policy, files).map((v) => v.why);
  assert.ok(reasons.some((why) => why.includes("inventory 누락: V68__create_error_events.sql")));
  assert.ok(reasons.some((why) => why.includes("inventory SHA-256 drift: V67__admin_user_roles_granted_by.sql")));
  assert.ok(reasons.some((why) => why.includes("inventory unknown entry: V70__unknown.sql")));
});

test("정책 validator는 allowlist file/SHA/approval/expiry 불일치를 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = loadMigrationFiles(realMigrationDir);
  const [entry] = policy.allowlist;
  entry.sha256 = "0".repeat(64);
  entry.approval = "AquilaXk/easysubway-backend#29";
  entry.expiresAt = "2020-01-01T00:00:00.000Z";
  const reasons = validateMigrationPolicy(policy, files).map((v) => v.why);
  assert.ok(reasons.some((why) => why.includes("allowlist SHA-256 drift")));
  assert.ok(reasons.some((why) => why.includes("allowlist approval은 GitHub issue URL")));
  assert.ok(reasons.some((why) => why.includes("allowlist expiresAt은 유효한 미래 ISO instant")));
});

test("정책 validator는 현재 V69 allowlist approval identity drift를 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = loadMigrationFiles(realMigrationDir);
  policy.allowlist[0].approval = "https://github.com/AquilaXk/easysubway/issues/9999";
  const reasons = validateMigrationPolicy(policy, files).map((v) => v.why);
  assert.ok(reasons.some((why) => why.includes("V69 allowlist approval은 https://github.com/AquilaXk/easysubway/issues/2433이어야 함")));
});

test("정책 validator는 유효하지만 다른 top-level issue URL을 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = loadMigrationFiles(realMigrationDir);
  policy.issue = "https://github.com/AquilaXk/easysubway/issues/9999";
  const reasons = validateMigrationPolicy(policy, files).map((v) => v.why);
  assert.ok(reasons.some((why) => why.includes("issue는 https://github.com/AquilaXk/easysubway/issues/2365이어야 함")));
});

test("현재 트리의 실제 migration policy·DDL은 strict validator에서 위반이 없다(회귀 없음)", () => {
  const policy = loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json"));
  const files = loadMigrationFiles(realMigrationDir);
  const violations = [
    ...validateMigrationPolicy(policy, files),
    ...evaluateMigrationSet(files, {
      baselineVersion: policy.baselineVersion,
      allowlist: policy.allowlist ?? [],
    }),
  ];
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
