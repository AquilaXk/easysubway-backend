#!/usr/bin/env node
import { createHash } from "node:crypto";
import { existsSync, lstatSync, mkdirSync, readFileSync, realpathSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";

const requiredResources = [
  "datapack/source-governance-policy.json",
  "datapack/datapack-freshness-sla.json",
];
const artifactUrl = "https://raw.githubusercontent.com/AquilaXk/easysubway/6c29b55e6cbdb1713522cb4f766d9754728d5fc8/contracts/bundles/backend-contracts-v1.0.0.json";
const repositoryRoot = resolve(import.meta.dirname, "../..");
const backendBuild = resolve(repositoryRoot, "backend/build");

try {
  const { lock, input, output } = parseArguments(process.argv.slice(2));
  assertRegularFile(lock, "lock");
  assertRegularFile(input, "input");
  const stagedOutput = assertOutputBelowBuild(output);

  const lockDocument = parseJson(readFileSync(lock), "lock");
  assertExactKeys(lockDocument, ["schemaVersion", "bundleVersion", "artifactUrl", "sha256"], "lock");
  if (lockDocument.schemaVersion !== 1 || lockDocument.bundleVersion !== "1.0.0" || lockDocument.artifactUrl !== artifactUrl || !/^[a-f0-9]{64}$/.test(lockDocument.sha256)) {
    throw new Error("invalid lock");
  }

  const bytes = readFileSync(input);
  if (createHash("sha256").update(bytes).digest("hex") !== lockDocument.sha256) throw new Error("sha256 mismatch");
  const bundle = parseJson(bytes, "bundle");
  assertExactKeys(bundle, ["schemaVersion", "bundleVersion", "resources"], "bundle");
  if (bundle.schemaVersion !== 1 || bundle.bundleVersion !== lockDocument.bundleVersion) throw new Error("bundleVersion mismatch");
  if (bundle.resources === null || typeof bundle.resources !== "object" || Array.isArray(bundle.resources)) throw new Error("invalid resources");
  if (Object.keys(bundle.resources).sort((left, right) => left.localeCompare(right)).join("\n") !== requiredResources.slice().sort((left, right) => left.localeCompare(right)).join("\n")) throw new Error("invalid resources");
  for (const resource of requiredResources) {
    const content = bundle.resources[resource];
    if (typeof content !== "string") throw new Error("invalid resources");
    const document = parseJson(Buffer.from(content), `resource ${resource}`);
    if (document === null || typeof document !== "object" || Array.isArray(document)) throw new Error("invalid resources");
  }

  const temporary = `${stagedOutput}.tmp-${process.pid}`;
  rmSync(temporary, { recursive: true, force: true });
  try {
    for (const resource of requiredResources) {
      const destination = resolve(temporary, resource);
      mkdirSync(dirname(destination), { recursive: true });
      writeFileSync(destination, bundle.resources[resource]);
    }
    mkdirSync(dirname(stagedOutput), { recursive: true });
    rmSync(stagedOutput, { recursive: true, force: true });
    renameSync(temporary, stagedOutput);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}

function parseArguments(arguments_) {
  const options = new Map();
  for (let index = 0; index < arguments_.length; index += 2) {
    const option = arguments_[index];
    const value = arguments_[index + 1];
    if (!new Set(["--lock", "--input", "--output"]).has(option)) throw new Error(`unknown option: ${option}`);
    if (value === undefined || value.startsWith("--")) throw new Error(`missing value: ${option}`);
    if (options.has(option)) throw new Error(`duplicate option: ${option}`);
    options.set(option, value);
  }
  if (options.size !== 3) throw new Error("lock, input, and output are required");
  return { lock: options.get("--lock"), input: options.get("--input"), output: options.get("--output") };
}

function assertRegularFile(path, label) {
  if (!lstatSync(path).isFile()) throw new Error(`${label} must be a regular file, not a symlink`);
}

function assertOutputBelowBuild(path) {
  const output = resolve(path);
  const pathBelowBuild = relative(backendBuild, output);
  if (pathBelowBuild === "" || pathBelowBuild.startsWith("..") || pathBelowBuild.includes("../")) throw new Error("output must be below backend/build");
  mkdirSync(backendBuild, { recursive: true });
  if (lstatSync(backendBuild).isSymbolicLink()) throw new Error("backend/build must not be a symlink");
  const normalizedBuild = realpathSync(backendBuild);
  let current = backendBuild;
  for (const segment of pathBelowBuild.split("/")) {
    current = resolve(current, segment);
    if (!existsSync(current)) continue;
    const metadata = lstatSync(current);
    if (metadata.isSymbolicLink()) throw new Error("output must not have a symlink ancestor");
    if (!metadata.isDirectory()) throw new Error("output ancestor must be a directory");
    const normalizedAncestor = realpathSync(current);
    const ancestorBelowBuild = relative(normalizedBuild, normalizedAncestor);
    if (ancestorBelowBuild.startsWith("..") || ancestorBelowBuild.includes("../")) throw new Error("output must be below backend/build");
  }
  return resolve(normalizedBuild, pathBelowBuild);
}

function parseJson(bytes, label) {
  try {
    return JSON.parse(bytes);
  } catch {
    throw new Error(`invalid ${label} JSON`);
  }
}

function assertExactKeys(value, keys, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || Object.keys(value).sort((left, right) => left.localeCompare(right)).join("\n") !== keys.slice().sort((left, right) => left.localeCompare(right)).join("\n")) {
    throw new Error(`invalid ${label}`);
  }
}
