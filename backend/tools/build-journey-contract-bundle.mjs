#!/usr/bin/env node
import { createHash } from "node:crypto";
import { lstatSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const backendBuild = resolve(repositoryRoot, "backend/build");
const expectedOptions = new Set(["--producer-sha", "--output"]);
const expectedResources = [
  {
    id: "journey-v3-error-catalog",
    digestPath: "journey-v3-error-catalog.json",
    path: "contracts/api/journey-v3-error-catalog.json",
    mediaType: "application/json",
  },
  {
    id: "journey-v3-error-disposition",
    digestPath: "journey-v3-error-disposition.json",
    path: "contracts/api/journey-v3-error-disposition.json",
    mediaType: "application/json",
  },
  {
    id: "journey-v3-openapi",
    digestPath: "journey-v3.openapi.yaml",
    path: "contracts/api/journey-v3.openapi.yaml",
    mediaType: "application/yaml",
  },
];

try {
  const arguments_ = parseArguments(process.argv.slice(2));
  if (!/^[a-f0-9]{40}$/.test(arguments_["producer-sha"])) throw new Error("producer sha is invalid");
  const contractRoot = resolveContractRoot();
  const resources = readResources(contractRoot);
  const output = assertOutput(arguments_.output);
  writeAtomically(output, {
    schemaVersion: 2,
    bundleVersion: "2.0.0",
    component: "backend",
    producerRepository: "AquilaXk/easysubway-backend",
    producerSha: arguments_["producer-sha"],
    resources,
  });
} catch (error) {
  process.stderr.write(`build-journey-contract-bundle: ${error.message}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  if (values.length % 2 !== 0) throw new Error("arguments must be option/value pairs");
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const option = values[index];
    const value = values[index + 1];
    if (!expectedOptions.has(option)) throw new Error(`unknown option: ${option}`);
    if (!value || value.startsWith("--")) throw new Error(`missing value: ${option}`);
    const name = option.slice(2);
    if (Object.hasOwn(parsed, name)) throw new Error(`duplicate option: ${option}`);
    parsed[name] = value;
  }
  if (Object.keys(parsed).length !== expectedOptions.size) throw new Error("producer sha and output are required");
  return parsed;
}

function resolveContractRoot() {
  const override = process.env.EASYSUBWAY_JOURNEY_CONTRACT_ROOT;
  if (override && process.env.NODE_ENV !== "test") throw new Error("contract root override is test-only");
  return override ? resolve(override) : resolve(repositoryRoot, "contracts/api");
}

function readResources(contractRoot) {
  const digestDocument = parseJson(readRegularFile(resolve(contractRoot, "journey-v3-contract-digests.json"), "digest document"), "digest document");
  assertExactKeys(digestDocument, ["schemaVersion", "artifactKind", "artifacts"], "digest document");
  if (digestDocument.schemaVersion !== "JOURNEY_V3_CONTRACT_DIGESTS_V1" || digestDocument.artifactKind !== "journey-v3-contract-digests" || !Array.isArray(digestDocument.artifacts)) {
    throw new Error("invalid digest document");
  }
  if (digestDocument.artifacts.length !== expectedResources.length) throw new Error("invalid digest resources");

  return expectedResources.map((expected, index) => {
    const digest = digestDocument.artifacts[index];
    assertExactKeys(digest, ["path", "sha256"], "digest resource");
    if (digest.path !== expected.digestPath || !/^[a-f0-9]{64}$/.test(digest.sha256)) throw new Error("invalid digest resources");
    const bytes = readRegularFile(resolve(contractRoot, expected.digestPath), expected.id);
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    if (sha256 !== digest.sha256) throw new Error(`digest mismatch: ${expected.digestPath}`);
    return {
      id: expected.id,
      path: expected.path,
      owner: "AquilaXk/easysubway-backend",
      mediaType: expected.mediaType,
      sha256,
      contentBase64: bytes.toString("base64"),
    };
  });
}

function assertOutput(outputPath) {
  const output = resolve(outputPath);
  const pathBelowBuild = relative(backendBuild, output);
  if (!pathBelowBuild || pathBelowBuild.startsWith("..") || pathBelowBuild.includes("../")) throw new Error("output must be below backend/build");
  assertDirectory(backendBuild, "backend/build");
  let current = backendBuild;
  for (const segment of pathBelowBuild.split("/")) {
    current = resolve(current, segment);
    const metadata = lstatSync(current, { throwIfNoEntry: false });
    if (!metadata) continue;
    if (metadata.isSymbolicLink()) throw new Error("output must not have a symlink ancestor");
    if (current !== output && !metadata.isDirectory()) throw new Error("output ancestor must be a directory");
    if (current === output && !metadata.isFile()) throw new Error("output must be a regular file");
  }
  assertDirectory(dirname(output), "output parent");
  return output;
}

function assertDirectory(path, label) {
  const metadata = lstatSync(path, { throwIfNoEntry: false });
  if (!metadata || metadata.isSymbolicLink() || !metadata.isDirectory()) throw new Error(`${label} must be a non-symlink directory`);
}

function readRegularFile(path, label) {
  const metadata = lstatSync(path, { throwIfNoEntry: false });
  if (!metadata || metadata.isSymbolicLink() || !metadata.isFile()) throw new Error(`${label} must be a regular file`);
  return readFileSync(path);
}

function parseJson(bytes, label) {
  try {
    return JSON.parse(bytes);
  } catch {
    throw new Error(`invalid ${label} JSON`);
  }
}

function assertExactKeys(value, keys, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every((key) => Object.hasOwn(value, key))) {
    throw new Error(`invalid ${label}`);
  }
}

function writeAtomically(output, document) {
  const temporary = `${output}.tmp-${process.pid}`;
  let temporaryCreated = false;
  try {
    writeFileSync(temporary, `${JSON.stringify(document)}\n`, { flag: "wx", mode: 0o600 });
    temporaryCreated = true;
    renameSync(temporary, output);
  } catch (error) {
    if (temporaryCreated) rmSync(temporary, { force: true });
    throw error;
  }
}
