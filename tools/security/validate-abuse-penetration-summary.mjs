#!/usr/bin/env node
import {
  argValue,
  collectStrings,
  readJson,
  required,
  stableFlatJson,
} from "../release/summary-validation-utils.mjs";

const STATUS = new Set(["PASS", "FAIL", "BLOCKED_EXTERNAL"]);
const RAW_SECRET_PATTERNS = [
  /https?:\/\/\S*(x-amz-signature|x-goog-signature|signature=|sig=|token=|receipt)/i,
  /\bAuthorization:\s*(Bearer|Basic)\s+\S+/i,
  /\bCookie:\s*\S+/i,
  /\b(JSESSIONID|sessionid)=\S+/i,
  /-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----/i,
];

function assertNoSensitiveSummary(summary, gate) {
  const forbiddenValues = new Set([
    ...gate.manualRehearsalPolicy.forbiddenInEvidence,
    ...Object.values(gate.rehearsalMatrices).flatMap((matrix) => matrix.forbiddenSummaryValues),
    ...gate.latestQaEvidenceStatus.redactionPolicy.forbiddenInGitHubEvidence,
    ...gate.productionLikeEvidencePolicy.forbiddenClosureEvidence,
  ].map((value) => value.toLowerCase()));
  for (const [path, value] of collectStrings(summary)) {
    const normalized = value.toLowerCase();
    for (const forbidden of forbiddenValues) {
      if (normalized.includes(forbidden)) {
        throw new Error(`${path} contains forbidden sensitive evidence marker: ${forbidden}`);
      }
    }
    for (const pattern of RAW_SECRET_PATTERNS) {
      if (pattern.test(value)) {
        throw new Error(`${path} appears to contain raw secret, token, cookie, or signed URL material`);
      }
    }
  }
}

function assertIdentity(summary, gate, path = "artifactIdentity", expectedIdentity) {
  const identity = required(summary.artifactIdentity, path);
  for (const field of gate.buildIdentityPolicy.requiredIdentityFields) {
    required(identity[field], `${path}.${field}`);
  }
  for (const fields of gate.buildIdentityPolicy.requiredIdentityAnyOf) {
    if (!fields.some((field) => identity[field])) {
      throw new Error(`${path} must include one of: ${fields.join(", ")}`);
    }
  }
  if (expectedIdentity && stableFlatJson(identity) !== stableFlatJson(expectedIdentity)) {
    throw new Error(`${path} must match artifactIdentity`);
  }
  return identity;
}

function assertFindingCounts(matrixSummary, requirePass, gate) {
  const counts = required(matrixSummary.findingCounts, `${matrixSummary.matrixId}.findingCounts`);
  const critical = counts.critical ?? 0;
  const high = counts.high ?? 0;
  const medium = counts.medium ?? 0;
  if (![critical, high, medium].every((value) => Number.isInteger(value) && value >= 0)) {
    throw new Error(`${matrixSummary.matrixId}.findingCounts must be non-negative integers`);
  }
  if (critical > gate.findingPolicy.criticalHighAllowed || high > gate.findingPolicy.criticalHighAllowed) {
    throw new Error(`${matrixSummary.matrixId} has critical/high findings`);
  }
  if (medium > 0 && (!matrixSummary.mediumFindingDisposition?.owner || !matrixSummary.mediumFindingDisposition?.fixPlan)) {
    throw new Error(`${matrixSummary.matrixId} medium findings require owner and fixPlan`);
  }
  if (requirePass && matrixSummary.result !== "PASS") {
    throw new Error(`${matrixSummary.matrixId}.result must be PASS`);
  }
}

function assertProductionLikeEvidence(summary, gate, requirePass) {
  if (!requirePass) return;

  const evidenceById = new Map(
    required(summary.productionLikeEvidence, "productionLikeEvidence").map((item) => [item.evidenceId, item]),
  );
  for (const evidenceId of gate.productionLikeEvidencePolicy.requiredForClosing) {
    const evidence = required(evidenceById.get(evidenceId), `productionLikeEvidence missing ${evidenceId}`);
    if (evidence.result !== "PASS") throw new Error(`productionLikeEvidence.${evidenceId}.result must be PASS`);
    required(evidence.localEvidencePath, `productionLikeEvidence.${evidenceId}.localEvidencePath`);
  }
}

function expectedStatuses(matrixId, caseId, matrix) {
  const statuses = required(matrix.expectedStatusByCase?.[caseId], `${matrixId}.expectedStatusByCase.${caseId}`);
  if (!Array.isArray(statuses) || statuses.length === 0 || !statuses.every(Number.isInteger)) {
    throw new Error(`${matrixId}.expectedStatusByCase.${caseId} must be non-empty integer status list`);
  }
  return statuses;
}

function assertCasePass(matrixId, caseId, item, matrix) {
  if (item.observedStatus !== item.expectedStatus) {
    throw new Error(`${matrixId}.cases.${caseId}.observedStatus must match expectedStatus`);
  }
  const statuses = expectedStatuses(matrixId, caseId, matrix);
  if (!statuses.includes(item.expectedStatus)) {
    throw new Error(`${matrixId}.cases.${caseId}.expectedStatus must match release gate`);
  }
  if (!statuses.includes(item.observedStatus)) {
    throw new Error(`${matrixId}.cases.${caseId}.observedStatus must match release gate`);
  }
  for (const field of ["redactionResult", "auditRedactionResult", "cleanupResult", "deleteOrCleanupResult"]) {
    if (item[field] !== undefined && item[field] !== "PASS") {
      throw new Error(`${matrixId}.cases.${caseId}.${field} must be PASS`);
    }
  }
}

function assertMatrix(matrixId, matrix, matrixSummary, gate, requirePass) {
  required(matrixSummary, `matrices.${matrixId}`);
  if (matrixSummary.scenarioId !== matrix.scenarioId) {
    throw new Error(`${matrixId}.scenarioId must be ${matrix.scenarioId}`);
  }
  for (const field of ["matrixId", ...gate.manualRehearsalPolicy.githubSummaryFields]) {
    required(matrixSummary[field], `${matrixId}.${field}`);
  }
  const evidence = new Set(required(matrixSummary.requiredEvidence, `${matrixId}.requiredEvidence`));
  for (const evidenceId of matrix.requiredEvidence) {
    if (!evidence.has(evidenceId)) throw new Error(`${matrixId}.requiredEvidence missing ${evidenceId}`);
  }
  const caseById = new Map(required(matrixSummary.cases, `${matrixId}.cases`).map((item) => [item.caseId, item]));
  for (const caseId of matrix.requiredCases) {
    const item = required(caseById.get(caseId), `${matrixId}.cases.${caseId}`);
    for (const field of matrix.summaryFields) {
      required(item[field], `${matrixId}.cases.${caseId}.${field}`);
    }
    if (requirePass) assertCasePass(matrixId, caseId, item, matrix);
  }
}

async function main() {
  const args = process.argv.slice(2);
  const summaryPath = argValue(args, "--summary");
  const gatePath = argValue(args, "--gate", "apps/mobile/release/abuse-penetration-rehearsal-gate.json");
  const requirePass = args.includes("--require-pass");
  if (!summaryPath) throw new Error("--summary is required");

  const [summary, gate] = await Promise.all([readJson(summaryPath), readJson(gatePath)]);
  if (summary.schemaVersion !== 1) throw new Error("schemaVersion must be 1");
  if (summary.releaseGate !== gate.releaseGate) throw new Error(`releaseGate must be ${gate.releaseGate}`);
  if (summary.issue !== gate.issue) throw new Error(`issue must be ${gate.issue}`);
  if (!STATUS.has(summary.status)) throw new Error("status must be a release gate status");
  if (requirePass && summary.status !== "PASS") throw new Error("status must be PASS");

  const artifactIdentity = assertIdentity(summary, gate);
  assertNoSensitiveSummary(summary, gate);
  assertProductionLikeEvidence(summary, gate, requirePass);

  const matrixSummaries = new Map(required(summary.matrices, "matrices").map((matrix) => [matrix.matrixId, matrix]));
  for (const [matrixId, matrix] of Object.entries(gate.rehearsalMatrices)) {
    const matrixSummary = matrixSummaries.get(matrixId);
    assertMatrix(matrixId, matrix, matrixSummary, gate, requirePass);
    assertIdentity(matrixSummary, gate, `${matrixId}.artifactIdentity`, artifactIdentity);
    assertFindingCounts(matrixSummary, requirePass, gate);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
