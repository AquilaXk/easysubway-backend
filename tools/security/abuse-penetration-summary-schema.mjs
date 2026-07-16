const TYPE_TOKENS = new Set(["string", "integer", "boolean", "object", "array"]);
export const SUMMARY_V2_OBJECT_KINDS = Object.freeze([
  "root", "artifactIdentity", "evidence", "matrix", "findingCounts", "mediumFindingDisposition", "case",
]);
const REQUIRED_KINDS = Object.freeze([
  "rootForAllStatuses", "rootAdditionalForPass", "artifactIdentity", "evidence", "matrix",
  "findingCounts", "mediumFindingDisposition", "case",
]);
function invalidGate(rule) { throw new Error(`GATE_SUMMARY_CONTRACT_INVALID at $ (${rule})`); }
function objectValue(value, rule) {
  if (!value || typeof value !== "object" || Array.isArray(value)) invalidGate(rule);
  return value;
}
function sameStrings(actual, expected) {
  return actual.length === expected.length && actual.every((value, index) => value === expected[index]);
}
function compareCodePoints(left, right) {
  const a = Array.from(left, (value) => value.codePointAt(0));
  const b = Array.from(right, (value) => value.codePointAt(0));
  for (let index = 0; index < Math.min(a.length, b.length); index += 1) if (a[index] !== b[index]) return a[index] - b[index];
  return a.length - b.length;
}
function sorted(values) { return Array.from(values).sort(compareCodePoints); }
function uniqueStrings(value, rule) {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string") || new Set(value).size !== value.length) invalidGate(rule);
  return value;
}
function uniqueIntegers(value, rule) {
  if (!Array.isArray(value) || value.some((item) => !Number.isInteger(item)) || new Set(value).size !== value.length) invalidGate(rule);
  return value;
}
function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  for (const child of Object.values(value)) deepFreeze(child);
  return Object.freeze(value);
}
function validateContract(gate) {
  objectValue(gate, "gate");
  if (typeof gate.releaseGate !== "string" || gate.releaseGate.length === 0) invalidGate("release-gate");
  if (!Number.isInteger(gate.issue) || gate.issue <= 0) invalidGate("issue");
  const contract = objectValue(gate.summaryContract, "summary-contract");
  if (!Number.isInteger(contract.currentVersion) || !Number.isInteger(contract.requirePassVersion)) invalidGate("versions");
  const legacyVersions = uniqueIntegers(contract.legacyNonPassVersions, "legacy-versions");
  if (legacyVersions.includes(contract.currentVersion) || legacyVersions.includes(contract.requirePassVersion)) invalidGate("legacy-version-overlap");
  uniqueStrings(contract.statusValues, "status-values"); uniqueStrings(contract.resultValues, "result-values");
  uniqueStrings(contract.redactionResultValues, "redaction-result-values");
  uniqueStrings(contract.redactionPolicyIds, "redaction-policy-ids");
  if (typeof contract.rawInvocationStored !== "boolean" || typeof contract.relativeEvidencePathPattern !== "string") invalidGate("scalar-contract");
  try { new RegExp(contract.relativeEvidencePathPattern); } catch { invalidGate("relative-evidence-path-pattern"); }
  for (const field of ["procedureIdDerivation", "targetAliasDerivation", "ownerAliasDerivation"]) if (typeof contract[field] !== "string") invalidGate("derivation");
  const fieldTypes = objectValue(contract.fieldTypes, "field-types");
  if (!sameStrings(Object.keys(fieldTypes), SUMMARY_V2_OBJECT_KINDS)) invalidGate("field-type-kinds");
  for (const [kind, fields] of Object.entries(fieldTypes)) {
    objectValue(fields, `field-types-${kind}`);
    for (const type of Object.values(fields)) if (!TYPE_TOKENS.has(type)) invalidGate("type-token");
  }
  const required = objectValue(contract.requiredFields, "required-fields");
  if (!sameStrings(Object.keys(required), REQUIRED_KINDS)) invalidGate("required-kinds");
  for (const [requiredKind, fields] of Object.entries(required)) {
    uniqueStrings(fields, `required-${requiredKind}`);
    const objectKind = requiredKind.startsWith("root") ? "root" : requiredKind;
    for (const field of fields) if (!(field in fieldTypes[objectKind])) invalidGate("required-field-reference");
  }
  for (const [kind, patterns] of Object.entries(objectValue(contract.fieldPatterns, "field-patterns"))) {
    if (!(kind in fieldTypes)) invalidGate("pattern-kind"); objectValue(patterns, "pattern-fields");
    for (const [field, pattern] of Object.entries(patterns)) {
      if (fieldTypes[kind][field] !== "string" || typeof pattern !== "string") invalidGate("pattern-field");
      try { new RegExp(pattern); } catch { invalidGate("pattern-regexp"); }
    }
  }
  return contract;
}

const DERIVATION_RESOLVERS = Object.freeze({
  procedureIdDerivation: new Map([["matrixId + '.' + caseId", (matrixId, caseId) => `${matrixId}.${caseId}`]]),
  targetAliasDerivation: new Map([["'target.' + matrixId", (matrixId) => `target.${matrixId}`]]),
  ownerAliasDerivation: new Map([["'owner.' + matrixId", (matrixId) => `owner.${matrixId}`]]),
});
function resolveDerivation(contract, field) {
  const resolver = DERIVATION_RESOLVERS[field].get(contract[field]);
  if (!resolver) invalidGate(`unsupported-${field}`);
  return resolver;
}
export function deriveSummaryCatalog(gate) {
  const contract = validateContract(gate); const matrices = objectValue(gate.rehearsalMatrices, "matrices");
  const procedureFor = resolveDerivation(contract, "procedureIdDerivation");
  const targetFor = resolveDerivation(contract, "targetAliasDerivation");
  const ownerFor = resolveDerivation(contract, "ownerAliasDerivation");
  const matrixIds = sorted(Object.keys(matrices)); if (matrixIds.length === 0) invalidGate("matrix-empty");
  const procedureIds = []; const targetAliases = []; const ownerAliases = []; const matrixEvidence = new Set();
  const procedureById = {}; const evidenceIdsByMatrix = {}; const targetAliasByMatrix = {}; const ownerAliasByMatrix = {};
  for (const matrixId of matrixIds) {
    const matrix = objectValue(matrices[matrixId], "matrix");
    const cases = uniqueStrings(matrix.requiredCases, "case-duplicate");
    const evidence = uniqueStrings(matrix.requiredEvidence, "matrix-evidence-duplicate");
    if (!sameStrings(sorted(Object.keys(objectValue(matrix.expectedStatusByCase, "status-map"))), sorted(cases))) invalidGate("status-map-keys");
    const targetAlias = targetFor(matrixId); const ownerAlias = ownerFor(matrixId);
    if (typeof targetAlias !== "string" || typeof ownerAlias !== "string") invalidGate("derivation-result");
    targetAliases.push(targetAlias); ownerAliases.push(ownerAlias);
    targetAliasByMatrix[matrixId] = targetAlias; ownerAliasByMatrix[matrixId] = ownerAlias;
    evidenceIdsByMatrix[matrixId] = sorted(evidence); for (const id of evidence) matrixEvidence.add(id);
    for (const caseId of cases) {
      const statuses = matrix.expectedStatusByCase[caseId];
      if (!Array.isArray(statuses) || statuses.length === 0 || statuses.some((value) => !Number.isInteger(value)) || new Set(statuses).size !== statuses.length) invalidGate("expected-status");
      const procedureId = procedureFor(matrixId, caseId); if (typeof procedureId !== "string" || procedureById[procedureId]) invalidGate("procedure-collision");
      procedureIds.push(procedureId); procedureById[procedureId] = {
        matrixId, caseId, targetAlias, expectedStatuses: Array.from(statuses),
      };
    }
  }
  if (new Set(targetAliases).size !== targetAliases.length || new Set(ownerAliases).size !== ownerAliases.length) invalidGate("alias-collision");
  const productionLikeEvidencePolicy = objectValue(gate.productionLikeEvidencePolicy, "production-evidence-policy");
  const productionLikeEvidenceIds = uniqueStrings(productionLikeEvidencePolicy.requiredForClosing, "production-evidence-duplicate");
  return deepFreeze({ matrixIds, procedureIds: sorted(procedureIds), targetAliases: sorted(targetAliases),
    ownerAliases: sorted(ownerAliases), matrixEvidenceIds: sorted(matrixEvidence),
    productionLikeEvidenceIds: sorted(productionLikeEvidenceIds), procedureById, evidenceIdsByMatrix,
    targetAliasByMatrix, ownerAliasByMatrix });
}

function typedField(contract, kind, field, extra = {}) {
  return Object.assign({ type: contract.fieldTypes[kind][field] },
    contract.fieldPatterns[kind]?.[field] ? { pattern: contract.fieldPatterns[kind][field] } : {}, extra);
}
function objectSchema(properties, required) { return { type: "object", additionalProperties: false, properties, required }; }
function arraySchema(items) { return { type: "array", items }; }
function pathField(contract, kind, field) {
  return typedField(contract, kind, field, { pattern: contract.relativeEvidencePathPattern });
}
function evidenceSchema(contract, ids) {
  return objectSchema({
    evidenceId: typedField(contract, "evidence", "evidenceId", { enum: ids }),
    result: typedField(contract, "evidence", "result", { enum: contract.resultValues }),
    localEvidencePath: pathField(contract, "evidence", "localEvidencePath"),
    artifactIdentitySha256: typedField(contract, "evidence", "artifactIdentitySha256"),
  }, contract.requiredFields.evidence);
}

export function buildAbusePenetrationSummaryV2Schema(gate, catalog = deriveSummaryCatalog(gate)) {
  const contract = validateContract(gate); const required = contract.requiredFields;
  const typed = (kind, field, extra = {}) => typedField(contract, kind, field, extra);
  const pathValue = (kind, field) => pathField(contract, kind, field);
  const evidence = (ids) => evidenceSchema(contract, ids);
  const identity = objectSchema(Object.fromEntries(Object.keys(contract.fieldTypes.artifactIdentity)
    .map((field) => [field, typed("artifactIdentity", field)])), required.artifactIdentity);
  const counts = objectSchema(Object.fromEntries(Object.keys(contract.fieldTypes.findingCounts)
    .map((field) => [field, typed("findingCounts", field, { minimum: 0 })])), required.findingCounts);
  const disposition = objectSchema({
    ownerAlias: typed("mediumFindingDisposition", "ownerAlias", { enum: catalog.ownerAliases }),
    fixPlanEvidencePath: pathValue("mediumFindingDisposition", "fixPlanEvidencePath"),
  }, required.mediumFindingDisposition);
  const caseItem = objectSchema({
    procedureId: typed("case", "procedureId", { enum: catalog.procedureIds }),
    targetAlias: typed("case", "targetAlias", { enum: catalog.targetAliases }),
    expectedStatus: typed("case", "expectedStatus"), observedStatus: typed("case", "observedStatus"),
    redactionResult: typed("case", "redactionResult", { enum: contract.redactionResultValues }),
    localEvidencePath: pathValue("case", "localEvidencePath"),
    artifactIdentitySha256: typed("case", "artifactIdentitySha256"),
  }, required.case);
  const matrix = objectSchema({
    matrixId: typed("matrix", "matrixId", { enum: catalog.matrixIds }),
    result: typed("matrix", "result", { enum: contract.resultValues }), findingCounts: counts,
    mediumFindingDisposition: disposition, cases: arraySchema(caseItem),
  }, required.matrix);
  return Object.assign({ $id: "abuse-penetration-summary-v2" }, objectSchema({
    schemaVersion: typed("root", "schemaVersion", { const: contract.currentVersion }),
    releaseGate: typed("root", "releaseGate", { const: gate.releaseGate }), issue: typed("root", "issue", { const: gate.issue }),
    status: typed("root", "status", { enum: contract.statusValues }),
    rawInvocationStored: typed("root", "rawInvocationStored", { const: contract.rawInvocationStored }),
    redactionPolicyId: typed("root", "redactionPolicyId", { enum: contract.redactionPolicyIds }),
    artifactIdentity: identity, evidence: arraySchema(evidence(catalog.matrixEvidenceIds)),
    productionLikeEvidence: arraySchema(evidence(catalog.productionLikeEvidenceIds)), matrices: arraySchema(matrix),
  }, required.rootForAllStatuses));
}
