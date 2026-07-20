import { createHash } from "node:crypto";
import { execFile, execFileSync } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import assert from "node:assert/strict";
import test from "node:test";
import { validateSchema } from "../ci/lib/json-schema-lite.mjs";
import { buildAbusePenetrationSummaryV2Schema, deriveSummaryCatalog } from "./abuse-penetration-summary-schema.mjs";
import { validateAbusePenetrationSummary } from "./validate-abuse-penetration-summary.mjs";
import { codepointCompare } from "../lib/codepoint-compare.mjs";

const execFileAsync = promisify(execFile);
const root = path.resolve(import.meta.dirname, "../..");
const gatePath = path.join(root, "apps/mobile/release/abuse-penetration-rehearsal-gate.json");
const gate = JSON.parse(readFileSync(gatePath, "utf8"));

const schemaV2EvidenceRoot = `.codex/evidence/security/abuse-penetration-rehearsal/${"a".repeat(40)}`;
const schemaV2EvidencePath = `${schemaV2EvidenceRoot}/redacted.json`;
const schemaV2Identity = Object.freeze({
  gitSha: "a".repeat(40), versionCode: 10001, androidApplicationId: "com.easysubway.app",
  dataPackManifestSha256: "b".repeat(64), aabSha256: "c".repeat(64),
  generatedApkSha256: "d".repeat(64), backendImageDigest: `sha256:${"e".repeat(64)}`,
  backendArtifactSha256: "f".repeat(64),
});
function schemaV2IdentitySha256(identity = schemaV2Identity) {
  return createHash("sha256")
    .update(JSON.stringify(Object.fromEntries(Object.entries(identity).sort(([left], [right]) => codepointCompare(left, right)))))
    .digest("hex");
}
function schemaV2Evidence(evidenceId) {
  return {
    evidenceId,
    result: "PASS",
    localEvidencePath: `${schemaV2EvidenceRoot}/${evidenceId}.json`,
    artifactIdentitySha256: schemaV2IdentitySha256(),
  };
}
function schemaV2Blocked(gateValue = gate, status = "BLOCKED_EXTERNAL") {
  return { schemaVersion: 2, releaseGate: gateValue.releaseGate, issue: gateValue.issue, status,
    rawInvocationStored: false, redactionPolicyId: "summary-v2-no-sensitive-values" };
}
function schemaV2Pass(gateValue = gate, withDisposition = true) {
  const evidenceIds = Array.from(new Set(Object.values(gateValue.rehearsalMatrices)
    .flatMap((matrix) => matrix.requiredEvidence))).sort();
  const summary = Object.assign(schemaV2Blocked(gateValue, "PASS"), {
    artifactIdentity: structuredClone(schemaV2Identity), evidence: evidenceIds.map(schemaV2Evidence),
    productionLikeEvidence: gateValue.productionLikeEvidencePolicy.requiredForClosing.map(schemaV2Evidence),
    matrices: Object.entries(gateValue.rehearsalMatrices).map(([matrixId, matrix]) => ({
      matrixId, result: "PASS", findingCounts: { critical: 0, high: 0, medium: 0, low: 0 },
      cases: matrix.requiredCases.map((caseId) => ({
        procedureId: `${matrixId}.${caseId}`, targetAlias: `target.${matrixId}`,
        expectedStatus: matrix.expectedStatusByCase[caseId][0], observedStatus: matrix.expectedStatusByCase[caseId][0],
        redactionResult: "PASS", localEvidencePath: schemaV2EvidencePath,
        artifactIdentitySha256: schemaV2IdentitySha256(),
      })),
    })),
  });
  if (withDisposition) {
    summary.matrices[0].findingCounts.medium = 1;
    summary.matrices[0].mediumFindingDisposition = {
      ownerAlias: `owner.${summary.matrices[0].matrixId}`,
      fixPlanEvidencePath: ".codex/evidence/security/abuse-penetration-rehearsal/run/fix-plan.json",
    };
  }
  return summary;
}
function schemaV2Nodes(summary) {
  return {
    root: summary, artifactIdentity: summary.artifactIdentity, evidence: summary.evidence[0],
    matrix: summary.matrices[0], findingCounts: summary.matrices[0].findingCounts,
    mediumFindingDisposition: summary.matrices[0].mediumFindingDisposition,
    case: summary.matrices[0].cases[0],
  };
}
function schemaV2Validate(summary, gateValue = gate) {
  return validateSchema(buildAbusePenetrationSummaryV2Schema(gateValue), summary);
}

test("A RED Route V2 ingress cases are part of the validated matrix catalog", () => {
  const catalog = deriveSummaryCatalog(gate);
  assert.ok(catalog.matrixIds.includes("routeV2IngressAbuse"));
  assert.ok(catalog.procedureIds.includes("routeV2IngressAbuse.gateway_token_limiter"));
});

test("A RED v2 PASS evidence is bound to the root artifact identity", () => {
  const summary = schemaV2Pass();
  assert.equal(schemaV2Validate(summary).ok, true);
  summary.productionLikeEvidence[0].artifactIdentitySha256 = "0".repeat(64);
  assert.throws(
    () => validateAbusePenetrationSummary(summary, gate, true),
    (error) => error.code === "SUMMARY_IDENTITY_INVALID" && error.path.endsWith(".artifactIdentitySha256"),
  );
  const stale = schemaV2Pass();
  stale.productionLikeEvidence[0].localEvidencePath = ".codex/evidence/security/abuse-penetration-rehearsal/older-rc/result.json";
  assert.throws(
    () => validateAbusePenetrationSummary(stale, gate, true),
    (error) => error.code === "SUMMARY_IDENTITY_INVALID" && error.path.endsWith(".localEvidencePath"),
  );
  const duplicate = schemaV2Pass();
  duplicate.productionLikeEvidence[1].localEvidencePath = duplicate.productionLikeEvidence[0].localEvidencePath;
  assert.throws(
    () => validateAbusePenetrationSummary(duplicate, gate, true),
    (error) => error.code === "SUMMARY_IDENTITY_INVALID" && error.rule === "duplicate-evidence-path",
  );
  const crossCollectionDuplicate = schemaV2Pass();
  crossCollectionDuplicate.productionLikeEvidence[0].localEvidencePath =
    crossCollectionDuplicate.evidence[0].localEvidencePath;
  assert.throws(
    () => validateAbusePenetrationSummary(crossCollectionDuplicate, gate, true),
    (error) => error.code === "SUMMARY_IDENTITY_INVALID" && error.rule === "duplicate-evidence-path",
  );
  const traversed = schemaV2Pass();
  traversed.evidence[0].localEvidencePath = `${schemaV2EvidenceRoot}/../older-rc/result.json`;
  assert.throws(
    () => validateAbusePenetrationSummary(traversed, gate, true),
    (error) => error.code === "SUMMARY_V2_SCHEMA_INVALID" ||
      (error.code === "SUMMARY_IDENTITY_INVALID" && error.rule === "root-git-sha-path"),
  );
  const staleCase = schemaV2Pass();
  staleCase.matrices.find((matrix) => matrix.matrixId === "routeV2IngressAbuse")
    .cases[0].localEvidencePath =
      `.codex/evidence/security/abuse-penetration-rehearsal/${"f".repeat(40)}/result.json`;
  assert.throws(
    () => validateAbusePenetrationSummary(staleCase, gate, true),
    (error) => error.code === "SUMMARY_IDENTITY_INVALID" && error.rule === "root-git-sha-path",
  );
});
test("A RED v2 matrix cases are bound to the complete root artifact identity", () => {
  const summary = schemaV2Pass();
  assert.equal(schemaV2Validate(summary).ok, true);
  summary.matrices[0].cases[0].artifactIdentitySha256 = "0".repeat(64);
  assert.throws(
    () => validateAbusePenetrationSummary(summary, gate, true),
    (error) => error.code === "SUMMARY_IDENTITY_INVALID" &&
      error.path.endsWith(".artifactIdentitySha256") &&
      error.rule === "root-artifact-identity-digest",
  );
  const unbound = schemaV2Blocked(gate, "FAIL");
  unbound.matrices = [freshV2Pass().matrices[0]];
  assert.throws(
    () => validateAbusePenetrationSummary(unbound, gate),
    (error) => error.code === "SUMMARY_IDENTITY_INVALID" &&
      error.path === "$.artifactIdentity" &&
      error.rule === "matrix-case-root-artifact-identity",
  );
});
test("A RED v2 evidence collections require the complete root artifact identity", () => {
  for (const collection of ["evidence", "productionLikeEvidence"]) {
    const summary = schemaV2Blocked(gate, "FAIL");
    summary[collection] = [structuredClone(freshV2Pass()[collection][0])];
    assert.throws(
      () => validateAbusePenetrationSummary(summary, gate),
      (error) => error.code === "SUMMARY_IDENTITY_INVALID" &&
        error.path === "$.artifactIdentity" &&
        error.rule === "evidence-root-artifact-identity",
    );
  }
});
test("A RED direct schema rejects every missing required and extra field", () => {
  const requiredByKind = {
    root: ["schemaVersion", "releaseGate", "issue", "status", "rawInvocationStored", "redactionPolicyId"],
    artifactIdentity: ["gitSha", "versionCode", "androidApplicationId", "dataPackManifestSha256"],
    evidence: ["evidenceId", "result", "localEvidencePath", "artifactIdentitySha256"], matrix: ["matrixId", "result", "findingCounts", "cases"],
    findingCounts: ["critical", "high", "medium", "low"],
    mediumFindingDisposition: ["ownerAlias", "fixPlanEvidencePath"],
    case: ["procedureId", "targetAlias", "expectedStatus", "observedStatus", "redactionResult", "localEvidencePath", "artifactIdentitySha256"],
  };
  for (const [kind, fields] of Object.entries(requiredByKind)) {
    for (const field of fields) {
      const summary = schemaV2Pass(); delete schemaV2Nodes(summary)[kind][field];
      assert.equal(schemaV2Validate(summary).ok, false, `${kind}.${field} missing`);
    }
    const summary = schemaV2Pass(); schemaV2Nodes(summary)[kind].unexpectedField = 1;
    assert.equal(schemaV2Validate(summary).ok, false, `${kind} extra`);
  }
});

test("A RED direct schema rejects every wrong declared type", () => {
  const wrong = { string: 1, integer: "1", boolean: "false", object: [], array: {} };
  for (const [kind, fields] of Object.entries(gate.summaryContract.fieldTypes)) {
    for (const [field, type] of Object.entries(fields)) {
      const summary = schemaV2Pass();
      schemaV2Nodes(summary)[kind][field] = structuredClone(wrong[type]);
      assert.equal(schemaV2Validate(summary).ok, false, `${kind}.${field}:${type}`);
    }
  }
});

test("A RED direct schema enforces every enum const count and relative path", () => {
  const mutations = [
    (s) => { s.schemaVersion = 3; }, (s) => { s.releaseGate = "other"; }, (s) => { s.issue = -1; },
    (s) => { s.status = "UNKNOWN"; }, (s) => { s.rawInvocationStored = true; },
    (s) => { s.redactionPolicyId = "unknown-policy"; }, (s) => { s.evidence[0].evidenceId = "unknown-evidence"; },
    (s) => { s.evidence[0].result = "UNKNOWN"; }, (s) => { s.productionLikeEvidence[0].evidenceId = "unknown-evidence"; },
    (s) => { s.matrices[0].matrixId = "unknownMatrix"; }, (s) => { s.matrices[0].result = "UNKNOWN"; },
    (s) => { s.matrices[0].cases[0].procedureId = "unknown.procedure"; },
    (s) => { s.matrices[0].cases[0].targetAlias = "target.unknown"; },
    (s) => { s.matrices[0].cases[0].redactionResult = "UNKNOWN"; },
    (s) => { s.matrices[0].findingCounts.high = -1; }, (s) => { s.matrices[0].findingCounts.high = 0.5; },
    (s) => { s.matrices[0].findingCounts.high = "0"; },
  ];
  for (const mutate of mutations) { const summary = schemaV2Pass(); mutate(summary); assert.equal(schemaV2Validate(summary).ok, false); }
  const badPaths = ["/absolute", "../parent", "scheme:path", "with?query", "with#fragment", "with space"];
  for (const value of badPaths) { const summary = schemaV2Pass(); summary.evidence[0].localEvidencePath = value; assert.equal(schemaV2Validate(summary).ok, false); }
  const allowed = new Set(["$id", "additionalProperties", "const", "enum", "items", "minimum", "pattern", "properties", "required", "type"]);
  const visit = (schema) => {
    for (const key of Object.keys(schema)) assert.equal(allowed.has(key), true, key);
    for (const child of Object.values(schema.properties ?? {})) visit(child);
    if (schema.items) visit(schema.items);
  };
  visit(buildAbusePenetrationSummaryV2Schema(gate));
});

test("A RED direct schema enforces deterministic identity patterns", () => {
  assert.equal(schemaV2Validate(schemaV2Pass()).ok, true);
  const fields = ["gitSha", "androidApplicationId", "dataPackManifestSha256", "aabSha256", "generatedApkSha256", "backendImageDigest", "backendArtifactSha256"];
  const invalid = {
    gitSha: "a".repeat(39), androidApplicationId: "com.example.app", dataPackManifestSha256: "b".repeat(63),
    aabSha256: "c".repeat(63), generatedApkSha256: "d".repeat(65),
    backendImageDigest: `sha256:${"g".repeat(64)}`, backendArtifactSha256: "f".repeat(63),
  };
  for (const field of fields) {
    const summary = schemaV2Pass(); summary.artifactIdentity[field] = invalid[field];
    assert.equal(schemaV2Validate(summary).ok, false, field);
  }
});

test("A RED direct schema normalizes invalid production evidence policy", () => {
  for (const policy of [undefined, []]) {
    const gateValue = structuredClone(gate);
    if (policy === undefined) delete gateValue.productionLikeEvidencePolicy;
    else gateValue.productionLikeEvidencePolicy = policy;
    assert.throws(
      () => deriveSummaryCatalog(gateValue),
      (error) => {
        assert.equal(error.message, "GATE_SUMMARY_CONTRACT_INVALID at $ (production-evidence-policy)");
        assert.doesNotMatch(error.message, /Cannot read|is not iterable|is not a function/);
        return true;
      },
    );
  }
});

test("A RED direct schema normalizes invalid relative evidence path pattern", () => {
  const gateValue = structuredClone(gate);
  gateValue.summaryContract.relativeEvidencePathPattern = "[";
  assert.throws(
    () => buildAbusePenetrationSummaryV2Schema(gateValue),
    (error) => {
      assert.equal(error.message, "GATE_SUMMARY_CONTRACT_INVALID at $ (relative-evidence-path-pattern)");
      assert.doesNotMatch(error.message, /Invalid regular expression/);
      return true;
    },
  );
});

test("A RED direct schema requires valid release gate identity", () => {
  const cases = [
    [(value) => { delete value.releaseGate; }, "release-gate"],
    [(value) => { value.releaseGate = 1; }, "release-gate"],
    [(value) => { value.releaseGate = ""; }, "release-gate"],
    [(value) => { delete value.issue; }, "issue"],
    [(value) => { value.issue = "1"; }, "issue"],
    [(value) => { value.issue = 0; }, "issue"],
    [(value) => { value.issue = -1; }, "issue"],
  ];
  for (const [mutate, rule] of cases) {
    const gateValue = structuredClone(gate); mutate(gateValue);
    assert.throws(() => buildAbusePenetrationSummaryV2Schema(gateValue),
      new RegExp(`GATE_SUMMARY_CONTRACT_INVALID at \\$ \\(${rule}\\)`));
  }
});

const gateHashBefore = createHash("sha256").update(readFileSync(gatePath)).digest("hex");
const statusBefore = execFileSync("git", ["status", "--short", "--untracked-files=all"], { cwd: root, encoding: "utf8" });
function freshV2Pass(gateValue = gate) { return structuredClone(schemaV2Pass(gateValue, false)); }
function rebindEvidenceToIdentity(summary) {
  const digest = schemaV2IdentitySha256(summary.artifactIdentity);
  for (const item of [...summary.evidence, ...summary.productionLikeEvidence]) item.artifactIdentitySha256 = digest;
  for (const matrix of summary.matrices) {
    for (const caseItem of matrix.cases) caseItem.artifactIdentitySha256 = digest;
  }
}
function v1Minimal(status = "BLOCKED_EXTERNAL") {
  return { schemaVersion: 1, releaseGate: gate.releaseGate, issue: gate.issue, status };
}
function v1Optional() {
  const matrix = gate.rehearsalMatrices.adCounterInflation;
  const v1Evidence = schemaV2Evidence(gate.productionLikeEvidencePolicy.requiredForClosing[0]);
  delete v1Evidence.artifactIdentitySha256;
  return Object.assign(v1Minimal("FAIL"), {
    artifactIdentity: structuredClone(schemaV2Identity),
    productionLikeEvidence: [v1Evidence],
    matrices: [{ matrixId: "adCounterInflation", scenarioId: matrix.scenarioId,
      artifactIdentity: structuredClone(schemaV2Identity), commandOrManualCheck: "sanitized local rehearsal",
      findingCounts: { critical: 0, high: 0, medium: 0, low: 0 }, result: "FAIL",
      redactionNotes: "sanitized values removed", localEvidencePath: schemaV2EvidencePath,
      requiredEvidence: [matrix.requiredEvidence[0]],
      cases: [{ caseId: matrix.requiredCases[0], expectedStatus: matrix.expectedStatusByCase[matrix.requiredCases[0]][0],
        endpoint: "/sanitized-relative-route", commandOrManualCheck: "sanitized local rehearsal",
        observedStatus: -1, redactionResult: "FAIL", localEvidencePath: schemaV2EvidencePath }],
    }],
  });
}
async function runSummary(t, summary, { requirePass = false, gateValue = gate, rawSummary } = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), "abuse-summary-")); t.after(() => rm(dir, { recursive: true, force: true }));
  const summaryPath = path.join(dir, "summary.json"); const localGatePath = path.join(dir, "gate.json");
  await writeFile(summaryPath, rawSummary ?? `${JSON.stringify(summary, null, 2)}\n`);
  await writeFile(localGatePath, `${JSON.stringify(gateValue, null, 2)}\n`);
  const args = ["tools/security/validate-abuse-penetration-summary.mjs", "--summary", summaryPath, "--gate", localGatePath];
  if (requirePass) args.push("--require-pass");
  return execFileAsync(process.execPath, args, { cwd: root });
}
async function rejectSummary(t, summary, mutate, code, options = {}) {
  const value = structuredClone(summary); mutate(value);
  await assert.rejects(runSummary(t, value, options), new RegExp(code));
}
const joined = (parts) => parts.join("");
test.after(() => {
  assert.equal(createHash("sha256").update(readFileSync(gatePath)).digest("hex"), gateHashBefore);
  assert.equal(execFileSync("git", ["status", "--short", "--untracked-files=all"], { cwd: root, encoding: "utf8" }), statusBefore);
});

test("B RED envelope rejects missing string and unsupported versions and v2 status drift", async (t) => {
  for (const version of [0, 3]) await rejectSummary(t, schemaV2Blocked(), (s) => { s.schemaVersion = version; }, "SUMMARY_VERSION_UNSUPPORTED");
  await rejectSummary(t, schemaV2Blocked(), (s) => { delete s.schemaVersion; }, "SUMMARY_VERSION_UNSUPPORTED");
  await rejectSummary(t, schemaV2Blocked(), (s) => { s.schemaVersion = "2"; }, "SUMMARY_VERSION_UNSUPPORTED");
  await rejectSummary(t, schemaV2Blocked(), (s) => { s.status = "UNKNOWN"; }, "SUMMARY_V2_SCHEMA_INVALID");
  await rejectSummary(t, schemaV2Blocked(), (s) => { delete s.status; }, "SUMMARY_V2_SCHEMA_INVALID");
});
test("B RED v1 non-PASS accepts minimal and valid optional containers", async (t) => {
  await runSummary(t, v1Minimal("FAIL")); await runSummary(t, v1Minimal("BLOCKED_EXTERNAL")); await runSummary(t, v1Optional());
});
test("B RED v1 malformed optional containers fail closed before iteration", async (t) => {
  const mutations = [
    (s) => { s.artifactIdentity = []; },
    (s) => { s.productionLikeEvidence = {}; },
    (s) => { s.matrices = {}; },
    (s) => { s.matrices[0].artifactIdentity = []; },
    (s) => { s.matrices[0].findingCounts = []; },
    (s) => { s.matrices[0].requiredEvidence = {}; },
    (s) => { s.matrices[0].cases = {}; },
    (s) => { s.matrices[0].cases[0] = []; },
  ];
  for (const mutate of mutations) await rejectSummary(t, v1Optional(), mutate, "SUMMARY_V1_SCHEMA_INVALID");
});
test("B RED v1 matrix identity scenario types ids and result values are controlled", async (t) => {
  const mutations = [
    [(s) => { s.matrices[0].result = 1; }, "SUMMARY_V1_SCHEMA_INVALID"],
    [(s) => { s.matrices[0].result = "UNKNOWN"; }, "SUMMARY_V1_SCHEMA_INVALID"],
    [(s) => { s.matrices[0].scenarioId = "wrong-scenario"; }, "SUMMARY_PROCEDURE_MAPPING_INVALID"],
    [(s) => { delete s.matrices[0].artifactIdentity.gitSha; }, "SUMMARY_V1_SCHEMA_INVALID"],
    [(s) => { delete s.matrices[0].artifactIdentity.aabSha256; delete s.matrices[0].artifactIdentity.generatedApkSha256; }, "SUMMARY_IDENTITY_INVALID"],
    [(s) => { delete s.matrices[0].artifactIdentity.backendImageDigest; delete s.matrices[0].artifactIdentity.backendArtifactSha256; }, "SUMMARY_IDENTITY_INVALID"],
    [(s) => { s.matrices[0].findingCounts.high = "0"; }, "SUMMARY_V1_SCHEMA_INVALID"],
    [(s) => { s.matrices[0].requiredEvidence[0] = "unknown-evidence"; }, "SUMMARY_ID_SET_MISMATCH"],
    [(s) => { s.matrices[0].cases[0].expectedStatus = -2; }, "SUMMARY_PROCEDURE_MAPPING_INVALID"],
    [(s) => { s.matrices[0].cases[0].redactionResult = "UNKNOWN"; }, "SUMMARY_V1_SCHEMA_INVALID"],
  ];
  for (const [mutate, code] of mutations) await rejectSummary(t, v1Optional(), mutate, code);
});
test("B RED v1 disposition is shape-checked independently and is not stale at medium zero", async (t) => {
  const disposition = { owner: "security-owner", fixPlan: "sanitized local remediation" };
  const noCounts = v1Optional(); delete noCounts.matrices[0].findingCounts;
  noCounts.matrices[0].mediumFindingDisposition = structuredClone(disposition); await runSummary(t, noCounts);
  await rejectSummary(t, noCounts, (s) => { s.matrices[0].mediumFindingDisposition.owner = 1; }, "SUMMARY_V1_SCHEMA_INVALID");
  await rejectSummary(t, noCounts, (s) => { delete s.matrices[0].mediumFindingDisposition.fixPlan; }, "SUMMARY_V1_SCHEMA_INVALID");
  const mediumZero = v1Optional(); mediumZero.matrices[0].mediumFindingDisposition = structuredClone(disposition);
  await runSummary(t, mediumZero);
  await rejectSummary(t, v1Optional(), (s) => { s.matrices[0].findingCounts.medium = 1; }, "SUMMARY_FINDING_POLICY_INVALID");
  const mediumPositive = v1Optional(); mediumPositive.matrices[0].findingCounts.medium = 1;
  mediumPositive.matrices[0].mediumFindingDisposition = structuredClone(disposition); await runSummary(t, mediumPositive);
  await rejectSummary(t, mediumPositive, (s) => { s.matrices[0].mediumFindingDisposition.fixPlan = []; }, "SUMMARY_V1_SCHEMA_INVALID");
});
test("B RED v1 PASS and require-pass are always rejected", async (t) => {
  const pass = v1Optional(); pass.status = "PASS";
  await assert.rejects(runSummary(t, pass), /SUMMARY_VERSION_UNSUPPORTED/);
  for (const status of ["PASS", "FAIL", "BLOCKED_EXTERNAL"]) {
    const summary = v1Optional(); summary.status = status;
    await assert.rejects(runSummary(t, summary, { requirePass: true }), /SUMMARY_VERSION_UNSUPPORTED/);
  }
});

test("B RED v2 PASS collections reject missing extra and duplicate", async (t) => {
  const collections = [
    ["matrices", (s) => s.matrices, { matrixId: "unknownMatrix" }],
    ["cases", (s) => s.matrices[0].cases, { procedureId: "unknown.procedure", targetAlias: "target.unknown", expectedStatus: 0, observedStatus: 0, redactionResult: "PASS", localEvidencePath: schemaV2EvidencePath }],
    ["evidence", (s) => s.evidence, schemaV2Evidence("unknown-evidence")],
    ["production", (s) => s.productionLikeEvidence, schemaV2Evidence("unknown-evidence")],
  ];
  for (const [name, select, extra] of collections) {
    await rejectSummary(t, freshV2Pass(), (s) => { select(s).pop(); }, "SUMMARY_ID_SET_MISMATCH", { requirePass: true });
    await rejectSummary(t, freshV2Pass(), (s) => { select(s).push(structuredClone(extra)); }, "SUMMARY_V2_SCHEMA_INVALID", { requirePass: true });
    await rejectSummary(t, freshV2Pass(), (s) => { select(s).push(structuredClone(select(s)[0])); }, "SUMMARY_ID_SET_MISMATCH", { requirePass: true });
    assert.equal(typeof name, "string");
  }
});
test("B RED moved procedure reports mapping error before exact-set error", async (t) => {
  await rejectSummary(t, freshV2Pass(), (s) => s.matrices[1].cases.push(s.matrices[0].cases.pop()), "SUMMARY_PROCEDURE_MAPPING_INVALID", { requirePass: true });
});

test("B RED v2 procedure target expected and observed status mapping is exact", async (t) => {
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].cases[0].targetAlias = `target.${s.matrices[1].matrixId}`; }, "SUMMARY_PROCEDURE_MAPPING_INVALID", { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].cases[0].expectedStatus = 599; }, "SUMMARY_PROCEDURE_MAPPING_INVALID", { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].cases[0].observedStatus = -1; }, "SUMMARY_PROCEDURE_MAPPING_INVALID", { requirePass: true });
  const failed = schemaV2Blocked(gate, "FAIL"); failed.artifactIdentity = structuredClone(schemaV2Identity);
  failed.matrices = [freshV2Pass().matrices[0]];
  failed.matrices[0].result = "FAIL"; failed.matrices[0].cases[0].observedStatus = -1;
  await runSummary(t, failed);
});
test("B RED v2 identity base fields and digest groups are exact", async (t) => {
  for (const field of ["gitSha", "versionCode", "androidApplicationId", "dataPackManifestSha256"])
    await rejectSummary(t, freshV2Pass(), (s) => { delete s.artifactIdentity[field]; }, "SUMMARY_V2_SCHEMA_INVALID", { requirePass: true });
  const oneEach = freshV2Pass(); delete oneEach.artifactIdentity.generatedApkSha256; delete oneEach.artifactIdentity.backendImageDigest;
  rebindEvidenceToIdentity(oneEach);
  await runSummary(t, oneEach, { requirePass: true });
  const bothEach = freshV2Pass(); await runSummary(t, bothEach, { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { delete s.artifactIdentity.aabSha256; delete s.artifactIdentity.generatedApkSha256; }, "SUMMARY_IDENTITY_INVALID", { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { delete s.artifactIdentity.backendImageDigest; delete s.artifactIdentity.backendArtifactSha256; }, "SUMMARY_IDENTITY_INVALID", { requirePass: true });
});
test("B RED v2 finding policy rejects both high limits medium omission wrong owner invalid path and stale disposition", async (t) => {
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].findingCounts.critical = 1; }, "SUMMARY_FINDING_POLICY_INVALID", { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].findingCounts.high = 1; }, "SUMMARY_FINDING_POLICY_INVALID", { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].findingCounts.medium = 1; delete s.matrices[0].mediumFindingDisposition; }, "SUMMARY_FINDING_POLICY_INVALID", { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].findingCounts.medium = 1; s.matrices[0].mediumFindingDisposition = { ownerAlias: `owner.${s.matrices[1].matrixId}`, fixPlanEvidencePath: schemaV2EvidencePath }; }, "SUMMARY_FINDING_POLICY_INVALID", { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].findingCounts.medium = 1; s.matrices[0].mediumFindingDisposition = { ownerAlias: `owner.${s.matrices[0].matrixId}`, fixPlanEvidencePath: "../outside" }; }, "SUMMARY_V2_SCHEMA_INVALID", { requirePass: true });
  await rejectSummary(t, freshV2Pass(), (s) => { s.matrices[0].mediumFindingDisposition = { ownerAlias: `owner.${s.matrices[0].matrixId}`, fixPlanEvidencePath: schemaV2EvidencePath }; }, "SUMMARY_FINDING_POLICY_INVALID", { requirePass: true });
});

test("B RED v2 accepts cross-matrix shared root evidence and unordered arrays", async (t) => {
  const syntheticGate = structuredClone(gate);
  const ids = Object.keys(syntheticGate.rehearsalMatrices); const shared = syntheticGate.rehearsalMatrices[ids[0]].requiredEvidence[0];
  if (!syntheticGate.rehearsalMatrices[ids[1]].requiredEvidence.includes(shared)) syntheticGate.rehearsalMatrices[ids[1]].requiredEvidence.push(shared);
  const summary = freshV2Pass(syntheticGate); summary.matrices.reverse(); summary.evidence.reverse();
  assert.equal(summary.evidence.filter((item) => item.evidenceId === shared).length, 1);
  await runSummary(t, summary, { requirePass: true, gateValue: syntheticGate });
});
test("B RED v2 FAIL allows observed mismatch and BLOCKED allows omissions", async (t) => {
  const failed = schemaV2Blocked(gate, "FAIL"); failed.artifactIdentity = structuredClone(schemaV2Identity);
  failed.matrices = [freshV2Pass().matrices[0]];
  failed.matrices[0].result = "FAIL"; failed.matrices[0].cases[0].observedStatus = -1; await runSummary(t, failed);
  await runSummary(t, schemaV2Blocked());
  await assert.rejects(runSummary(t, failed, { requirePass: true }), /SUMMARY_REQUIRE_PASS_FAILED/);
});
test("B RED BLOCKED rejects fake unknown matrix procedure and evidence entries", async (t) => {
  const unknownMatrix = schemaV2Blocked(); unknownMatrix.matrices = [{ matrixId: "unknownMatrix", result: "BLOCKED_EXTERNAL", findingCounts: { critical: 0, high: 0, medium: 0, low: 0 }, cases: [] }];
  await assert.rejects(runSummary(t, unknownMatrix), /SUMMARY_V2_SCHEMA_INVALID/);
  const unknownProcedure = schemaV2Blocked(); unknownProcedure.matrices = [freshV2Pass().matrices[0]];
  unknownProcedure.matrices[0].result = "BLOCKED_EXTERNAL"; unknownProcedure.matrices[0].cases[0].procedureId = "unknown.procedure";
  await assert.rejects(runSummary(t, unknownProcedure), /SUMMARY_V2_SCHEMA_INVALID/);
  const unknownEvidence = schemaV2Blocked(); unknownEvidence.evidence = [schemaV2Evidence("unknown-evidence")];
  await assert.rejects(runSummary(t, unknownEvidence), /SUMMARY_V2_SCHEMA_INVALID/);
});

test("B RED v2 structurally rejects legacy raw command endpoint and redaction keys", async (t) => {
  for (const field of ["commandOrManualCheck", "endpoint", "redactionNotes"]) {
    await rejectSummary(t, schemaV2Blocked(), (s) => { s[field] = "sanitized"; }, "SUMMARY_V2_SCHEMA_INVALID");
  }
});
test("B RED privacy rejects network credential identifier command and normalized bypasses without echo", async (t) => {
  const ipv4 = [["19", "2"], ["0"], ["2"], ["4", "2"]].map((parts) => parts.join("")).join(".");
  const ipv6 = [["20", "01"], ":", ["db", "8"], "::", ["4", "2"]].flat().join("");
  const alternateHex = ["0x", "c0", "00", "02", "2a"].join("");
  const alternateDecimal = ["322", "122", "602", "6"].join("");
  const legacyAuthority = joined(["synthetic", "-endpoint", ".invalid", ":", ["8", "443"].join(""), "/evidence"]);
  const matrixForbiddenMarker = gate.rehearsalMatrices.adCounterInflation.forbiddenSummaryValues[0];
  const values = [
    joined(["Ht", "Tp", ":", "//", "invalid", ".example"]),
    joined(["Auth", "ori", "zation", " : Bear", "er synthetic"]),
    joined(["Co", "okie", " = synthetic"]),
    joined(["re", "quest", " ", "i", "d", " synthetic"]),
    joined(["de", "vice", "_", "identi", "fier", " synthetic"]),
    joined(["cu", " ", "rl", " ", "--fail"]),
    joined(["po", "wer", "shell", " ", "-Command", " synthetic"]),
    joined(["ku", "bectl", " ", "get", " synthetic"]),
    joined(["Us", "er", " - ", "Ag", "ent", " : synthetic"]),
    joined(["Synthetic", "User", "Agent", "Probe"]),
    joined(["Synthetic", "Client", "/", ["5", "0"].join("."), " (sanitized)"]),
    joined(["%", "68", "%", "74", "%", "74", "%", "70", "%", "3a", "%", "2f", "%", "2f", "invalid.example"]),
    joined(["ht", "tp", "%", "3a", "%", "2f", "%", "2f", "invalid.example"]),
    joined([ipv4, ":", ["8", "443"].join(""), "/evidence"]),
    joined([ipv4, "/evidence"]),
    joined(["[", ipv6, "]:", ["4", "43"].join(""), "/evidence"]),
    joined([alternateHex, ":", ["8", "443"].join(""), "/evidence"]),
    alternateDecimal,
    legacyAuthority,
    matrixForbiddenMarker,
  ];
  for (const value of values) {
    const summary = v1Optional(); summary.matrices[0].commandOrManualCheck = value;
    await assert.rejects(runSummary(t, summary), (error) => {
      assert.match(error.stderr, /SUMMARY_PRIVACY_VIOLATION/);
      assert.equal(error.stderr.includes(value), false); return true;
    });
  }
});
test("B RED privacy allows every controlled id and approved relative evidence path", async (t) => {
  await runSummary(t, schemaV2Blocked());
  await runSummary(t, freshV2Pass(), { requirePass: true });
  const encodedBenign = Buffer.from(["sanitized", " normal", " evidence"].join(""), "utf8").toString("base64url");
  const encodedSummary = freshV2Pass();
  encodedSummary.evidence[0].localEvidencePath = `${schemaV2EvidenceRoot}/${encodedBenign}.json`;
  await runSummary(t, encodedSummary, { requirePass: true });
  const legacy = v1Optional(); legacy.matrices[0].commandOrManualCheck = "sanitized local rehearsal";
  legacy.matrices[0].redactionNotes = "sanitized values removed";
  legacy.matrices[0].cases[0].endpoint = "/sanitized-relative-route";
  legacy.matrices[0].cases[0].commandOrManualCheck = joined(["synthetic", ".invalid"]);
  legacy.matrices[0].redactionNotes = joined(["user", " agent", " fields removed"]);
  await runSummary(t, legacy);
});

test("B RED privacy rejects canonical Base64url sensitive evidence-path segments without echo", async (t) => {
  const sensitive = ["ht", "tp", ":", "//", "19", "2", ".0.2.42"].join("");
  const encoded = Buffer.from(sensitive, "utf8").toString("base64url");
  const summary = freshV2Pass();
  summary.evidence[0].localEvidencePath = `${schemaV2EvidenceRoot}/${encoded}.json`;
  await assert.rejects(runSummary(t, summary, { requirePass: true }), (error) => {
    assert.match(error.stderr, /SUMMARY_PRIVACY_VIOLATION/);
    assert.equal(error.stderr.includes(sensitive), false);
    return true;
  });
});

test("B RED privacy rejects canonical standard Base64 split across allowed evidence-path segments without echo", async (t) => {
  const sensitive = ["ht", "tp", ":", "//", "19", "2", ".0.2.42", "/", "x"].join("");
  const prefix = String.fromCodePoint(0x01df, 0x3448);
  const encoded = Buffer.from(`${prefix}${sensitive}`, "utf8").toString("base64");
  assert.equal(encoded.includes("/"), true);
  const summary = freshV2Pass();
  summary.evidence[0].localEvidencePath = `${schemaV2EvidenceRoot}/${encoded}.json`;
  await assert.rejects(runSummary(t, summary, { requirePass: true }), (error) => {
    assert.match(error.stderr, /SUMMARY_PRIVACY_VIOLATION/);
    assert.equal(error.stderr.includes(sensitive), false);
    return true;
  });
});

test("B RED privacy allows benign multi-segment evidence paths", async (t) => {
  const summary = freshV2Pass();
  summary.evidence[0].localEvidencePath = `${schemaV2EvidenceRoot}/nested/redacted.json`;
  await runSummary(t, summary, { requirePass: true });
});

test("B RED privacy rejects a sixth nested Base64url network-shaped evidence segment without echo", async (t) => {
  const sensitive = ["ht", "tp", ":", "//", "19", "2", ".0.2.42"].join("");
  const encoded = Array.from({ length: 6 }, (_, index) => index)
    .reduce((value) => Buffer.from(value, "utf8").toString("base64url"), sensitive);
  const summary = freshV2Pass();
  summary.evidence[0].localEvidencePath = `${schemaV2EvidenceRoot}/${encoded}.json`;
  await assert.rejects(runSummary(t, summary, { requirePass: true }), (error) => {
    assert.match(error.stderr, /SUMMARY_PRIVACY_VIOLATION/);
    assert.equal(error.stderr.includes(sensitive), false);
    return true;
  });
});

test("B RED privacy rejects an allowed evidence path exceeding the UTF-8 byte bound without echo", async (t) => {
  const oversized = "a".repeat(4097);
  const summary = freshV2Pass();
  summary.evidence[0].localEvidencePath = `${schemaV2EvidenceRoot}/${oversized}.json`;
  await assert.rejects(runSummary(t, summary, { requirePass: true }), (error) => {
    assert.match(error.stderr, /SUMMARY_PRIVACY_VIOLATION/);
    assert.equal(error.stderr.includes(oversized), false);
    return true;
  });
});

test("B RED privacy allows a benign Base64url value decoded at the maximum decode depth", async (t) => {
  const encoded = Array.from({ length: 8 }, (_, index) => index)
    .reduce((value) => Buffer.from(value, "utf8").toString("base64url"), "sanitized evidence");
  const summary = freshV2Pass();
  summary.evidence[0].localEvidencePath = `${schemaV2EvidenceRoot}/${encoded}.json`;
  await runSummary(t, summary, { requirePass: true });
});

test("B RED direct validator rejects prototype-inherited v2 summaries as schema-invalid", () => {
  const inherited = freshV2Pass();
  const summary = Object.create(inherited);
  assert.throws(() => validateAbusePenetrationSummary(summary, gate, { requirePass: true }), (error) => {
    assert.match(error.message, /SUMMARY_V2_SCHEMA_INVALID/);
    assert.match(error.message, /json-like/);
    return true;
  });
});

test("B RED v1 matrix item shape is rejected before controlled ID collection", async (t) => {
  const summary = v1Minimal("FAIL");
  summary.matrices = [[]];
  await assert.rejects(runSummary(t, summary), (error) => {
    assert.match(error.stderr, /SUMMARY_V1_SCHEMA_INVALID/);
    assert.doesNotMatch(error.stderr, /SUMMARY_ID_SET_MISMATCH/);
    return true;
  });
});

test("B RED CLI handles defaults explicit gate files malformed JSON and options", async (t) => {
  await runSummary(t, freshV2Pass(), { requirePass: true });
  const dir = await mkdtemp(path.join(tmpdir(), "abuse-cli-")); t.after(() => rm(dir, { recursive: true, force: true }));
  const valid = path.join(dir, "valid.json"); const malformed = path.join(dir, "malformed.json");
  await writeFile(valid, `${JSON.stringify(schemaV2Blocked(), null, 2)}\n`); await writeFile(malformed, "{");
  const cli = "tools/security/validate-abuse-penetration-summary.mjs";
  await execFileAsync(process.execPath, [cli, "--summary", valid], { cwd: root });
  await execFileAsync(process.execPath, [cli, "--summary", valid, "--gate", gatePath], { cwd: root });
  const failures = [
    [[cli], /SUMMARY_CLI_INVALID/], [[cli, "--unknown"], /SUMMARY_CLI_INVALID/],
    [[cli, "--summary"], /SUMMARY_CLI_INVALID/], [[cli, "--summary", valid, "--summary", valid], /SUMMARY_CLI_INVALID/],
    [[cli, "--summary", path.join(dir, "missing.json")], /SUMMARY_INPUT_READ_FAILED/],
    [[cli, "--summary", malformed], /SUMMARY_JSON_INVALID/],
    [[cli, "--summary", valid, "--gate", path.join(dir, "missing-gate.json")], /SUMMARY_INPUT_READ_FAILED/],
    [[cli, "--summary", valid, "--gate", malformed], /SUMMARY_JSON_INVALID/],
  ];
  for (const [args, pattern] of failures) await assert.rejects(execFileAsync(process.execPath, args, { cwd: root }), pattern);
});
test("B RED CLI require-pass accepts only complete v2 PASS", async (t) => {
  await runSummary(t, freshV2Pass(), { requirePass: true });
  for (const status of ["FAIL", "BLOCKED_EXTERNAL"]) {
    await assert.rejects(runSummary(t, schemaV2Blocked(gate, status), { requirePass: true }), /SUMMARY_REQUIRE_PASS_FAILED/);
  }
  const incompletePass = schemaV2Blocked(gate, "PASS");
  await assert.rejects(runSummary(t, incompletePass, { requirePass: true }), /SUMMARY_ID_SET_MISMATCH/);
  for (const status of ["PASS", "FAIL", "BLOCKED_EXTERNAL"]) {
    const legacy = v1Optional(); legacy.status = status;
    await assert.rejects(runSummary(t, legacy, { requirePass: true }), /SUMMARY_VERSION_UNSUPPORTED/);
  }
});
