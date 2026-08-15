import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

const script = resolve(import.meta.dirname, "build-journey-contract-prepublication-caller.mjs");
const sourceSha = "a".repeat(40);

test("caller builder는 ZIP과 run identity를 exact caller metadata로 결속한다", () => {
  const directory = fixture();
  try {
    const output = join(directory, "caller.json");
    run(directory, output);
    const zip = readFileSync(join(directory, "artifact.zip"));
    assert.deepEqual(JSON.parse(readFileSync(output, "utf8")), { schemaVersion: 1, artifactKind: "journey-contract-prepublication-caller", repository: "AquilaXk/easysubway-backend", pullRequestNumber: 253, sourceSha, workflowRunId: "123", workflowRunAttempt: "2", artifactName: `journey-contract-prepublication-pr-253-${sourceSha}-run-123-attempt-2`, archiveSha256: sha(zip) });
    assert.equal(lstatSync(output).mode & 0o777, 0o600);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("caller builder는 unsafe input, invalid scalar, and output collision에서 output을 만들지 않는다", () => {
  const directory = fixture();
  try {
    const zip = join(directory, "artifact.zip");
    const cases = [
      ["symlink", () => { const link = join(directory, "artifact-link.zip"); symlinkSync(zip, link); return { zip: link }; }],
      ["nonregular", () => { const nonregular = join(directory, "artifact-directory"); mkdirSync(nonregular); return { zip: nonregular }; }],
      ["oversize", () => { const large = join(directory, "large.zip"); writeFileSync(large, Buffer.alloc(8 * 1024 * 1024 + 1)); return { zip: large }; }],
      ["invalid pr", () => ({ pr: "0" })],
      ["invalid SHA", () => ({ sourceSha: "A".repeat(40) })],
      ["invalid run", () => ({ runId: "01" })],
      ["invalid attempt", () => ({ attempt: "0" })],
    ];
    for (const [name, options] of cases) {
      const output = join(directory, `${name}.json`);
      assert.throws(() => run(directory, output, options()), /caller input is invalid/);
      assert.equal(existsSync(output), false, name);
    }
    const existing = join(directory, "existing.json"); writeFileSync(existing, "preserve\n"); chmodSync(existing, 0o644);
    assert.throws(() => run(directory, existing), /caller output is invalid/);
    assert.equal(readFileSync(existing, "utf8"), "preserve\n"); assert.equal(lstatSync(existing).mode & 0o777, 0o644);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

function fixture() { const directory = mkdtempSync(join(tmpdir(), "journey-prepublication-caller-")); writeFileSync(join(directory, "artifact.zip"), "PK\x03\x04fixture"); return directory; }
function run(directory, output, options = {}) { try { execFileSync(process.execPath, [script, "--artifact-zip", options.zip ?? join(directory, "artifact.zip"), "--pull-request-number", options.pr ?? "253", "--source-sha", options.sourceSha ?? sourceSha, "--workflow-run-id", options.runId ?? "123", "--workflow-run-attempt", options.attempt ?? "2", "--output", output], { stdio: "pipe" }); } catch (error) { throw new Error(error.stderr?.toString() || error.message); } }
function sha(value) { return createHash("sha256").update(value).digest("hex"); }
