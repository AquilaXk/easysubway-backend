import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  classifyStatement,
  evaluateMigrationSet,
  loadMigrationFiles,
  splitSql,
  validatePolicy,
} from "./check-migration-ddl-compat.mjs";

const ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));

const safe = [
  "CREATE TABLE event_log (id BIGINT PRIMARY KEY, note TEXT CHECK (note <> 'DROP TABLE;'));",
  "CREATE INDEX idx_event_log_id ON event_log (id);",
  "ALTER TABLE admin_user_roles ADD COLUMN granted_by VARCHAR(40);",
];

const rejected = [
  "DROP TABLE event_log;",
  "ALTER TABLE event_log DROP COLUMN note;",
  "TRUNCATE event_log;",
  "ALTER TABLE event_log RENAME COLUMN note TO description;",
  "ALTER TABLE event_log ALTER COLUMN note TYPE VARCHAR(100);",
  "ALTER TABLE event_log ALTER COLUMN note SET NOT NULL;",
  "ALTER TABLE event_log ADD CONSTRAINT event_log_note_check CHECK (note <> '');",
  "ALTER TABLE event_log ADD COLUMN note TEXT NOT NULL;",
  "ALTER TABLE event_log ADD COLUMN note TEXT DEFAULT 'x';",
  "ALTER TABLE event_log ADD COLUMN note required_domain;",
  "ALTER TABLE event_log ADD COLUMN notes TEXT[];",
  "CREATE UNIQUE INDEX ux_event_log_id ON event_log (id);",
  "INSERT INTO event_log(id) VALUES (1);",
];

const sha = (text) => createHash("sha256").update(text).digest("hex");
const policy = (inventory, allowlist = []) => ({
  schemaVersion: 2,
  gateId: "backend-migration-ddl-compat",
  issue: "https://github.com/AquilaXk/easysubway/issues/2365",
  baselineVersion: 66,
  scannedDirectory: "backend/src/main/resources/db/migration/postgresql",
  excludedDirectories: ["backend/src/main/resources/db/migration/h2"],
  note: "strict test policy",
  allowlistEntryFormat: {
    file: "file",
    reason: "reason",
    approval: "approval",
    expiresAt: "expiry",
    sha256: "sha",
  },
  inventory: Object.entries(inventory).map(([file, sha256]) => ({ file, sha256 })),
  allowlist,
});

test("allows only additive SQL forms", () => {
  for (const sql of safe) assert.equal(classifyStatement(splitSql(sql)[0]).ok, true, sql);
  for (const sql of rejected) assert.equal(classifyStatement(splitSql(sql)[0]).ok, false, sql);
  assert.throws(() => splitSql("DO $$ BEGIN RAISE NOTICE 'safe'; END $$;"), /unsupported/);
});

test("does not split ordinary strings or comments and rejects malformed lexical input", () => {
  assert.equal(splitSql("CREATE TABLE x (note TEXT DEFAULT 'DROP TABLE;'); -- ; DROP\n").length, 1);
  assert.equal(splitSql("/* outer /* nested ; */ still */ CREATE TABLE x (id BIGINT);").length, 1);
  assert.equal(classifyStatement(splitSql('CREATE TABLE "DROP" ("ALTER" BIGINT);')[0]).ok, true);
  for (const sql of [
    "CREATE TABLE x (note TEXT DEFAULT 'unterminated);",
    "CREATE TABLE x (note TEXT DEFAULT 'DROP TABLE x; ''",
    "E'plain string'",
    "/* unclosed",
    "DO $$ nope;",
  ]) {
    assert.throws(() => splitSql(sql), /unsupported|unterminated/i);
  }
});

test("ends line comments on LF, CR, and CRLF so following statements are checked", () => {
  for (const ending of ["\n", "\r", "\r\n"]) {
    const statements = splitSql(`-- harmless${ending}DROP TABLE event_log;`);
    assert.equal(statements.length, 1);
    assert.equal(classifyStatement(statements[0]).category, "DROP");
  }
});

test("accepts only the PostgreSQL CREATE INDEX CONCURRENTLY order", () => {
  assert.equal(classifyStatement(splitSql("CREATE INDEX CONCURRENTLY idx_event_log_id ON event_log (id);")[0]).ok, true);
  assert.equal(classifyStatement(splitSql("CREATE CONCURRENTLY INDEX idx_event_log_id ON event_log (id);")[0]).ok, false);
});

test("assigns stable categories without claiming unrelated statements are destructive", () => {
  const expected = new Map([
    ["DROP TABLE x;", "DROP"],
    ["ALTER TABLE x DROP COLUMN a;", "DROP"],
    ["ALTER TABLE x RENAME COLUMN a TO b;", "RENAME"],
    ["TRUNCATE x;", "TRUNCATE"],
    ["ALTER TABLE x ALTER COLUMN a TYPE TEXT;", "ALTER COLUMN TYPE"],
    ["ALTER TABLE x ALTER COLUMN a SET NOT NULL;", "SET NOT NULL"],
  ]);
  for (const [sql, category] of expected) assert.equal(classifyStatement(splitSql(sql)[0]).category, category);
  for (const sql of ["DROP FUNCTION f;", "DROP TRIGGER t ON x;", "DROP POLICY p ON x;", "DROP SEQUENCE s;", "ALTER ROLE app_user LOGIN;", "REVOKE ALL ON x FROM y;", "ALTER TABLE x OWNER TO y;", "CREATE SEQUENCE x;", "CREATE FUNCTION f;", "CREATE TRIGGER x BEFORE INSERT ON y EXECUTE FUNCTION f();", "CREATE POLICY x ON y;"]) {
    assert.equal(classifyStatement(splitSql(sql)[0]).category, "unsupported / exact approval required");
  }
});

test("keeps non-additive type and multi-action tails unsupported", () => {
  for (const sql of [
    "ALTER TABLE x ADD COLUMN note TEXT NOT NULL;",
    "ALTER TABLE x ADD COLUMN note TEXT DEFAULT 'x';",
    "ALTER TABLE x ADD CONSTRAINT c CHECK (id > 0);",
  ]) assert.equal(classifyStatement(splitSql(sql)[0]).category, "existing-table constraint strengthening");
  for (const sql of [
    "ALTER TABLE x ADD COLUMN note required_domain;",
    "ALTER TABLE x ADD COLUMN notes TEXT[];",
    "ALTER TABLE x ADD COLUMN note TEXT COLLATE c;",
    "ALTER TABLE x ADD COLUMN note TEXT, ADD COLUMN other TEXT;",
  ]) assert.equal(classifyStatement(splitSql(sql)[0]).category, "unsupported / exact approval required");
});

test("checks full dotted Flyway versions above the integer baseline", () => {
  const file = { name: "V66.1__destructive.sql", version: [66, 1], content: "DROP TABLE x;", sha256: "a".repeat(64) };
  assert.deepEqual(evaluateMigrationSet([file], { baselineVersion: 66, allowlist: [] }), [{ file: file.name, findings: ["DROP"] }]);
});

test("fails closed for a multi-statement file and malformed policy", () => {
  const files = [{ path: "V67__safe.sql", name: "V67__safe.sql", version: 67, content: "CREATE TABLE x (id BIGINT); DROP TABLE x;", sha256: "a".repeat(64) }];
  assert.equal(evaluateMigrationSet(files, policy({ "V67__safe.sql": files[0].sha256 })).length, 1);
  const valid = policy({ "V67__safe.sql": files[0].sha256 });
  validatePolicy(valid, files, new Date("2029-01-01T00:00:00.000Z"));
  const invalidExpiry = policy({ "V67__safe.sql": files[0].sha256 }, [{
    file: "V67__safe.sql",
    reason: "approved",
    approval: "https://github.com/AquilaXk/easysubway/issues/1",
    expiresAt: "2030-99-31T00:00:00.000Z",
    sha256: files[0].sha256,
  }]);
  assert.throws(
    () => validatePolicy(invalidExpiry, files, new Date("2029-01-01T00:00:00.000Z")),
    /unsupported \/ allowlist mismatch/,
  );
  for (const broken of [
    { ...valid, extra: true },
    { ...valid, issue: "#2365" },
    { ...valid, scannedDirectory: "other" },
    { ...valid, inventory: { "V67__safe.sql": "x" } },
    { ...valid, allowlist: [{ file: "V67__safe.sql", reason: "ok", approval: "https://github.com/AquilaXk/easysubway/issues/1", expiresAt: "2020-01-01T00:00:00.000Z", sha256: files[0].sha256 }] },
  ]) assert.throws(() => validatePolicy(broken, files, new Date("2029-01-01T00:00:00.000Z")));
});

test("reads only canonical regular migration files", () => {
  const dir = mkdtempSync(join(tmpdir(), "backend-ddl-gate-"));
  try {
    writeFileSync(join(dir, "V67__safe.sql"), "CREATE TABLE x (id BIGINT);");
    assert.equal(loadMigrationFiles(dir).length, 1);
    writeFileSync(join(dir, "unsafe.sql"), "CREATE TABLE y (id BIGINT);");
    assert.throws(() => loadMigrationFiles(dir));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("fails closed for symlink, non-regular SQL, invalid UTF-8, and hashes raw bytes", () => {
  const dir = mkdtempSync(join(tmpdir(), "backend-ddl-gate-"));
  try {
    const raw = Buffer.from("CREATE TABLE x (id BIGINT);\n", "utf8");
    writeFileSync(join(dir, "V67__raw.sql"), raw);
    assert.equal(loadMigrationFiles(dir)[0].sha256, sha(raw));
    writeFileSync(join(dir, "V68__invalid.sql"), Buffer.from([0xff]));
    assert.throws(() => loadMigrationFiles(dir), /invalid UTF-8/);
    rmSync(join(dir, "V68__invalid.sql"));
    symlinkSync(join(dir, "V67__raw.sql"), join(dir, "V68__link.sql"));
    assert.throws(() => loadMigrationFiles(dir), /symlink/);
    rmSync(join(dir, "V68__link.sql"));
    mkdirSync(join(dir, "V68__directory.sql"));
    assert.throws(() => loadMigrationFiles(dir), /non-regular SQL/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("requires inventory coverage and exact SHA for repeatable and callback migrations", () => {
  const files = [
    { name: "V67__safe.sql", version: [67], content: "CREATE TABLE x (id BIGINT);", sha256: sha("CREATE TABLE x (id BIGINT);") },
    { name: "R__refresh.sql", version: null, content: "CREATE TABLE r (id BIGINT);", sha256: sha("CREATE TABLE r (id BIGINT);") },
    { name: "afterMigrate.sql", version: null, content: "CREATE TABLE c (id BIGINT);", sha256: sha("CREATE TABLE c (id BIGINT);") },
  ];
  const inventory = Object.fromEntries(files.map((file) => [file.name, file.sha256]));
  validatePolicy(policy(inventory), files, new Date("2029-01-01T00:00:00.000Z"));
  for (const broken of [
    policy({ "V67__safe.sql": files[0].sha256, "R__refresh.sql": files[1].sha256 }),
    policy({ ...inventory, "V68__unknown.sql": "a".repeat(64) }),
    policy({ ...inventory, "afterMigrate.sql": "0".repeat(64) }),
  ]) assert.throws(() => validatePolicy(broken, files, new Date("2029-01-01T00:00:00.000Z")));
});

test("current V67-V74 inventory validates and V74 is byte-pinned", () => {
  const files = loadMigrationFiles(resolve(ROOT, "backend/src/main/resources/db/migration/postgresql"));
  const current = JSON.parse(readFileSync(resolve(ROOT, "backend/quality/migration-ddl-gate.json"), "utf8"));
  validatePolicy(current, files, new Date("2029-01-01T00:00:00.000Z"));
  assert.equal(evaluateMigrationSet(files, current).length, 0);
  const v74 = files.find((file) => file.name.startsWith("V74__"));
  assert.ok(v74);
  const drifted = files.map((file) => file === v74 ? { ...file, sha256: sha(`${file.content} `) } : file);
  assert.throws(() => validatePolicy(current, drifted, new Date("2029-01-01T00:00:00.000Z")));
});
