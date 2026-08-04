import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
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
  ["V120__enable_row_level_security.sql", "ENABLE ROW LEVEL SECURITY"],
  ["V121__force_row_level_security.sql", "FORCE ROW LEVEL SECURITY"],
  ["V122__set_generated_always.sql", "SET GENERATED ALWAYS"],
  ["V123__add_column_unique.sql", "기존 테이블에 제약(ADD CONSTRAINT) 추가"],
  ["V124__add_exclude.sql", "기존 테이블에 제약(ADD CONSTRAINT) 추가"],
  ["V125__create_unique_nulls_distinct_index.sql", "기존 테이블에 UNIQUE INDEX 추가"],
  ["V126__create_unique_nulls_not_distinct_index.sql", "기존 테이블에 UNIQUE INDEX 추가"],
  ["V127__do_single_quote_hidden_drop.sql", "DROP TABLE"],
  ["V128__create_trigger_existing.sql", "CREATE TRIGGER ON 기존 테이블"],
  ["V129__create_or_replace_function.sql", "CREATE OR REPLACE 기존 객체"],
  ["V130__add_generated_identity.sql", "ADD GENERATED ALWAYS AS IDENTITY"],
  ["V131__function_single_quote_hidden_drop.sql", "DROP TABLE"],
  ["V132__revoke_execute.sql", "REVOKE 권한"],
  ["V133__alter_existing_table_trigger.sql", "ALTER TABLE TRIGGER 활성화/비활성화"],
  ["V134__alter_domain_add_constraint.sql", "ALTER DOMAIN ADD CONSTRAINT"],
  ["V135__alter_quoted_column_type.sql", "ALTER COLUMN TYPE"],
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
  "V208__no_force_row_level_security.sql",
  "V209__dollar_literal_data.sql",
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

test("newline으로 연결된 DO·함수·프로시저 single-quoted body는 함께 재귀 검사한다", () => {
  for (const sql of [
    "DO 'BEGIN '\n'DROP TABLE hidden; END';",
    "CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS 'BEGIN '\n'DROP TABLE hidden; END';",
    "CREATE PROCEDURE p() LANGUAGE plpgsql AS E'BEGIN \\\'quoted\\\'; '\nE'DROP TABLE hidden; END';",
  ]) {
    assert.ok(scanSqlForViolations(sql).includes("DROP TABLE"));
  }
  assert.deepEqual(scanSqlForViolations("DO 'BEGIN ' 'DROP TABLE hidden; END';"), []);
  assert.deepEqual(scanSqlForViolations("SELECT 'DROP TABLE hidden;'\n'DROP TABLE still_data;';"), []);
});

test("stripSqlNoise는 일반 literal을 제거하고 procedural dollar 본문만 인라인한다", () => {
  const cleaned = stripSqlNoise(
    "-- DROP TABLE in a comment\n/* DROP TABLE block */\nSELECT 'DROP TABLE in string';\nDO $$ DROP TABLE hidden; $$;",
  );
  // 주석·문자열 속 DROP TABLE은 사라지고, dollar 본문 속 DROP TABLE만 남는다.
  assert.equal((cleaned.match(/DROP TABLE/gi) ?? []).length, 1);
  assert.match(cleaned, /DROP TABLE hidden/);
});

test("CREATE OR REPLACE procedure/view는 기존 객체 교체로 fail closed 한다", () => {
  for (const sql of [
    "CREATE OR REPLACE PROCEDURE refresh_routes() LANGUAGE SQL AS $$ SELECT 1 $$;",
    "CREATE OR REPLACE VIEW active_routes AS SELECT * FROM routes;",
    "CREATE OR REPLACE RECURSIVE VIEW active_routes AS SELECT * FROM routes;",
  ]) {
    assert.ok(scanSqlForViolations(sql).includes("CREATE OR REPLACE 기존 객체"));
  }
});

test("같은 migration에서 만든 테이블의 trigger는 허용한다", () => {
  const findings = scanSqlForViolations(
    "CREATE TABLE snapshots(id bigint); " +
      "CREATE TRIGGER t BEFORE INSERT ON snapshots FOR EACH ROW EXECUTE FUNCTION guard();",
  );
  assert.deepEqual(findings, []);
});

test("미종결 quoted body는 fail closed 한다", () => {
  assert.ok(scanSqlForViolations("DO $$ BEGIN SELECT 1;").includes("미종결 quoted body"));
});

test("E 문자열의 backslash escape 뒤 파괴적 DDL은 숨기지 않는다", () => {
  const sql = "SELECT E'quoted \\' text'; DROP TABLE hidden;";
  assert.match(stripSqlNoise(sql), /DROP TABLE hidden/);
  assert.ok(scanSqlForViolations(sql).includes("DROP TABLE"));
});

test("위치 파라미터($1)는 dollar-quote로 오인하지 않는다", () => {
  const findings = scanSqlForViolations(
    "UPDATE service_notices SET title = $1 WHERE id = $2;",
  );
  assert.deepEqual(findings, []);
});

test("trigger의 EXECUTE FUNCTION/PROCEDURE는 동적 EXECUTE로 오탐하지 않는다", () => {
  const findings = scanSqlForViolations(
    "CREATE TABLE snapshots(id bigint); " +
      "CREATE TRIGGER t BEFORE INSERT ON snapshots FOR EACH ROW EXECUTE FUNCTION guard();",
  );
  assert.deepEqual(findings, []);
});

test("GRANT EXECUTE ON FUNCTION은 동적 EXECUTE로 오탐하지 않는다", () => {
  assert.deepEqual(
    scanSqlForViolations("GRANT EXECUTE ON FUNCTION guard_lineage() TO easysubway_app;"),
    [],
  );
});

test("REVOKE EXECUTE를 포함한 권한 회수는 fail closed 한다", () => {
  assert.ok(
    scanSqlForViolations("REVOKE EXECUTE ON ALL PROCEDURES IN SCHEMA public FROM readonly;").includes(
      "REVOKE 권한",
    ),
  );
});

test("procedural control syntax 뒤의 REVOKE도 fail closed 한다", () => {
  assert.ok(
    scanSqlForViolations(
      "DO $$ BEGIN FOR i IN 1..1 LOOP REVOKE SELECT ON journeys FROM readonly; END LOOP; END $$;",
    ).includes("REVOKE 권한"),
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

test("NULL 계열 default와 rule·policy·partition·restart contract는 fail closed 하고 인접 additive 문장은 통과한다", () => {
  for (const sql of [
    "ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT (NULL);",
    "ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT ((NULL));",
    "ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT CAST(NULL AS TEXT);",
    "ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT NULL::TEXT;",
    "ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT (NULL)::public.text;",
    "ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT ((NULL))::public.text;",
    "ALTER TABLE t ALTER COLUMN required_value SET DEFAULT (NULL);",
    "ALTER TABLE t ALTER required_value SET DEFAULT CAST(NULL AS TEXT);",
    "ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT (CAST(NULL AS TEXT));",
    "ALTER TABLE t ALTER COLUMN required_value SET DEFAULT (CAST(NULL AS TEXT));",
    "ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT ( NULL );",
    "ALTER TABLE t ALTER COLUMN required_value SET DEFAULT (CAST( NULL AS TEXT));",
  ]) {
    assert.ok(scanSqlForViolations(sql).some((finding) => finding.includes("DEFAULT NULL")));
  }
  for (const [sql, label] of [
    ["CREATE OR REPLACE RULE r AS ON INSERT TO t DO INSTEAD NOTHING;", "CREATE OR REPLACE RULE"],
    ["CREATE POLICY reader ON t FOR SELECT USING (true);", "CREATE/ALTER POLICY ON 기존 테이블"],
    ["ALTER POLICY reader ON t USING (true);", "CREATE/ALTER POLICY ON 기존 테이블"],
    ["ALTER TABLE t DETACH PARTITION t_2026;", "ALTER TABLE DETACH PARTITION"],
    ["ALTER SEQUENCE t_id_seq RESTART WITH 1;", "ALTER SEQUENCE RESTART"],
    ["ALTER SEQUENCE t_id_seq INCREMENT BY 5 RESTART WITH 1;", "ALTER SEQUENCE RESTART"],
    ["ALTER TABLE t ALTER COLUMN id RESTART WITH 1;", "ALTER TABLE ALTER COLUMN RESTART"],
  ]) {
    assert.ok(scanSqlForViolations(sql).includes(label), `${label} 누락`);
  }
  assert.deepEqual(scanSqlForViolations("ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT 'x';"), []);
  assert.deepEqual(scanSqlForViolations("ALTER TABLE t ADD COLUMN required_value TEXT NOT NULL DEFAULT (NULL IS NULL);"), []);
  assert.deepEqual(scanSqlForViolations("ALTER TABLE t ALTER COLUMN required_value SET DEFAULT (NULL IS NULL);"), []);
  assert.deepEqual(scanSqlForViolations("CREATE TABLE t (id BIGINT); CREATE POLICY reader ON t FOR SELECT USING (true);"), []);
  assert.deepEqual(scanSqlForViolations("CREATE TABLE t (id BIGINT); CREATE POLICY reader ON t FOR SELECT USING (true); ALTER POLICY reader ON t USING (true);"), []);
  assert.deepEqual(scanSqlForViolations("ALTER TABLE t ATTACH PARTITION t_2026 FOR VALUES FROM (1) TO (2);"), []);
  assert.deepEqual(scanSqlForViolations('ALTER SEQUENCE t_id_seq OWNED BY "RESTART";'), []);
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

test("quoted identifier의 destructive DDL도 fail closed 한다", () => {
  for (const [sql, label] of [
    ['ALTER TABLE "legacy-table" ALTER COLUMN "default-null" SET DEFAULT NULL;', "SET DEFAULT NULL"],
    ['ALTER TABLE "legacy""table" SET SCHEMA "archive-schema";', "SET SCHEMA"],
    ['ALTER TABLE "legacy-table" ALTER COLUMN "generated-always" SET GENERATED ALWAYS;', "SET GENERATED ALWAYS"],
    ['ALTER TABLE "legacy-table" ALTER COLUMN "identity-column" ADD GENERATED ALWAYS AS IDENTITY;', "ADD GENERATED ALWAYS AS IDENTITY"],
    ['ALTER TABLE "legacy-table" ADD COLUMN "unique-column" TEXT UNIQUE;', "기존 테이블에 제약(ADD CONSTRAINT) 추가"],
    ['CREATE UNIQUE INDEX "unique-index" ON "legacy""table" (id);', "기존 테이블에 UNIQUE INDEX 추가"],
  ]) {
    assert.ok(scanSqlForViolations(sql).includes(label), `${label}가 quoted identifier에서 누락됐다`);
  }
});

test("무조건적 CREATE TABLE 대상 제약 추가는 계속 면제되고 IF NOT EXISTS만 위반이 된다", () => {
  const unconditional =
    "CREATE TABLE t (id BIGINT);\nALTER TABLE t ADD CONSTRAINT t_unique UNIQUE (id);";
  const conditional =
    "CREATE TABLE IF NOT EXISTS t (id BIGINT);\nALTER TABLE t ADD CONSTRAINT t_unique UNIQUE (id);";
  assert.deepEqual(scanSqlForViolations(unconditional), []);
  assert.deepEqual(scanSqlForViolations(conditional), ["기존 테이블에 제약(ADD CONSTRAINT) 추가"]);
});

test("신규 테이블 면제는 선행 최상위 무조건 CREATE TABLE에만 적용된다", () => {
  for (const sql of [
    "ALTER TABLE t ADD CONSTRAINT t_unique UNIQUE (id); CREATE TABLE t (id BIGINT);",
    "DO $$ BEGIN CREATE TABLE t (id BIGINT); END $$; ALTER TABLE t ADD CONSTRAINT t_unique UNIQUE (id);",
    "DO $$ BEGIN PERFORM 1; CREATE TABLE t (id BIGINT); END $$; ALTER TABLE t ADD CONSTRAINT t_unique UNIQUE (id);",
    "CREATE TABLE IF NOT EXISTS t (id BIGINT); CREATE UNIQUE INDEX ux_t ON t (id);",
  ]) {
    assert.ok(scanSqlForViolations(sql).length > 0, `면제가 잘못 적용됐다: ${sql}`);
  }
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE t (id BIGINT); CREATE UNIQUE INDEX ux_t ON t (id);"),
    [],
  );
});

test("double-quoted identifier 내부 SQL 표식은 구문으로 재해석하지 않는다", () => {
  assert.deepEqual(
    scanSqlForViolations('CREATE TABLE "-- not comment; DROP TABLE" (id BIGINT);'),
    [],
  );
  assert.ok(
    scanSqlForViolations('ALTER TABLE "-- hidden" ADD COLUMN required_value TEXT NOT NULL;').includes(
      "DEFAULT 없는 NOT NULL 컬럼 추가",
    ),
  );
});

test("quoted column DROP은 탐지하고 quoted identifier 내부 DROP은 오탐하지 않는다", () => {
  assert.ok(
    scanSqlForViolations('ALTER TABLE t DROP "quoted-column";').includes("DROP COLUMN"),
  );
  assert.deepEqual(scanSqlForViolations('CREATE TABLE "DROP hidden" (id BIGINT);'), []);
});

test("기존 테이블의 ENABLE/DISABLE [REPLICA|ALWAYS] TRIGGER는 fail closed 하고 새 테이블은 면제한다", () => {
  for (const sql of [
    "ALTER TABLE t ENABLE TRIGGER audit;",
    "ALTER TABLE t DISABLE TRIGGER ALL;",
    "ALTER TABLE t ENABLE REPLICA TRIGGER audit;",
    "ALTER TABLE t ENABLE ALWAYS TRIGGER USER;",
  ]) {
    assert.ok(scanSqlForViolations(sql).includes("ALTER TABLE TRIGGER 활성화/비활성화"));
  }
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE t (id BIGINT); ALTER TABLE t DISABLE TRIGGER ALL;"),
    [],
  );
  assert.ok(
    scanSqlForViolations(
      'CREATE TABLE audit (id BIGINT); ALTER TABLE "audit-log" DISABLE TRIGGER ALL;',
    ).includes("ALTER TABLE TRIGGER 활성화/비활성화"),
  );
  assert.deepEqual(
    scanSqlForViolations('CREATE TABLE "audit-log" (id BIGINT); ALTER TABLE "audit-log" DISABLE TRIGGER ALL;'),
    [],
  );
  assert.ok(
    scanSqlForViolations('CREATE TABLE audit (id BIGINT); ALTER TABLE "Audit" DISABLE TRIGGER ALL;').includes(
      "ALTER TABLE TRIGGER 활성화/비활성화",
    ),
  );
  assert.deepEqual(
    scanSqlForViolations('CREATE TABLE "a""b" (id BIGINT); ALTER TABLE "a""b" DISABLE TRIGGER ALL;'),
    [],
  );
  assert.ok(
    scanSqlForViolations('CREATE TABLE ab (id BIGINT); ALTER TABLE "a""b" DISABLE TRIGGER ALL;').includes(
      "ALTER TABLE TRIGGER 활성화/비활성화",
    ),
  );
  assert.ok(
    scanSqlForViolations(
      'ALTER TABLE t ADD COLUMN marker BOOLEAN, DISABLE TRIGGER ALL, ALTER COLUMN "display-name" SET DATA TYPE TEXT;',
    ).includes("ALTER TABLE TRIGGER 활성화/비활성화"),
  );
  assert.ok(
    scanSqlForViolations(
      'ALTER TABLE t ADD COLUMN marker BOOLEAN, DISABLE TRIGGER ALL, ALTER COLUMN "display-name" SET DATA TYPE TEXT;',
    ).includes("ALTER COLUMN TYPE"),
  );
  assert.ok(
    scanSqlForViolations("ALTER TABLE t * DISABLE TRIGGER ALL;").includes(
      "ALTER TABLE TRIGGER 활성화/비활성화",
    ),
  );
  assert.ok(
    scanSqlForViolations("ALTER TABLE t * ALTER COLUMN c TYPE BIGINT;").includes(
      "ALTER COLUMN TYPE",
    ),
  );
  assert.ok(
    scanSqlForViolations(
      'ALTER TABLE t ADD COLUMN "CREATE TABLE audit" TEXT; ALTER TABLE audit DISABLE TRIGGER ALL;',
    ).includes("ALTER TABLE TRIGGER 활성화/비활성화"),
  );
});

test("ALTER DOMAIN ADD [CONSTRAINT] CHECK은 fail closed 한다", () => {
  for (const sql of [
    "ALTER DOMAIN postal_code ADD CHECK (VALUE ~ '^[0-9]+$');",
    "ALTER DOMAIN postal_code ADD CONSTRAINT postal_code_format CHECK (VALUE ~ '^[0-9]+$');",
    'ALTER DOMAIN "app schema"."postal-code" ADD CONSTRAINT "postal code format" CHECK (VALUE ~ \'^[0-9]+$\');',
  ]) {
    assert.ok(scanSqlForViolations(sql).includes("ALTER DOMAIN ADD CONSTRAINT"));
  }
  assert.deepEqual(scanSqlForViolations('CREATE TABLE "ALTER DOMAIN d ADD CHECK" (id BIGINT);'), []);
});

test("ALTER DOMAIN DROP CONSTRAINT·NOT NULL만 완화로 허용한다", () => {
  assert.deepEqual(scanSqlForViolations("ALTER DOMAIN postal_code DROP CONSTRAINT postal_code_format;"), []);
  assert.deepEqual(scanSqlForViolations("ALTER DOMAIN postal_code DROP NOT NULL;"), []);
  assert.ok(scanSqlForViolations("ALTER DOMAIN postal_code DROP DEFAULT;").length > 0);
  assert.ok(scanSqlForViolations("ALTER DOMAIN postal_code DROP NOT VALID;").length > 0);
});

test("특수 문자와 escaped quote가 있는 quoted column의 SET DATA TYPE을 탐지한다", () => {
  for (const sql of [
    'ALTER TABLE t ALTER COLUMN "display-name" SET DATA TYPE TEXT;',
    'ALTER TABLE t ALTER "a""quoted"" name" TYPE TEXT;',
  ]) {
    assert.ok(scanSqlForViolations(sql).includes("ALTER COLUMN TYPE"));
  }
});

test("double-quoted identifier 안의 새 규칙 키워드는 오탐하지 않는다", () => {
  for (const sql of [
    'ALTER TABLE t ADD COLUMN "REVOKE" TEXT;',
    'ALTER TABLE t ADD COLUMN "RENAME" TEXT;',
    'ALTER TABLE t ADD COLUMN "EXECUTE" TEXT;',
    'ALTER TABLE t ADD COLUMN "DISABLE TRIGGER" BOOLEAN;',
    'ALTER TABLE t ADD COLUMN "x, DISABLE TRIGGER" BOOLEAN;',
    'ALTER TABLE t ADD COLUMN "x, ALTER COLUMN y TYPE" BOOLEAN;',
    'CREATE TABLE t ("ALTER value TYPE" TEXT);',
  ]) {
    assert.deepEqual(scanSqlForViolations(sql), []);
  }
  assert.ok(
    scanSqlForViolations('ALTER TABLE routes ADD COLUMN "DEFAULT value" TEXT NOT NULL DEFAULT NULL;').includes(
      "DEFAULT NULL인 NOT NULL 컬럼 추가",
    ),
  );
  assert.ok(
    scanSqlForViolations('ALTER TABLE routes ADD COLUMN value TEXT NOT NULL DEFAULT CAST(NULL AS "CHECK");').includes(
      "DEFAULT NULL인 NOT NULL 컬럼 추가",
    ),
  );
});

test("기존 테이블의 plain CREATE RULE은 fail closed 하고 선행 신규 테이블 rule만 면제한다", () => {
  assert.ok(
    scanSqlForViolations("CREATE RULE route_insert AS ON INSERT TO routes DO INSTEAD NOTHING;").includes(
      "CREATE RULE ON 기존 테이블",
    ),
  );
  assert.deepEqual(
    scanSqlForViolations(
      "CREATE TABLE routes (id BIGINT); CREATE RULE route_insert AS ON INSERT TO routes DO INSTEAD NOTHING;",
    ),
    [],
  );
});

test("quoted comma가 있는 ADD COLUMN도 전체 절을 검사한다", () => {
  assert.ok(
    scanSqlForViolations('ALTER TABLE routes ADD COLUMN "required,value" TEXT NOT NULL;').includes(
      "DEFAULT 없는 NOT NULL 컬럼 추가",
    ),
  );
});

test("기존 테이블 inline ADD COLUMN CHECK·REFERENCES는 fail closed 하고 새 테이블은 면제한다", () => {
  for (const sql of [
    "ALTER TABLE routes ADD COLUMN valid BOOLEAN CHECK (valid);",
    "ALTER TABLE routes ADD COLUMN station_id BIGINT REFERENCES stations(id);",
  ]) {
    assert.ok(scanSqlForViolations(sql).includes("기존 테이블에 제약(ADD CONSTRAINT) 추가"));
  }
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE routes (id BIGINT); ALTER TABLE routes ADD COLUMN valid BOOLEAN CHECK (valid);"),
    [],
  );
  for (const sql of [
    'ALTER TABLE routes ADD COLUMN "CHECK" TEXT;',
    'ALTER TABLE routes ADD COLUMN value "REFERENCES";',
    'ALTER TABLE routes ADD COLUMN "PRIMARY KEY" TEXT;',
    'ALTER TABLE routes ADD COLUMN value "NOT NULL";',
  ]) {
    assert.deepEqual(scanSqlForViolations(sql), []);
  }
});

test("기존 테이블 RULE 상태 변경과 ALTER CONSTRAINT 강화는 fail closed 한다", () => {
  for (const sql of [
    "ALTER TABLE routes ENABLE RULE route_insert;",
    "ALTER TABLE routes DISABLE RULE route_insert;",
    "ALTER TABLE routes ENABLE REPLICA RULE route_insert;",
    "ALTER TABLE routes ENABLE ALWAYS RULE route_insert;",
  ]) {
    assert.ok(scanSqlForViolations(sql).includes("ALTER TABLE RULE 활성화/비활성화"));
  }
  for (const sql of [
    "ALTER TABLE routes ALTER CONSTRAINT routes_station_id_fkey NOT DEFERRABLE;",
    "ALTER TABLE routes ALTER CONSTRAINT routes_station_id_fkey INITIALLY IMMEDIATE;",
    "ALTER TABLE routes ALTER CONSTRAINT routes_station_id_fkey DEFERRABLE INITIALLY IMMEDIATE;",
  ]) {
    assert.ok(scanSqlForViolations(sql).includes("ALTER CONSTRAINT DEFERRABLE 강화"));
  }
  for (const sql of [
    "ALTER TABLE routes ALTER CONSTRAINT routes_station_id_fkey DEFERRABLE;",
    "ALTER TABLE routes ALTER CONSTRAINT routes_station_id_fkey INITIALLY DEFERRED;",
  ]) {
    assert.deepEqual(scanSqlForViolations(sql), []);
  }
});

test("qualified·quoted composite ALTER ATTRIBUTE TYPE은 fail closed 한다", () => {
  assert.ok(
    scanSqlForViolations('ALTER TYPE "app schema".route_point ALTER ATTRIBUTE "display-name" TYPE TEXT;').includes(
      "ALTER TYPE ALTER ATTRIBUTE TYPE",
    ),
  );
  assert.ok(
    scanSqlForViolations("ALTER TYPE route_point RENAME ATTRIBUTE x TO y, ALTER ATTRIBUTE y SET DATA TYPE TEXT;").includes(
      "ALTER TYPE ALTER ATTRIBUTE TYPE",
    ),
  );
  assert.ok(
    scanSqlForViolations("DO $$ BEGIN ALTER TYPE route_point ALTER ATTRIBUTE point SET DATA TYPE TEXT; END $$;").includes(
      "ALTER TYPE ALTER ATTRIBUTE TYPE",
    ),
  );
  assert.deepEqual(
    scanSqlForViolations('ALTER TYPE route_point ADD ATTRIBUTE "ALTER ATTRIBUTE x TYPE" TEXT;'),
    [],
  );
});

test("새 테이블 면제의 unqualified identity는 search_path 문맥을 넘지 않는다", () => {
  assert.ok(
    scanSqlForViolations("CREATE TABLE routes (id BIGINT); SET search_path TO archive; ALTER TABLE routes ADD COLUMN id BIGINT PRIMARY KEY;").includes(
      "PRIMARY KEY 컬럼 추가",
    ),
  );
  assert.ok(
    scanSqlForViolations("CREATE TABLE routes (id BIGINT); RESET search_path; ALTER TABLE routes ADD COLUMN id BIGINT PRIMARY KEY;").includes(
      "PRIMARY KEY 컬럼 추가",
    ),
  );
  for (const contextChange of ["SET SCHEMA 'archive'", "RESET ALL"]) {
    assert.ok(
      scanSqlForViolations(`CREATE TABLE routes (id BIGINT); ${contextChange}; ALTER TABLE routes ADD COLUMN id BIGINT PRIMARY KEY;`).includes(
        "PRIMARY KEY 컬럼 추가",
      ),
    );
  }
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE routes (id BIGINT); ALTER TABLE routes ADD COLUMN id BIGINT PRIMARY KEY;"),
    [],
  );
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE public.routes (id BIGINT); SET search_path TO archive; ALTER TABLE public.routes ADD COLUMN id BIGINT PRIMARY KEY;"),
    [],
  );
  for (const contextChange of [
    "SET search_path TO archive",
    "RESET search_path",
    "SET SCHEMA 'archive'",
    "RESET ALL",
  ]) {
    assert.ok(
      scanSqlForViolations(`CREATE TABLE routes (id BIGINT); DO $$ BEGIN ${contextChange}; END $$; ALTER TABLE routes ADD COLUMN id BIGINT PRIMARY KEY;`).includes(
        "PRIMARY KEY 컬럼 추가",
      ),
    );
  }
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE routes (id BIGINT); CREATE FUNCTION set_context() RETURNS void LANGUAGE plpgsql AS $$ BEGIN SET search_path TO archive; END $$; ALTER TABLE routes ADD COLUMN id BIGINT PRIMARY KEY;"),
    [],
  );
  assert.deepEqual(
    scanSqlForViolations("CREATE TABLE routes (id BIGINT); DO $$ BEGIN CREATE FUNCTION set_context() RETURNS void LANGUAGE plpgsql AS 'BEGIN SET search_path TO archive; END'; END $$; ALTER TABLE routes ADD COLUMN id BIGINT PRIMARY KEY;"),
    [],
  );
  assert.ok(
    scanSqlForViolations("CREATE TABLE routes (id BIGINT); DO $$ BEGIN CREATE FUNCTION set_context() RETURNS void LANGUAGE plpgsql AS 'BEGIN SET search_path TO archive; END'; SET search_path TO archive; END $$; ALTER TABLE routes ADD COLUMN id BIGINT PRIMARY KEY;").includes(
      "PRIMARY KEY 컬럼 추가",
    ),
  );
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
  assert.equal(parseVersion("V70.1__decimal_patch.sql"), 70);
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

test("정책 validator는 유효한 allowlist로 인식 불가 .sql을 승인하고 불일치는 fail closed 한다", () => {
  const unknown = { file: "legacy_manual_patch.sql", sql: "DROP TABLE legacy;" };
  const files = [...loadMigrationFiles(realMigrationDir), unknown];
  const makePolicy = () => {
    const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
    policy.allowlist.push({
      file: unknown.file,
      reason: "기존 수동 migration 기록",
      approval: "https://github.com/AquilaXk/easysubway/issues/2365",
      expiresAt: "2030-01-01T00:00:00.000Z",
      sha256: createHash("sha256").update(unknown.sql, "utf8").digest("hex"),
    });
    return policy;
  };

  const now = Date.parse("2029-01-01T00:00:00.000Z");
  assert.deepEqual(validateMigrationPolicy(makePolicy(), files, now), []);
  for (const [field, value, expected] of [
    ["reason", "", "allowlist entry는 file, reason"],
    ["approval", "not-a-url", "allowlist approval은 GitHub issue URL"],
    ["expiresAt", "2020-01-01T00:00:00.000Z", "allowlist expiresAt은 유효한 미래 ISO instant"],
    ["sha256", "0".repeat(64), "allowlist SHA-256 drift"],
  ]) {
    const policy = makePolicy();
    const entry = policy.allowlist.find((item) => item.file === unknown.file);
    entry[field] = value;
    const reasons = validateMigrationPolicy(policy, files, now).map((violation) => violation.why);
    assert.ok(reasons.some((reason) => reason.includes(expected)), `${field} 불일치가 통과했다`);
  }
});

function copy(value) {
  return JSON.parse(JSON.stringify(value));
}

test("정책 validator는 unknown field와 inventory/allowlist schema mismatch를 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = loadMigrationFiles(realMigrationDir);
  policy.unexpected = true;
  policy.inventory.find((entry) => entry.file === "V67__admin_user_roles_granted_by.sql").unexpected = true;
  policy.allowlist.find((entry) => entry.file === "V69__admin_error_events_permission.sql").unexpected = true;
  const v68Index = policy.inventory.findIndex((entry) => entry.file === "V68__create_error_events.sql");
  policy.inventory[v68Index] = "not-an-object";
  policy.schemaVersion = "2";
  policy.issue = "AquilaXk/easysubway#2365";
  policy.allowlist.find((entry) => entry.file === "V69__admin_error_events_permission.sql").file = "V69__not_the_actual_file.sql";
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
  policy.inventory.find((entry) => entry.file === "V67__admin_user_roles_granted_by.sql").sha256 = "0".repeat(64);
  policy.inventory.push({ file: "V70__unknown.sql", sha256: "1".repeat(64) });
  const reasons = validateMigrationPolicy(policy, files).map((v) => v.why);
  assert.ok(reasons.some((why) => why.includes("inventory 누락: V68__create_error_events.sql")));
  assert.ok(reasons.some((why) => why.includes("inventory SHA-256 drift: V67__admin_user_roles_granted_by.sql")));
  assert.ok(reasons.some((why) => why.includes("inventory unknown entry: V70__unknown.sql")));
});

test("정책 validator는 allowlist file/SHA/approval/expiry 불일치를 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = loadMigrationFiles(realMigrationDir);
  const entry = policy.allowlist.find((item) => item.file === "V69__admin_error_events_permission.sql");
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
  policy.allowlist.find((entry) => entry.file === "V69__admin_error_events_permission.sql").approval = "https://github.com/AquilaXk/easysubway/issues/9999";
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

test("정책 validator는 baseline과 scannedDirectory drift를 fail closed 한다", () => {
  const policy = copy(loadJson(path.join(repoRoot, "backend/quality/migration-ddl-gate.json")));
  const files = loadMigrationFiles(realMigrationDir);
  policy.baselineVersion = 65;
  policy.scannedDirectory = "backend/src/main/resources/db/migration/h2";
  const reasons = validateMigrationPolicy(policy, files).map((violation) => violation.why);
  assert.ok(reasons.some((reason) => reason.includes("baselineVersion은 66이어야 함")));
  assert.ok(
    reasons.some((reason) =>
      reason.includes("scannedDirectory는 backend/src/main/resources/db/migration/postgresql이어야 함"),
    ),
  );
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
