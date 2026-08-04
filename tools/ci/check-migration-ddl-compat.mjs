#!/usr/bin/env node
import { createHash } from "node:crypto";
import { lstatSync, readFileSync, readdirSync } from "node:fs";
import { relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const MIGRATIONS = "backend/src/main/resources/db/migration/postgresql";
const POLICY_FILE = "backend/quality/migration-ddl-gate.json";
const GITHUB_ISSUE = /^https:\/\/github\.com\/[^/]+\/[^/]+\/issues\/[1-9]\d*$/;
const SHA256 = /^[a-f0-9]{64}$/;
const unsupported = "unsupported / exact approval required";

const fail = (category) => {
  const error = new Error(category);
  error.category = category;
  throw error;
};
const hash = (text) => createHash("sha256").update(text).digest("hex");
const word = (token, value) => token?.kind === "word" && token.value.toUpperCase() === value;
const punctuation = (token, value) => token?.kind === "punctuation" && token.value === value;
const identifier = (token) => token?.kind === "word" || token?.kind === "quoted-identifier";
const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

export function splitSql(sql) {
  if (typeof sql !== "string" || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(sql)) fail("unsupported / invalid SQL input");
  const statements = [];
  let raw = "";
  let tokens = [];
  let i = 0;
  const emit = (kind, value) => tokens.push({ kind, value });
  const finish = () => {
    if (tokens.length) statements.push({ raw, tokens });
    raw = "";
    tokens = [];
  };
  while (i < sql.length) {
    const c = sql[i];
    if (/\s/.test(c)) { raw += c; i++; continue; }
    if (sql.startsWith("--", i)) {
      const end = sql.slice(i).search(/[\r\n]/);
      const lineEnd = end < 0 ? -1 : i + end;
      raw += lineEnd < 0 ? sql.slice(i) : sql.slice(i, lineEnd + 1);
      i = lineEnd < 0 ? sql.length : lineEnd + 1;
      continue;
    }
    if (sql.startsWith("/*", i)) {
      let depth = 1;
      const start = i;
      i += 2;
      while (i < sql.length && depth) {
        if (sql.startsWith("/*", i)) { depth++; i += 2; }
        else if (sql.startsWith("*/", i)) { depth--; i += 2; }
        else i++;
      }
      if (depth) fail("unsupported / unterminated block comment");
      raw += sql.slice(start, i);
      continue;
    }
    if (c === "'" || c === '"') {
      const quote = c;
      const start = i++;
      while (i < sql.length) {
        if (sql[i] === quote && sql[i + 1] === quote) { i += 2; continue; }
        if (sql[i] === quote) { i++; break; }
        i++;
      }
      if (sql[i - 1] !== quote) fail(`unsupported / unterminated ${quote === "'" ? "string" : "quoted identifier"}`);
      const value = sql.slice(start, i);
      raw += value;
      emit(quote === "'" ? "string" : "quoted-identifier", value);
      continue;
    }
    if (c === "$") {
      const marker = /^\$[A-Za-z_][A-Za-z0-9_]*\$|^\$\$/.exec(sql.slice(i));
      if (marker) {
        const end = sql.indexOf(marker[0], i + marker[0].length);
        if (end < 0) fail("unsupported / unterminated dollar quote");
        fail("unsupported / exact approval required");
      }
    }
    if (c === ";") { raw += c; i++; finish(); continue; }
    if (/[A-Za-z_]/.test(c)) {
      const match = /^[A-Za-z_][A-Za-z0-9_$]*/.exec(sql.slice(i))[0];
      raw += match;
      emit("word", match);
      i += match.length;
      continue;
    }
    if (/\d/.test(c)) {
      const match = /^\d+(?:\.\d+)?/.exec(sql.slice(i))[0];
      raw += match;
      emit("number", match);
      i += match.length;
      continue;
    }
    raw += c;
    emit("punctuation", c);
    i++;
  }
  finish();
  return statements;
}

function balanced(tokens, start) {
  if (!punctuation(tokens[start], "(")) return -1;
  let depth = 0;
  for (let i = start; i < tokens.length; i++) {
    if (punctuation(tokens[i], "(")) depth++;
    if (punctuation(tokens[i], ")") && --depth === 0) return i;
  }
  return -1;
}
function qualified(tokens, index) {
  if (!identifier(tokens[index])) return -1;
  index++;
  while (punctuation(tokens[index], ".") && identifier(tokens[index + 1])) index += 2;
  return index;
}
function closedType(tokens, index) {
  const type = tokens[index]?.value?.toUpperCase();
  if (["SMALLINT", "INTEGER", "BIGINT", "BOOLEAN", "TEXT", "UUID", "DATE", "BYTEA", "JSONB", "TIMESTAMPTZ"].includes(type)) return index + 1;
  if (type === "TIMESTAMP") {
    if (word(tokens[index + 1], "WITH") && word(tokens[index + 2], "TIME") && word(tokens[index + 3], "ZONE")) return index + 4;
    return index + 1;
  }
  if (!["VARCHAR", "CHAR", "NUMERIC"].includes(type) || !punctuation(tokens[index + 1], "(")) return -1;
  const end = balanced(tokens, index + 1);
  if (end < 0 || end !== tokens.length - 1) return -1;
  const values = tokens.slice(index + 2, end);
  const numeric = values.length === 1 && values[0].kind === "number" ||
    type === "NUMERIC" && values.length === 3 && values[0].kind === "number" && punctuation(values[1], ",") && values[2].kind === "number";
  return numeric ? end + 1 : -1;
}

export function classifyStatement(statement) {
  try {
    const tokens = statement?.tokens ?? [];
    if (!tokens.length) return { ok: true, category: "empty" };
    if (word(tokens[0], "DROP")) return { ok: false, category: word(tokens[1], "TABLE") ? "DROP" : unsupported };
    if (word(tokens[0], "TRUNCATE")) return { ok: false, category: "TRUNCATE" };
    if (word(tokens[0], "ALTER") && word(tokens[1], "TABLE")) {
      let i = qualified(tokens, 2);
      if (i < 0) return { ok: false, category: unsupported };
      if (word(tokens[i], "DROP")) return { ok: false, category: "DROP" };
      if (word(tokens[i], "RENAME")) return { ok: false, category: "RENAME" };
      if (word(tokens[i], "ALTER") && word(tokens[i + 1], "COLUMN")) {
        if (word(tokens[i + 3], "TYPE")) return { ok: false, category: "ALTER COLUMN TYPE" };
        if (word(tokens[i + 3], "SET") && word(tokens[i + 4], "NOT") && word(tokens[i + 5], "NULL")) return { ok: false, category: "SET NOT NULL" };
        return { ok: false, category: unsupported };
      }
      if (word(tokens[i], "ADD")) {
        i += word(tokens[i + 1], "COLUMN") ? 2 : 1;
        if (!identifier(tokens[i])) return { ok: false, category: "existing-table constraint strengthening" };
        const end = closedType(tokens, i + 1);
        return { ok: end === tokens.length, category: end === tokens.length ? "ALTER TABLE ADD COLUMN" : "existing-table constraint strengthening" };
      }
      return { ok: false, category: unsupported };
    }
    if (word(tokens[0], "CREATE") && word(tokens[1], "TABLE")) {
      let i = 2;
      if (word(tokens[i], "IF") && word(tokens[i + 1], "NOT") && word(tokens[i + 2], "EXISTS")) i += 3;
      i = qualified(tokens, i);
      const end = i < 0 ? -1 : balanced(tokens, i);
      return { ok: end === tokens.length - 1, category: end === tokens.length - 1 ? "CREATE TABLE" : unsupported };
    }
    if (word(tokens[0], "CREATE")) {
      if (word(tokens[1], "UNIQUE")) return { ok: false, category: "existing-table constraint strengthening" };
      if (!word(tokens[1], "INDEX")) return { ok: false, category: unsupported };
      let i = word(tokens[2], "CONCURRENTLY") ? 3 : 2;
      i = qualified(tokens, i);
      if (i < 0 || !word(tokens[i], "ON")) return { ok: false, category: unsupported };
      i = qualified(tokens, i + 1);
      const end = i < 0 ? -1 : balanced(tokens, i);
      return { ok: end === tokens.length - 1, category: end === tokens.length - 1 ? "CREATE INDEX" : unsupported };
    }
    return { ok: false, category: unsupported };
  } catch {
    return { ok: false, category: unsupported };
  }
}

function normalizedVersion(parts) {
  const normalized = [...parts];
  while (normalized.length > 1 && normalized.at(-1) === 0) normalized.pop();
  return normalized.join(".");
}
function afterBaseline(version, baseline) {
  const parts = Array.isArray(version) ? version : [version];
  const base = Array.isArray(baseline) ? baseline : [baseline];
  for (let index = 0; index < Math.max(parts.length, base.length); index++) {
    const difference = (parts[index] ?? 0) - (base[index] ?? 0);
    if (difference) return difference > 0;
  }
  return false;
}
function parseName(name) {
  const versioned = /^V(\d+(?:[._]\d+)*)__([^/]+)\.sql$/.exec(name);
  if (versioned) return { version: versioned[1].split(/[._]/).map(Number), canonical: true };
  if (/^R__[^/]+\.sql$/.test(name) || /^(?:before|after)[A-Za-z0-9_]*\.sql$/.test(name)) return { version: null, canonical: true };
  return { version: null, canonical: false };
}
export function loadMigrationFiles(dir) {
  const root = resolve(dir);
  const files = [];
  const walk = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = resolve(current, entry.name);
      const stat = lstatSync(full);
      if (stat.isSymbolicLink()) continue;
      if (stat.isDirectory()) { walk(full); continue; }
      if (!stat.isFile() || !entry.name.endsWith(".sql")) continue;
      const name = relative(root, full).replaceAll("\\", "/");
      const parsed = parseName(name);
      if (!parsed.canonical) fail(`unsupported / migration filename: ${name}`);
      files.push({ path: full, file: name, name, version: parsed.version, content: readFileSync(full, "utf8"), sha256: hash(readFileSync(full, "utf8")) });
    }
  };
  walk(root);
  const versions = new Set();
  for (const file of files) if (file.version !== null) {
    const version = normalizedVersion(file.version);
    if (versions.has(version)) fail(`unsupported / duplicate migration version: ${version}`);
    versions.add(version);
  }
  return files.sort((a, b) => a.name.localeCompare(b.name));
}

function assertKeys(value, keys, name) {
  if (!isObject(value) || Object.keys(value).length !== keys.length || Object.keys(value).some((key) => !keys.includes(key))) fail(`unsupported / invalid policy ${name}`);
}
export function validatePolicy(policy, files, now = new Date()) {
  assertKeys(policy, ["schemaVersion", "gateId", "issue", "baselineVersion", "scannedDirectory", "excludedDirectories", "note", "allowlistEntryFormat", "inventory", "allowlist"], "top-level fields");
  if (policy.schemaVersion !== 2 || policy.gateId !== "backend-migration-ddl-compat" || policy.issue !== "https://github.com/AquilaXk/easysubway/issues/2365" || policy.baselineVersion !== 66 || policy.scannedDirectory !== MIGRATIONS || !Array.isArray(policy.excludedDirectories) || policy.excludedDirectories.length !== 1 || policy.excludedDirectories[0] !== "backend/src/main/resources/db/migration/h2" || typeof policy.note !== "string" || !policy.note.trim()) fail("unsupported / invalid policy identity");
  assertKeys(policy.allowlistEntryFormat, ["file", "reason", "approval", "expiresAt", "sha256"], "allowlist format");
  if (Object.values(policy.allowlistEntryFormat).some((value) => typeof value !== "string" || !value.trim()) || !Array.isArray(policy.inventory) || !Array.isArray(policy.allowlist)) fail("unsupported / invalid policy types");
  const above = files.filter((file) => file.version !== null && afterBaseline(file.version, policy.baselineVersion));
  const actual = new Map(files.map((file) => [file.name, file]));
  const inventory = new Set();
  for (const entry of policy.inventory) {
    assertKeys(entry, ["file", "sha256"], "inventory entry");
    if (typeof entry.file !== "string" || !SHA256.test(entry.sha256) || inventory.has(entry.file) || !actual.get(entry.file) || actual.get(entry.file).sha256 !== entry.sha256) fail("unsupported / inventory mismatch");
    inventory.add(entry.file);
  }
  if (above.some((file) => !inventory.has(file.name)) || inventory.size !== above.length) fail("unsupported / inventory incomplete");
  const allowlist = new Set();
  for (const entry of policy.allowlist) {
    assertKeys(entry, ["file", "reason", "approval", "expiresAt", "sha256"], "allowlist entry");
    const expiry = new Date(entry.expiresAt);
    if (typeof entry.file !== "string" || typeof entry.reason !== "string" || !entry.reason.trim() || !GITHUB_ISSUE.test(entry.approval) || !SHA256.test(entry.sha256) || allowlist.has(entry.file) || !actual.get(entry.file) || actual.get(entry.file).sha256 !== entry.sha256 || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(entry.expiresAt) || Number.isNaN(expiry) || expiry.toISOString() !== entry.expiresAt || expiry <= now) fail("unsupported / allowlist mismatch");
    allowlist.add(entry.file);
  }
  const v69 = actual.get("V69__admin_error_events_permission.sql");
  if (v69) {
    const entry = policy.allowlist.find((item) => item.file === v69.name);
    if (!entry || entry.approval !== "https://github.com/AquilaXk/easysubway/issues/2433") fail("unsupported / V69 allowlist mismatch");
  }
}

export function evaluateMigrationSet(files, policy) {
  const allowed = new Map((policy.allowlist ?? []).map((entry) => [entry.file, entry.sha256]));
  const violations = [];
  for (const file of files) {
    if (file.version !== null && !afterBaseline(file.version, policy.baselineVersion)) continue;
    if (allowed.get(file.name) === file.sha256) continue;
    try {
      const findings = splitSql(file.content).map(classifyStatement).filter((result) => !result.ok).map((result) => result.category);
      if (findings.length) violations.push({ file: file.name, findings });
    } catch (error) { violations.push({ file: file.name, findings: [error.category ?? unsupported] }); }
  }
  return violations;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const policy = JSON.parse(readFileSync(resolve(ROOT, POLICY_FILE), "utf8"));
    const files = loadMigrationFiles(resolve(ROOT, MIGRATIONS));
    validatePolicy(policy, files, new Date());
    const violations = evaluateMigrationSet(files, policy);
    if (violations.length) throw new Error(JSON.stringify(violations));
    console.log("migration DDL compatibility: passed");
  } catch (error) {
    console.error(`migration DDL compatibility: ${error.message}`);
    process.exitCode = 1;
  }
}
