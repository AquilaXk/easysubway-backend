import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

import {
  classifyStatement,
  evaluateMigrationSet,
  loadMigrationFiles,
  splitSql,
  validatePolicy,
} from "./check-migration-ddl-compat.mjs";

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
  "DO $$ BEGIN RAISE NOTICE 'safe'; END $$;",
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
  for (const sql of rejected) {
    try {
      assert.equal(classifyStatement(splitSql(sql)[0]).ok, false, sql);
    } catch (error) {
      assert.match(error.message, /unsupported/);
    }
  }
});

test("does not split ordinary strings or comments and rejects malformed lexical input", () => {
  assert.equal(splitSql("CREATE TABLE x (note TEXT DEFAULT 'DROP TABLE;'); -- ; DROP\n").length, 1);
  assert.equal(splitSql("/* outer /* nested ; */ still */ CREATE TABLE x (id BIGINT);").length, 1);
  assert.equal(classifyStatement(splitSql('CREATE TABLE "DROP" ("ALTER" BIGINT);')[0]).ok, true);
  for (const sql of ["CREATE TABLE x (note TEXT DEFAULT 'unterminated);", "/* unclosed", "DO $$ nope;"]) {
    assert.throws(() => splitSql(sql), /unsupported|unterminated/i);
  }
});

test("assigns stable categories without claiming unrelated statements are destructive", () => {
  const expected = new Map([
    ["DROP TABLE x;", "DROP"],
    ["ALTER TABLE x RENAME COLUMN a TO b;", "RENAME"],
    ["TRUNCATE x;", "TRUNCATE"],
    ["ALTER TABLE x ALTER COLUMN a TYPE TEXT;", "ALTER COLUMN TYPE"],
    ["ALTER TABLE x ALTER COLUMN a SET NOT NULL;", "SET NOT NULL"],
  ]);
  for (const [sql, category] of expected) assert.equal(classifyStatement(splitSql(sql)[0]).category, category);
  for (const sql of ["ALTER ROLE app_user LOGIN;", "REVOKE ALL ON x FROM y;", "ALTER TABLE x OWNER TO y;", "CREATE SEQUENCE x;", "CREATE TRIGGER x BEFORE INSERT ON y EXECUTE FUNCTION f();", "CREATE POLICY x ON y;"]) {
    assert.equal(classifyStatement(splitSql(sql)[0]).category, "unsupported / exact approval required");
  }
});

test("fails closed for a multi-statement file and malformed policy", () => {
  const files = [{ path: "V67__safe.sql", name: "V67__safe.sql", version: 67, content: "CREATE TABLE x (id BIGINT); DROP TABLE x;", sha256: "a".repeat(64) }];
  assert.equal(evaluateMigrationSet(files, policy({ "V67__safe.sql": files[0].sha256 })).length, 1);
  const valid = policy({ "V67__safe.sql": files[0].sha256 });
  validatePolicy(valid, files, new Date("2029-01-01T00:00:00.000Z"));
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

test("current V67-V69 inventory validates and V69 is byte-pinned", () => {
  const files = loadMigrationFiles("backend/src/main/resources/db/migration/postgresql");
  const current = JSON.parse(readFileSync("backend/quality/migration-ddl-gate.json", "utf8"));
  validatePolicy(current, files, new Date("2029-01-01T00:00:00.000Z"));
  assert.equal(evaluateMigrationSet(files, current).length, 0);
  const v69 = files.find((file) => file.name.startsWith("V69__"));
  assert.ok(v69);
  const drifted = files.map((file) => file === v69 ? { ...file, sha256: sha(`${file.content} `) } : file);
  assert.throws(() => validatePolicy(current, drifted, new Date("2029-01-01T00:00:00.000Z")));
});
