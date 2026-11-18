#!/usr/bin/env node
import { createHash } from "node:crypto";
import { lstatSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const expectedOptions = new Set([
  "--repository", "--git-sha", "--image-digest", "--contract-version", "--evidence", "--issue-ref", "--output",
]);

try {
  const arguments_ = parseArguments(process.argv.slice(2));
  validate(arguments_);
  const evidence = readEvidence(arguments_.evidence);
  writeAtomically(arguments_.output, {
    schemaVersion: 1,
    component: "backend",
    repository: arguments_.repository,
    gitSha: arguments_["git-sha"],
    artifactIdentity: {
      imageDigest: arguments_["image-digest"],
      apiContractVersion: arguments_["contract-version"],
    },
    contractVersion: arguments_["contract-version"],
    evidenceSha256: createHash("sha256").update(evidence).digest("hex"),
    issueRefs: [arguments_["issue-ref"]],
  });
} catch (error) {
  process.stderr.write(`build-component-manifest: ${error.message}\n`);
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
    if (Object.hasOwn(parsed, option.slice(2))) throw new Error(`duplicate option: ${option}`);
    parsed[option.slice(2)] = value;
  }
  if (Object.keys(parsed).length !== expectedOptions.size) throw new Error("all manifest options are required");
  return parsed;
}

function validate(values) {
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(values.repository)) throw new Error("repository is invalid");
  if (!/^[a-f0-9]{40}$/.test(values["git-sha"])) throw new Error("git sha is invalid");
  if (!/^sha256:[a-f0-9]{64}$/.test(values["image-digest"])) throw new Error("image digest is invalid");
  if (!/^[^\s]+$/.test(values["contract-version"])) throw new Error("contract version is invalid");
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+#[1-9]\d*$/.test(values["issue-ref"])) throw new Error("issue ref is invalid");
}

function readEvidence(evidencePath) {
  try {
    if (!lstatSync(evidencePath).isFile()) throw new Error("not a regular file");
    return readFileSync(evidencePath);
  } catch {
    throw new Error("evidence is unreadable");
  }
}

function writeAtomically(outputPath, document) {
  const output = resolve(outputPath);
  const parent = dirname(output);
  try {
    if (!lstatSync(parent).isDirectory()) throw new Error("not a directory");
    if (lstatSync(output, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error("output must not be a symlink");
  } catch (error) {
    if (error.code === "ENOENT") throw new Error("output parent is unavailable");
    throw error;
  }
  const temporary = `${output}.tmp-${process.pid}`;
  try {
    writeFileSync(temporary, `${JSON.stringify(document, null, 2)}\n`, { flag: "wx" });
    renameSync(temporary, output);
  } finally {
    rmSync(temporary, { force: true });
  }
}
