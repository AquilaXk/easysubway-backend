import { createHash } from "node:crypto";
import { lstatSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const MIGRATION_FILENAME = /^V([1-9]\d*)__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$/;
const DIGEST = /^[a-f0-9]{64}$/;

export function buildFlywayMigrationInventory({ migrationRoot }) {
  assertDirectory(migrationRoot, "migration root");

  const versions = new Set();
  const migrations = readdirSync(migrationRoot, { withFileTypes: true }).map((entry) => {
    if (entry.isSymbolicLink() || !entry.isFile()) throw new Error(`migration must be a regular file: ${entry.name}`);

    const match = MIGRATION_FILENAME.exec(entry.name);
    if (!match) throw new Error(`invalid Flyway migration filename: ${entry.name}`);

    const [, version, description] = match;
    const numericVersion = BigInt(version);
    if (versions.has(numericVersion)) throw new Error(`Flyway migration version collision: ${version}`);
    versions.add(numericVersion);

    const path = join(migrationRoot, entry.name);
    const stat = lstatSync(path);
    if (stat.isSymbolicLink() || !stat.isFile()) throw new Error(`migration must be a regular file: ${entry.name}`);
    return { version, description, path: entry.name, sha256: sha256(readFileSync(path)), numericVersion };
  });

  migrations.sort((left, right) => left.numericVersion < right.numericVersion ? -1 : left.numericVersion > right.numericVersion ? 1 : 0);
  return {
    schemaVersion: 1,
    migrations: migrations.map(({ version, description, path, sha256: digest }) => ({ version, description, path, sha256: digest })),
  };
}

export function verifyFlywayMigrationInventory({ lockPath, migrationRoot }) {
  assertRegularFile(lockPath, "migration inventory lock");
  const rawLock = readFileSync(lockPath, "utf8");
  const lock = parseLock(rawLock);
  const inventory = buildFlywayMigrationInventory({ migrationRoot });
  const canonicalLock = canonicalJson(lock);

  if (rawLock !== `${canonicalLock}\n`) throw new Error("migration inventory lock must be canonical JSON followed by one newline");
  if (canonicalLock !== canonicalJson(inventory)) throw new Error("migration inventory lock does not match the current migration tree");

  return { migrations: inventory.migrations, inventorySha256: sha256(canonicalLock) };
}

function parseLock(rawLock) {
  let lock;
  try {
    lock = JSON.parse(rawLock);
  } catch {
    throw new Error("migration inventory lock must be valid JSON");
  }

  assertObjectKeys(lock, ["schemaVersion", "migrations"], "migration inventory lock");
  if (lock.schemaVersion !== 1 || !Array.isArray(lock.migrations)) throw new Error("invalid migration inventory lock schema");

  let previousVersion;
  const paths = new Set();
  const versions = new Set();
  for (const entry of lock.migrations) {
    assertObjectKeys(entry, ["version", "description", "path", "sha256"], "migration inventory entry");
    if (typeof entry.version !== "string" || typeof entry.description !== "string" || typeof entry.path !== "string" || typeof entry.sha256 !== "string") {
      throw new Error("invalid migration inventory entry");
    }
    if (!DIGEST.test(entry.sha256)) throw new Error("migration inventory sha256 must be canonical lowercase hex");

    const match = MIGRATION_FILENAME.exec(entry.path);
    if (!match || match[1] !== entry.version || match[2] !== entry.description) throw new Error("migration inventory entry must match a canonical Flyway filename");
    const numericVersion = BigInt(entry.version);
    if (versions.has(numericVersion)) throw new Error(`Flyway migration version collision: ${entry.version}`);
    if (paths.has(entry.path)) throw new Error(`duplicate migration inventory path: ${entry.path}`);
    if (previousVersion !== undefined && numericVersion <= previousVersion) throw new Error("migration inventory entries must use ascending numeric versions");
    versions.add(numericVersion);
    paths.add(entry.path);
    previousVersion = numericVersion;
  }
  return lock;
}

function assertDirectory(path, label) {
  assertPath(path, label);
  const stat = lstatSync(path);
  if (stat.isSymbolicLink() || !stat.isDirectory()) throw new Error(`${label} must be a non-symlink directory`);
}

function assertRegularFile(path, label) {
  assertPath(path, label);
  const stat = lstatSync(path);
  if (stat.isSymbolicLink() || !stat.isFile()) throw new Error(`${label} must be a regular non-symlink file`);
}

function assertPath(path, label) {
  if (typeof path !== "string" || path.length === 0) throw new Error(`${label} path is required`);
}

function assertObjectKeys(value, keys, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || JSON.stringify(Object.keys(value)) !== JSON.stringify(keys)) {
    throw new Error(`${label} has unknown or unordered fields`);
  }
}

function canonicalJson(value) {
  return JSON.stringify(value);
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
