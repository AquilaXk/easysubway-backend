import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

import { verifyFlywayMigrationInventory } from "./verify-flyway-migration-inventory.mjs";

const repositoryRoot = resolve(import.meta.dirname, "../..");

test("Flyway migration inventory는 committed lock과 PostgreSQL migration tree의 actual drift를 검증한다", () => {
  const input = {
    lockPath: join(repositoryRoot, "backend/flyway-migration-inventory.lock.json"),
    migrationRoot: join(repositoryRoot, "backend/src/main/resources/db/migration/postgresql"),
  };

  const first = verifyFlywayMigrationInventory(input);
  const second = verifyFlywayMigrationInventory(input);

  assert.ok(first.migrations.length > 0);
  assert.match(first.inventorySha256, /^[a-f0-9]{64}$/);
  assert.deepEqual(first, second);
});

test("Flyway migration inventory는 canonical numeric version 순서의 lock과 raw migration을 검증하고 stable digest를 반환한다", () => {
  const fixture = createFixture();
  try {
    const first = verify(fixture);
    const second = verify(fixture);

    assert.deepEqual(first.migrations.map((migration) => migration.version), ["1", "3", "10"]);
    assert.match(first.inventorySha256, /^[a-f0-9]{64}$/);
    assert.equal(first.inventorySha256, second.inventorySha256);
    assert.deepEqual(first, second);
  } finally {
    fixture.cleanup();
  }
});

test("Flyway migration inventory는 lock의 누락, 추가, rename, version collision과 noncanonical version을 fail closed한다", () => {
  const cases = [
    {
      name: "lock migration 누락",
      mutate(fixture) { fixture.lock.migrations.pop(); },
    },
    {
      name: "lock에 없는 migration 추가",
      mutate(fixture) { writeMigration(fixture.migrationRoot, "V99__unexpected.sql", "select 99;\n"); },
    },
    {
      name: "lock path rename",
      mutate(fixture) { fixture.lock.migrations[0].path = "V1__renamed.sql"; },
    },
    {
      name: "lock description drift",
      mutate(fixture) { fixture.lock.migrations[0].description = "renamed"; },
    },
    {
      name: "canonical version collision",
      mutate(fixture) { writeMigration(fixture.migrationRoot, "V1__other.sql", "select 'other';\n"); },
    },
    {
      name: "leading zero version",
      mutate(fixture) { writeMigration(fixture.migrationRoot, "V01__leading_zero.sql", "select 1;\n"); },
    },
  ];

  for (const { name, mutate } of cases) {
    const fixture = createFixture();
    try {
      mutate(fixture);
      writeLock(fixture);
      assert.throws(() => verify(fixture), undefined, name);
    } finally {
      fixture.cleanup();
    }
  }
});

test("Flyway migration inventory는 filename, raw SHA-256, regular file 및 symlink boundary를 검증한다", () => {
  const cases = [
    {
      name: "invalid filename",
      mutate(fixture) { writeMigration(fixture.migrationRoot, "R4__repeatable.sql", "select 4;\n"); },
    },
    {
      name: "raw bytes drift",
      mutate(fixture) { writeFileSync(join(fixture.migrationRoot, "V3__add_station.sql"), "select 3; -- changed\n"); },
    },
    {
      name: "migration symlink",
      mutate(fixture) {
        const path = join(fixture.migrationRoot, "V10__add_index.sql");
        rmSync(path);
        symlinkSync(join(fixture.migrationRoot, "V1__baseline.sql"), path);
      },
    },
    {
      name: "migration non-regular file",
      mutate(fixture) {
        const path = join(fixture.migrationRoot, "V10__add_index.sql");
        rmSync(path);
        mkdirSync(path);
      },
    },
  ];

  for (const { name, mutate } of cases) {
    const fixture = createFixture();
    try {
      mutate(fixture);
      assert.throws(() => verify(fixture), undefined, name);
    } finally {
      fixture.cleanup();
    }
  }
});

test("Flyway migration inventory는 canonical repository root와 repository-relative lock path를 강제한다", () => {
  const copiedRootFixture = createFixture();
  try {
    const copiedMigrationRoot = join(copiedRootFixture.directory, "copied-postgresql");
    mkdirSync(copiedMigrationRoot);
    writeMigration(copiedMigrationRoot, "V1__baseline.sql", "select 1;\n");
    assert.throws(() => verify({ ...copiedRootFixture, migrationRoot: copiedMigrationRoot }), /canonical repository location/);
  } finally {
    copiedRootFixture.cleanup();
  }

  const basenamePathFixture = createFixture();
  try {
    basenamePathFixture.lock.migrations[0].path = "V1__baseline.sql";
    writeLock(basenamePathFixture);
    assert.throws(() => verify(basenamePathFixture), /repository-relative/);
  } finally {
    basenamePathFixture.cleanup();
  }

  const nonRepositoryRelativeFixture = createFixture();
  try {
    nonRepositoryRelativeFixture.lock.migrations[0].path = "src/main/resources/db/migration/postgresql/V1__baseline.sql";
    writeLock(nonRepositoryRelativeFixture);
    assert.throws(() => verify(nonRepositoryRelativeFixture), /repository-relative/);
  } finally {
    nonRepositoryRelativeFixture.cleanup();
  }
});

test("Flyway migration inventory는 lock object의 closed field와 ordered entries를 강제한다", () => {
  const cases = [
    {
      name: "unknown lock field",
      mutate(fixture) { fixture.lock.unexpected = true; },
    },
    {
      name: "unknown migration field",
      mutate(fixture) { fixture.lock.migrations[0].unexpected = true; },
    },
    {
      name: "top-level field order",
      mutate(fixture) { fixture.lock = { migrations: fixture.lock.migrations, schemaVersion: 1 }; },
    },
    {
      name: "migration field order",
      mutate(fixture) {
        const migration = fixture.lock.migrations[0];
        fixture.lock.migrations[0] = { path: migration.path, version: migration.version, description: migration.description, sha256: migration.sha256 };
      },
    },
    {
      name: "numeric order",
      mutate(fixture) { fixture.lock.migrations.reverse(); },
    },
    {
      name: "duplicate lock path",
      mutate(fixture) { fixture.lock.migrations[1].path = fixture.lock.migrations[0].path; },
    },
    {
      name: "noncanonical digest",
      mutate(fixture) { fixture.lock.migrations[0].sha256 = fixture.lock.migrations[0].sha256.toUpperCase(); },
    },
  ];

  for (const { name, mutate } of cases) {
    const fixture = createFixture();
    try {
      mutate(fixture);
      writeLock(fixture);
      assert.throws(() => verify(fixture), undefined, name);
    } finally {
      fixture.cleanup();
    }
  }
});

function createFixture() {
  const directory = mkdtempSync(join(tmpdir(), "flyway-migration-inventory-"));
  const migrationRoot = join(directory, "backend/src/main/resources/db/migration/postgresql");
  mkdirSync(migrationRoot, { recursive: true });
  const migrations = [
    ["V1__baseline.sql", "select 1;\n"],
    ["V3__add_station.sql", "select 3;\n"],
    ["V10__add_index.sql", "select 10;\n"],
  ];
  for (const [name, contents] of migrations) writeMigration(migrationRoot, name, contents);
  const lock = {
    schemaVersion: 1,
    migrations: migrations.map(([path, contents]) => lockEntry(path, contents)),
  };
  const lockPath = join(directory, "backend/flyway-migration-inventory.lock.json");
  const fixture = {
    directory,
    lock,
    lockPath,
    migrationRoot,
    cleanup() { rmSync(directory, { recursive: true, force: true }); },
  };
  writeLock(fixture);
  return fixture;
}

function verify({ lockPath, migrationRoot }) {
  return verifyFlywayMigrationInventory({ lockPath, migrationRoot });
}

function writeMigration(root, name, contents) {
  writeFileSync(join(root, name), contents);
}

function lockEntry(path, contents) {
  const [, version, description] = /^V([1-9]\d*)__(.+)\.sql$/.exec(path);
  return { version, description, path: `backend/src/main/resources/db/migration/postgresql/${path}`, sha256: sha256(contents) };
}

function writeLock({ lockPath, lock }) {
  writeFileSync(lockPath, `${JSON.stringify(lock)}\n`);
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
