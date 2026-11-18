#!/usr/bin/env node
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { closeSync, constants, fstatSync, lstatSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { basename, dirname, join, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const backendBuild = resolve(repositoryRoot, "backend/build");
const secureMover = resolve(import.meta.dirname, "SecureDirectoryTreeMover.java");
const optionNames = new Set(["--lock", "--input", "--output"]);
const digestPattern = /^[a-f0-9]{64}$/;
const shaPattern = /^[a-f0-9]{40}$/;

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();

function main() {
  try {
    const { lock, input, output } = parseArguments(process.argv.slice(2));
    const lockBytes = readRegularFile(lock, "lock");
    const inputBytes = readRegularFile(input, "input");
    const lockDocument = parseJson(lockBytes, "lock");
    validateLock(lockDocument);
    if (sha256(inputBytes) !== lockDocument.payload.sha256) throw new Error("payload sha256 mismatch");
    const bundle = parseJson(inputBytes, "bundle");
    const resources = validateBundle(bundle, lockDocument);
    stageAtomically(output, resources, { payloadSha256: lockDocument.payload.sha256 });
  } catch (error) {
    process.stderr.write(`stage-journey-contracts: ${error instanceof Error ? error.message : "invalid input"}\n`);
    process.exitCode = 1;
  }
}

function parseArguments(values) {
  if (values.length !== 6) throw new Error("lock, input, and output are required");
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const option = values[index];
    const value = values[index + 1];
    if (!optionNames.has(option)) throw new Error("unknown option");
    if (!value || value.startsWith("--")) throw new Error("missing option value");
    const key = option.slice(2);
    if (Object.hasOwn(parsed, key)) throw new Error("duplicate option");
    parsed[key] = value;
  }
  if (Object.keys(parsed).length !== optionNames.size) throw new Error("lock, input, and output are required");
  return parsed;
}

function validateLock(lock) {
  assertExactKeys(lock, ["schemaVersion", "component", "bundleVersion", "producer", "artifact", "payload", "publicationReceiptSha256", "resources"], "lock");
  assertExactKeys(lock.producer, ["repository", "gitSha"], "producer");
  assertExactKeys(lock.artifact, ["repository", "manifestDigest", "artifactType"], "artifact");
  assertExactKeys(lock.payload, ["fileName", "mediaType", "sha256"], "payload");
  if (lock.schemaVersion !== 2 || typeof lock.component !== "string" || !lock.component || typeof lock.bundleVersion !== "string" || !lock.bundleVersion || typeof lock.producer.repository !== "string" || !lock.producer.repository || !shaPattern.test(lock.producer.gitSha) || typeof lock.artifact.repository !== "string" || !lock.artifact.repository || !/^sha256:[a-f0-9]{64}$/.test(lock.artifact.manifestDigest) || typeof lock.artifact.artifactType !== "string" || !lock.artifact.artifactType || !isSafeRelativePath(lock.payload.fileName) || typeof lock.payload.mediaType !== "string" || !lock.payload.mediaType || !digestPattern.test(lock.payload.sha256) || !digestPattern.test(lock.publicationReceiptSha256) || !Array.isArray(lock.resources) || lock.resources.length === 0) {
    throw new Error("invalid lock");
  }
  let previousPath = "";
  const ids = new Set();
  const paths = new Set();
  for (const resource of lock.resources) {
    assertExactKeys(resource, ["id", "path", "owner", "mediaType", "sha256"], "resource lock");
    if (typeof resource.id !== "string" || !resource.id || typeof resource.owner !== "string" || !resource.owner || typeof resource.mediaType !== "string" || !resource.mediaType || !isSafeRelativePath(resource.path) || resource.path === ".stage-complete" || !digestPattern.test(resource.sha256) || ids.has(resource.id) || paths.has(resource.path) || (previousPath && previousPath.localeCompare(resource.path) >= 0)) {
      throw new Error("invalid resource lock");
    }
    ids.add(resource.id);
    paths.add(resource.path);
    previousPath = resource.path;
  }
}

function validateBundle(bundle, lock) {
  assertExactKeys(bundle, ["schemaVersion", "bundleVersion", "component", "producerRepository", "producerSha", "resources"], "bundle");
  if (bundle.schemaVersion !== 2 || bundle.bundleVersion !== lock.bundleVersion || bundle.component !== lock.component || bundle.producerRepository !== lock.producer.repository || bundle.producerSha !== lock.producer.gitSha || !Array.isArray(bundle.resources) || bundle.resources.length !== lock.resources.length) {
    throw new Error("bundle identity mismatch");
  }
  return bundle.resources.map((resource, index) => {
    const locked = lock.resources[index];
    assertExactKeys(resource, ["id", "path", "owner", "mediaType", "sha256", "contentBase64"], "bundle resource");
    if (resource.id !== locked.id || resource.path !== locked.path || resource.owner !== locked.owner || resource.mediaType !== locked.mediaType || resource.sha256 !== locked.sha256 || typeof resource.contentBase64 !== "string") {
      throw new Error("bundle resource identity mismatch");
    }
    const bytes = decodeCanonicalBase64(resource.contentBase64);
    if (sha256(bytes) !== resource.sha256) throw new Error("resource sha256 mismatch");
    return { path: resource.path, bytes };
  });
}

function decodeCanonicalBase64(value) {
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) throw new Error("invalid resource Base64");
  const decoded = Buffer.from(value, "base64");
  if (decoded.toString("base64") !== value) throw new Error("invalid resource Base64");
  return decoded;
}

function assertOutput(outputPath) {
  const output = resolve(outputPath);
  const outputRelative = relative(backendBuild, output);
  if (!outputRelative || outputRelative.startsWith("..") || outputRelative.split("/").includes("..")) throw new Error("output must be below backend/build");
  assertDirectory(backendBuild, "backend/build");
  const parent = dirname(output);
  const parentRelative = relative(backendBuild, parent);
  let current = backendBuild;
  for (const segment of parentRelative ? parentRelative.split("/") : []) {
    current = resolve(current, segment);
    const metadata = lstatSync(current, { throwIfNoEntry: false });
    if (!metadata) throw new Error("output parent must already exist");
    if (metadata.isSymbolicLink() || !metadata.isDirectory()) throw new Error("output must not have a symlink or non-directory ancestor");
  }
  if (lstatSync(output, { throwIfNoEntry: false })) throw new Error("final output must be absent");
  return { output, parent, name: basename(output) };
}

export function stageAtomically(outputPath, resources, { payloadSha256, beforePublish, forceUnsupportedSecureDirectoryStream = false } = {}) {
  if (!digestPattern.test(payloadSha256)) throw new Error("invalid completion payload sha256");
  const target = assertOutput(outputPath);
  let temporary;
  try {
    temporary = mkdtempSync(join(backendBuild, ".stage-journey-contracts-"));
    for (const resource of resources) {
      const destination = resolve(temporary, resource.path);
      if (!isBelow(temporary, destination)) throw new Error("unsafe resource path");
      mkdirSync(dirname(destination), { recursive: true });
      writeFileSync(destination, resource.bytes, { flag: "wx", mode: 0o600 });
    }
    const completion = join(temporary, ".stage-complete");
    writeFileSync(completion, `${payloadSha256}\n`, { flag: "wx", mode: 0o600 });
    beforePublish?.();
    moveTemporaryWithSecureDirectories(temporary, target, forceUnsupportedSecureDirectoryStream);
  } finally {
    if (temporary) rmSync(temporary, { recursive: true, force: true });
  }
}

function moveTemporaryWithSecureDirectories(temporary, target, forceUnsupportedSecureDirectoryStream) {
  const arguments_ = [secureMover, repositoryRoot, basename(temporary), relative(backendBuild, target.output)];
  if (forceUnsupportedSecureDirectoryStream) arguments_.push("--test-force-unsupported");
  try {
    execFileSync("java", arguments_, { encoding: "utf8", stdio: "pipe" });
  } catch (error) {
    const detail = error && typeof error === "object" ? error.stderr?.toString().trim() : "";
    throw new Error(detail || "secure directory move failed");
  }
}

export function readRegularFile(path, label, { afterOpen } = {}) {
  let descriptor;
  try {
    descriptor = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch {
    throw new Error(`${label} must be a regular non-symlink file`);
  }
  try {
    if (!fstatSync(descriptor).isFile()) throw new Error(`${label} must be a regular non-symlink file`);
    afterOpen?.();
    return readFileSync(descriptor);
  } finally {
    closeSync(descriptor);
  }
}

function assertDirectory(path, label) {
  const metadata = lstatSync(path, { throwIfNoEntry: false });
  if (!metadata || metadata.isSymbolicLink() || !metadata.isDirectory()) throw new Error(`${label} must be a non-symlink directory`);
}

function isSafeRelativePath(path) {
  return typeof path === "string" && path.length > 0 && !path.startsWith("/") && !path.includes("\\") && path.split("/").every((segment) => segment && segment !== "." && segment !== "..");
}

function isBelow(root, path) {
  const pathRelative = relative(root, path);
  return pathRelative && !pathRelative.startsWith("..") && !pathRelative.split("/").includes("..");
}

function parseJson(bytes, label) {
  try {
    return JSON.parse(bytes);
  } catch {
    throw new Error(`invalid ${label} JSON`);
  }
}

function assertExactKeys(value, keys, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every((key) => Object.hasOwn(value, key))) throw new Error(`invalid ${label}`);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}
