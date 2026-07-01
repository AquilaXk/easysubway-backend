import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";

const execFileAsync = promisify(execFile);
const root = path.resolve(import.meta.dirname, "../..");
const gate = JSON.parse(readFileSync(path.join(root, "apps/mobile/release/abuse-penetration-rehearsal-gate.json"), "utf8"));
const artifactIdentity = {
  gitSha: "abcdef1234567890",
  versionCode: 10001,
  androidApplicationId: "com.easysubway.app",
  dataPackManifestSha256: "a".repeat(64),
  aabSha256: "b".repeat(64),
  backendArtifactSha256: "c".repeat(64),
};

function caseValue(field, matrix, caseId) {
  if (field === "expectedStatus" || field === "observedStatus") return matrix.expectedStatusByCase[caseId][0];
  return {
    apiStep: "submit",
    artifactType: "android-aab",
    auditRedactionResult: "PASS",
    bucketOrPolicyAlias: "report-photo-release",
    cleanupResult: "PASS",
    commandOrManualCheck: "redacted local rehearsal",
    contentType: "image/jpeg",
    deleteOrCleanupResult: "PASS",
    endpoint: "/api/v1/reports",
    localEvidencePath: ".codex/evidence/security/abuse-penetration-rehearsal/rc/redacted-summary.json",
    method: "PUT",
    nodeOrStoreMode: "multi-node",
    redactionResult: "PASS",
    retentionRule: "30d",
    role: "REPORT_REVIEWER",
    scanTarget: "release artifact",
    sizeBytes: 1024,
    tenantScope: "operator-global",
    ttlSeconds: 60,
  }[field] ?? "PASS";
}

function validSummary() {
  return {
    schemaVersion: 1,
    releaseGate: gate.releaseGate,
    issue: gate.issue,
    status: "PASS",
    artifactIdentity: { ...artifactIdentity },
    matrices: Object.entries(gate.rehearsalMatrices).map(([matrixId, matrix]) => ({
      matrixId,
      scenarioId: matrix.scenarioId,
      artifactIdentity: { ...artifactIdentity },
      commandOrManualCheck: "redacted local rehearsal",
      findingCounts: { critical: 0, high: 0, medium: 0, low: 0 },
      result: "PASS",
      redactionNotes: "sensitive values removed",
      localEvidencePath: ".codex/evidence/security/abuse-penetration-rehearsal/rc/redacted-summary.json",
      requiredEvidence: matrix.requiredEvidence,
      cases: matrix.requiredCases.map((caseId) => {
        const item = { caseId };
        for (const field of matrix.summaryFields) item[field] = field === "caseId" ? caseId : caseValue(field, matrix, caseId);
        return item;
      }),
    })),
  };
}

async function withSummary(summary, fn) {
  const dir = path.join(tmpdir(), `abuse-summary-${Date.now()}-${Math.random().toString(16).slice(2)}`);
  await rm(dir, { recursive: true, force: true });
  await mkdir(dir, { recursive: true });
  const summaryPath = path.join(dir, "summary.json");
  await writeFile(summaryPath, `${JSON.stringify(summary, null, 2)}\n`);
  return fn(summaryPath);
}

test("abuse penetration summary validator accepts a complete redacted matrix summary", async () => {
  await withSummary(validSummary(), (summaryPath) =>
    execFileAsync(process.execPath, [
      "tools/security/validate-abuse-penetration-summary.mjs",
      "--summary",
      summaryPath,
      "--require-pass",
    ], { cwd: root }),
  );
});

test("abuse penetration summary validator rejects missing cases, raw sensitive markers, and high findings", async () => {
  const missingCase = validSummary();
  missingCase.matrices[0].cases.pop();
  await assert.rejects(
    withSummary(missingCase, (summaryPath) =>
      execFileAsync(process.execPath, ["tools/security/validate-abuse-penetration-summary.mjs", "--summary", summaryPath], {
        cwd: root,
      }),
    ),
    /cases\./,
  );

  const leaked = validSummary();
  leaked.matrices[0].redactionNotes = "raw signed URL leaked";
  await assert.rejects(
    withSummary(leaked, (summaryPath) =>
      execFileAsync(process.execPath, ["tools/security/validate-abuse-penetration-summary.mjs", "--summary", summaryPath], {
        cwd: root,
      }),
    ),
    /forbidden sensitive evidence marker/,
  );

  const highFinding = validSummary();
  highFinding.matrices[0].findingCounts.high = 1;
  await assert.rejects(
    withSummary(highFinding, (summaryPath) =>
      execFileAsync(process.execPath, [
        "tools/security/validate-abuse-penetration-summary.mjs",
        "--summary",
        summaryPath,
        "--require-pass",
      ], { cwd: root }),
    ),
    /critical\/high findings/,
  );

  const stringCount = validSummary();
  stringCount.matrices[0].findingCounts.critical = "0";
  await assert.rejects(
    withSummary(stringCount, (summaryPath) =>
      execFileAsync(process.execPath, ["tools/security/validate-abuse-penetration-summary.mjs", "--summary", summaryPath], {
        cwd: root,
      }),
    ),
    /non-negative integers/,
  );

  const mixedIdentity = validSummary();
  mixedIdentity.matrices[0].artifactIdentity.versionCode = 10002;
  await assert.rejects(
    withSummary(mixedIdentity, (summaryPath) =>
      execFileAsync(process.execPath, ["tools/security/validate-abuse-penetration-summary.mjs", "--summary", summaryPath], {
        cwd: root,
      }),
    ),
    /artifactIdentity must match/,
  );
});

test("abuse penetration summary validator rejects case-level failed rehearsal evidence", async () => {
  const statusMismatch = validSummary();
  statusMismatch.matrices[0].cases[0].observedStatus = 200;
  await assert.rejects(
    withSummary(statusMismatch, (summaryPath) =>
      execFileAsync(process.execPath, [
        "tools/security/validate-abuse-penetration-summary.mjs",
        "--summary",
        summaryPath,
        "--require-pass",
      ], { cwd: root }),
    ),
    /observedStatus must match expectedStatus/,
  );

  const redactionFailure = validSummary();
  redactionFailure.matrices[0].cases[0].redactionResult = "FAIL";
  await assert.rejects(
    withSummary(redactionFailure, (summaryPath) =>
      execFileAsync(process.execPath, [
        "tools/security/validate-abuse-penetration-summary.mjs",
        "--summary",
        summaryPath,
        "--require-pass",
      ], { cwd: root }),
    ),
    /redactionResult must be PASS/,
  );

  const untrustedStatusPair = validSummary();
  untrustedStatusPair.matrices[0].cases[0].expectedStatus = 500;
  untrustedStatusPair.matrices[0].cases[0].observedStatus = 500;
  await assert.rejects(
    withSummary(untrustedStatusPair, (summaryPath) =>
      execFileAsync(process.execPath, [
        "tools/security/validate-abuse-penetration-summary.mjs",
        "--summary",
        summaryPath,
        "--require-pass",
      ], { cwd: root }),
    ),
    /expectedStatus must match release gate/,
  );
});
