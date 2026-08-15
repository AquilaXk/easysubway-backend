#!/usr/bin/env node
import { createHash } from "node:crypto";
import { closeSync, constants, fchmodSync, fsyncSync, fstatSync, lstatSync, openSync, readFileSync, unlinkSync, writeSync } from "node:fs";
import { isAbsolute } from "node:path";

const repository = "AquilaXk/easysubway-backend";
try {
  const args = parse(process.argv.slice(2));
  if (!/^[1-9][0-9]*$/.test(args.pr) || !Number.isSafeInteger(Number(args.pr)) || !/^[a-f0-9]{40}$/.test(args.sourceSha) || !/^[1-9][0-9]*$/.test(args.runId) || !/^[1-9][0-9]*$/.test(args.attempt)) throw new Error("caller input is invalid");
  const archive = readZip(args.artifactZip);
  const document = { schemaVersion: 1, artifactKind: "journey-contract-prepublication-caller", repository, pullRequestNumber: Number(args.pr), sourceSha: args.sourceSha, workflowRunId: args.runId, workflowRunAttempt: args.attempt, artifactName: `journey-contract-prepublication-pr-${args.pr}-${args.sourceSha}-run-${args.runId}-attempt-${args.attempt}`, archiveSha256: sha(archive) };
  createOutput(args.output, Buffer.from(`${JSON.stringify(document)}\n`));
} catch (error) {
  process.stderr.write(`build-journey-contract-prepublication-caller: ${error instanceof Error ? error.message : "caller input is invalid"}\n`);
  process.exitCode = 1;
}

function parse(values) {
  const names = ["--artifact-zip", "--pull-request-number", "--source-sha", "--workflow-run-id", "--workflow-run-attempt", "--output"];
  if (values.length !== names.length * 2) throw new Error("caller input is invalid");
  const found = {};
  for (let index = 0; index < values.length; index += 2) {
    if (!names.includes(values[index]) || !values[index + 1] || Object.hasOwn(found, values[index])) throw new Error("caller input is invalid");
    found[values[index]] = values[index + 1];
  }
  if (!isAbsolute(found["--artifact-zip"])) throw new Error("caller input is invalid");
  if (!isAbsolute(found["--output"])) throw new Error("caller output is invalid");
  return { artifactZip: found["--artifact-zip"], pr: found["--pull-request-number"], sourceSha: found["--source-sha"], runId: found["--workflow-run-id"], attempt: found["--workflow-run-attempt"], output: found["--output"] };
}

function readZip(path) {
  const lexical = lstatSync(path, { throwIfNoEntry: false });
  if (!lexical || lexical.isSymbolicLink() || !lexical.isFile() || lexical.size > 8 * 1024 * 1024) throw new Error("caller input is invalid");
  let descriptor;
  try { descriptor = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW); } catch { throw new Error("caller input is invalid"); }
  try {
    const before = fstatSync(descriptor);
    if (!before.isFile() || before.dev !== lexical.dev || before.ino !== lexical.ino || before.size > 8 * 1024 * 1024) throw new Error("caller input is invalid");
    const bytes = readFileSync(descriptor);
    const after = fstatSync(descriptor), stable = lstatSync(path, { throwIfNoEntry: false });
    if (before.dev !== after.dev || before.ino !== after.ino || before.size !== after.size || bytes.length !== before.size || !stable || stable.isSymbolicLink() || !stable.isFile() || stable.dev !== before.dev || stable.ino !== before.ino || stable.size !== before.size) throw new Error("caller input is invalid");
    return bytes;
  } finally { closeSync(descriptor); }
}

function createOutput(path, bytes) {
  if (lstatSync(path, { throwIfNoEntry: false })) throw new Error("caller output is invalid");
  let descriptor, opened;
  try { descriptor = openSync(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600); } catch { throw new Error("caller output is invalid"); }
  try { opened = fstatSync(descriptor); let offset = 0; while (offset < bytes.length) { const written = writeSync(descriptor, bytes, offset); if (written <= 0) throw new Error("write failed"); offset += written; } fchmodSync(descriptor, 0o600); fsyncSync(descriptor); } catch (error) { const current = lstatSync(path, { throwIfNoEntry: false }); if (opened && current?.isFile() && current.dev === opened.dev && current.ino === opened.ino) unlinkSync(path); throw new Error("caller output is invalid", { cause: error }); } finally { closeSync(descriptor); }
}

function sha(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
