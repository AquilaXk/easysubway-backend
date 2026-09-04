#!/usr/bin/env node
import { createHash } from "node:crypto";
import { lstatSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";
import { JOURNEY_CONTRACT_RESOURCES } from "./journey-contract-resources.mjs";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const backendBuild = resolve(repositoryRoot, "backend/build");
const expectedOptions = new Set(["--producer-sha", "--output"]);
try {
  const arguments_ = parseArguments(process.argv.slice(2));
  if (arguments_["producer-sha"].length !== 40 || !/^[a-f0-9]{40}$/.test(arguments_["producer-sha"])) throw new Error("producer sha is invalid");
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
  return JOURNEY_CONTRACT_RESOURCES.map((expected) => {
    const contractPath = expected.path.slice("contracts/api/".length);
    const bytes = readRegularFile(resolve(contractRoot, contractPath), expected.id);
    const sha256 = createHash("sha256").update(bytes).digest("hex");
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
