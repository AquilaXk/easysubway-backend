#!/usr/bin/env node
import { createHash } from "node:crypto";
import { isIP } from "node:net";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { codepointCompare } from "../lib/codepoint-compare.mjs";
import { validateSchema } from "../ci/lib/json-schema-lite.mjs";
import { buildAbusePenetrationSummaryV2Schema, deriveSummaryCatalog } from "./abuse-penetration-summary-schema.mjs";

export class SummaryValidationError extends Error {
  constructor(code, path, rule) { super(`${code} at ${path} (${rule})`); this.code = code; this.path = path; this.rule = rule; }
}
const fail = (code, path, rule) => { throw new SummaryValidationError(code, path, rule); };
const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
function isJsonLike(value, seen = new Set(), depth = 0) {
  if (value === null || typeof value !== "object") return true;
  if (depth > 64 || seen.has(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  if (Array.isArray(value) ? prototype !== Array.prototype : prototype !== Object.prototype && prototype !== null) return false;
  seen.add(value);
  return Object.keys(value).every((key) => isJsonLike(value[key], seen, depth + 1));
}
function typeOk(value, type) {
  return type === "string" ? typeof value === "string" : type === "integer" ? Number.isInteger(value) :
    type === "boolean" ? typeof value === "boolean" : type === "array" ? Array.isArray(value) : type === "object" ? isObject(value) : false;
}
function exactKeys(value, allowed, path, code = "SUMMARY_V1_SCHEMA_INVALID") {
  if (!isObject(value)) fail(code, path, "object");
  const set = new Set(allowed); for (const key of Object.keys(value)) if (!set.has(key)) fail(code, `${path}.${key}`, "additional-property");
  return value;
}
function requireType(value, type, path, code = "SUMMARY_V1_SCHEMA_INVALID") {
  if (!typeOk(value, type)) fail(code, path, `type-${type}`); return value;
}
function uniqueIds(items, field, allowed, path, code = "SUMMARY_ID_SET_MISMATCH") {
  requireType(items, "array", path, code); const seen = new Set(); const result = [];
  for (let index = 0; index < items.length; index += 1) {
    const value = requireType(items[index]?.[field], "string", `${path}[${index}].${field}`, code);
    if (!allowed.has(value) || seen.has(value)) fail(code, path, "controlled-unique-set"); seen.add(value); result.push(value);
  }
  return result;
}
const sorted = (values) => Array.from(values).sort(codepointCompare);
function exactSet(actual, expected, path) {
  if (JSON.stringify(sorted(actual)) !== JSON.stringify(sorted(expected))) fail("SUMMARY_ID_SET_MISMATCH", path, "exact-set");
}
const V1_ROOT_FIELDS = ["schemaVersion", "releaseGate", "issue", "status", "artifactIdentity", "productionLikeEvidence", "matrices"];
const V1_IDENTITY_TYPES = { gitSha:"string",versionCode:"integer",androidApplicationId:"string",dataPackManifestSha256:"string",aabSha256:"string",generatedApkSha256:"string",backendImageDigest:"string",backendArtifactSha256:"string" };
const V1_EVIDENCE_TYPES = { evidenceId:"string",result:"string",localEvidencePath:"string" };
const V1_MATRIX_TYPES = { matrixId:"string",scenarioId:"string",artifactIdentity:"object",commandOrManualCheck:"string",findingCounts:"object",result:"string",redactionNotes:"string",localEvidencePath:"string",requiredEvidence:"array",cases:"array",mediumFindingDisposition:"object" };
const V1_CASE_TYPES = { apiStep:"string",artifactType:"string",attemptCount:"string",auditRedactionResult:"string",bucketOrPolicyAlias:"string",caseId:"string",cleanupResult:"string",commandOrManualCheck:"string",contentType:"string",deleteOrCleanupResult:"string",endpoint:"string",expectedStatus:"integer",localEvidencePath:"string",method:"string",nodeOrStoreMode:"string",observedStatus:"integer",redactionResult:"string",retentionRule:"string",role:"string",scanTarget:"string",sizeBytes:"integer",tenantScope:"string",ttlSeconds:"integer" };
function validateIdentity(identity, gate, path, code) {
  const allowed = Array.from(new Set(gate.buildIdentityPolicy.requiredIdentityFields.concat(gate.buildIdentityPolicy.requiredIdentityAnyOf.flat())));
  exactKeys(identity, allowed, path, code); for (const [field, value] of Object.entries(identity)) requireType(value, V1_IDENTITY_TYPES[field], `${path}.${field}`, code);
  for (const field of gate.buildIdentityPolicy.requiredIdentityFields) if (!(field in identity)) fail(code, `${path}.${field}`, "required");
  for (const group of gate.buildIdentityPolicy.requiredIdentityAnyOf) if (!group.some((field) => typeof identity[field] === "string" && identity[field])) fail("SUMMARY_IDENTITY_INVALID", path, "digest-group");
}
function validateLegacyDisposition(disposition, path) {
  exactKeys(disposition,["owner","fixPlan"],`${path}.mediumFindingDisposition`);
  requireType(disposition.owner,"string",`${path}.mediumFindingDisposition.owner`);
  requireType(disposition.fixPlan,"string",`${path}.mediumFindingDisposition.fixPlan`);
}
function validateCounts(matrix, gate, path, version) {
  const counts = exactKeys(matrix.findingCounts, ["critical","high","medium","low"], `${path}.findingCounts`, version === 1 ? "SUMMARY_V1_SCHEMA_INVALID" : "SUMMARY_V2_SCHEMA_INVALID");
  for (const [field, value] of Object.entries(counts)) if (!Number.isInteger(value) || value < 0) fail(version === 1 ? "SUMMARY_V1_SCHEMA_INVALID" : "SUMMARY_V2_SCHEMA_INVALID", `${path}.findingCounts.${field}`, "nonnegative-integer");
  if (counts.critical > gate.findingPolicy.criticalHighAllowed || counts.high > gate.findingPolicy.criticalHighAllowed) fail("SUMMARY_FINDING_POLICY_INVALID", `${path}.findingCounts`, "critical-high-limit");
  const disposition = matrix.mediumFindingDisposition;
  if (version === 2 && counts.medium === 0 && disposition !== undefined) fail("SUMMARY_FINDING_POLICY_INVALID", `${path}.mediumFindingDisposition`, "stale-disposition");
  if (counts.medium > 0 && disposition === undefined) fail("SUMMARY_FINDING_POLICY_INVALID", `${path}.mediumFindingDisposition`, "required-disposition");
}
function validateLegacyV1(summary, gate, requirePass) {
  if (requirePass || summary.status === "PASS") fail("SUMMARY_VERSION_UNSUPPORTED", "$.schemaVersion", "v1-non-pass-only");
  exactKeys(summary, V1_ROOT_FIELDS, "$", "SUMMARY_V1_SCHEMA_INVALID");
  for (const field of ["schemaVersion","releaseGate","issue","status"]) if (!(field in summary)) fail("SUMMARY_V1_SCHEMA_INVALID", `$.${field}`, "required");
  if (summary.releaseGate !== gate.releaseGate || summary.issue !== gate.issue || !["FAIL","BLOCKED_EXTERNAL"].includes(summary.status)) fail("SUMMARY_V1_SCHEMA_INVALID", "$", "envelope-value");
  if (summary.artifactIdentity !== undefined) validateIdentity(summary.artifactIdentity, gate, "$.artifactIdentity", "SUMMARY_V1_SCHEMA_INVALID");
  if (summary.productionLikeEvidence !== undefined) {
    requireType(summary.productionLikeEvidence, "array", "$.productionLikeEvidence", "SUMMARY_V1_SCHEMA_INVALID");
    const allowed = new Set(gate.productionLikeEvidencePolicy.requiredForClosing);
    summary.productionLikeEvidence.forEach((item,index) => { exactKeys(item,Object.keys(V1_EVIDENCE_TYPES),`$.productionLikeEvidence[${index}]`); for (const [field,type] of Object.entries(V1_EVIDENCE_TYPES)) requireType(item[field],type,`$.productionLikeEvidence[${index}].${field}`); if (!gate.summaryContract.resultValues.includes(item.result)) fail("SUMMARY_V1_SCHEMA_INVALID",`$.productionLikeEvidence[${index}].result`,"controlled-result"); });
    uniqueIds(summary.productionLikeEvidence,"evidenceId",allowed,"$.productionLikeEvidence");
  }
  if (summary.matrices === undefined) return;
  requireType(summary.matrices, "array", "$.matrices", "SUMMARY_V1_SCHEMA_INVALID");
  summary.matrices.forEach((item,index) => {
    const path=`$.matrices[${index}]`; exactKeys(item,Object.keys(V1_MATRIX_TYPES),path);
  });
  const matrixAllowed = new Set(Object.keys(gate.rehearsalMatrices)); uniqueIds(summary.matrices,"matrixId",matrixAllowed,"$.matrices");
  summary.matrices.forEach((item,index) => {
    const path=`$.matrices[${index}]`; const matrix=gate.rehearsalMatrices[item.matrixId]; exactKeys(item,Object.keys(V1_MATRIX_TYPES),path);
    for (const [field,value] of Object.entries(item)) requireType(value,V1_MATRIX_TYPES[field],`${path}.${field}`);
    if (item.scenarioId !== undefined && item.scenarioId !== matrix.scenarioId) fail("SUMMARY_PROCEDURE_MAPPING_INVALID",`${path}.scenarioId`,"scenario-id");
    if (item.artifactIdentity !== undefined) validateIdentity(item.artifactIdentity,gate,`${path}.artifactIdentity`,"SUMMARY_V1_SCHEMA_INVALID");
    if (item.result !== undefined && !gate.summaryContract.resultValues.includes(item.result)) fail("SUMMARY_V1_SCHEMA_INVALID",`${path}.result`,"controlled-result");
    if (item.mediumFindingDisposition !== undefined) validateLegacyDisposition(item.mediumFindingDisposition,path);
    if (item.findingCounts !== undefined) validateCounts(item,gate,path,1);
    if (item.requiredEvidence !== undefined) {
      requireType(item.requiredEvidence,"array",`${path}.requiredEvidence`);
      uniqueIds(item.requiredEvidence.map((evidenceId)=>({evidenceId})),"evidenceId",new Set(matrix.requiredEvidence),`${path}.requiredEvidence`);
    }
    if (item.cases !== undefined) {
      requireType(item.cases,"array",`${path}.cases`);
      item.cases.forEach((caseItem,caseIndex) => { const casePath=`${path}.cases[${caseIndex}]`; exactKeys(caseItem,matrix.summaryFields,casePath);
        for (const [field,value] of Object.entries(caseItem)) requireType(value,V1_CASE_TYPES[field],`${casePath}.${field}`);
      });
      uniqueIds(item.cases,"caseId",new Set(matrix.requiredCases),`${path}.cases`);
      item.cases.forEach((caseItem,caseIndex) => { const casePath=`${path}.cases[${caseIndex}]`;
        if (caseItem.expectedStatus !== undefined && !matrix.expectedStatusByCase[caseItem.caseId].includes(caseItem.expectedStatus)) fail("SUMMARY_PROCEDURE_MAPPING_INVALID",casePath,"expected-status");
        for (const field of ["redactionResult","auditRedactionResult","cleanupResult","deleteOrCleanupResult"]) if (caseItem[field] !== undefined && !gate.summaryContract.redactionResultValues.includes(caseItem[field])) fail("SUMMARY_V1_SCHEMA_INVALID",`${casePath}.${field}`,"controlled-result");
      });
    }
  });
}
function artifactIdentitySha256(identity) {
  const canonical = Object.fromEntries(Object.keys(identity).sort(codepointCompare).map((field) => [field, identity[field]]));
  return createHash("sha256").update(JSON.stringify(canonical)).digest("hex");
}
function validateIdentityEvidencePath(value, identity, path) {
  if (identity === undefined) return;
  const expectedPathPrefix = `.codex/evidence/security/abuse-penetration-rehearsal/${identity.gitSha}/`;
  if (!value.startsWith(expectedPathPrefix)) {
    fail("SUMMARY_IDENTITY_INVALID",path,"root-git-sha-path");
  }
}
function validateEvidence(items, allowed, path, requireExact, gate, identity, paths) {
  const actual = uniqueIds(items,"evidenceId",new Set(allowed),path);
  if (requireExact) exactSet(actual,allowed,path);
  const expectedIdentitySha256 = identity === undefined ? undefined : artifactIdentitySha256(identity);
  items.forEach((item,index) => {
    if (!gate.summaryContract.resultValues.includes(item.result)) fail("SUMMARY_V2_SCHEMA_INVALID",`${path}[${index}].result`,"controlled-result");
    if (requireExact && item.result !== "PASS") fail("SUMMARY_ID_SET_MISMATCH",`${path}[${index}].result`,"pass-result");
    if (expectedIdentitySha256 !== undefined && item.artifactIdentitySha256 !== expectedIdentitySha256) {
      fail("SUMMARY_IDENTITY_INVALID",`${path}[${index}].artifactIdentitySha256`,"root-artifact-identity-digest");
    }
    validateIdentityEvidencePath(item.localEvidencePath,identity,`${path}[${index}].localEvidencePath`);
    if (paths.has(item.localEvidencePath)) {
      fail("SUMMARY_IDENTITY_INVALID",`${path}[${index}].localEvidencePath`,"duplicate-evidence-path");
    }
    paths.add(item.localEvidencePath);
  });
}
function validateV2Semantics(summary, gate, catalog) {
  const pass = summary.status === "PASS";
  if (pass) for (const field of gate.summaryContract.requiredFields.rootAdditionalForPass) if (!(field in summary)) fail("SUMMARY_ID_SET_MISMATCH",`$.${field}`,"pass-required");
  if (summary.artifactIdentity !== undefined) validateIdentity(summary.artifactIdentity,gate,"$.artifactIdentity","SUMMARY_V2_SCHEMA_INVALID");
  if (summary.artifactIdentity === undefined &&
      ((summary.evidence?.length ?? 0) > 0 || (summary.productionLikeEvidence?.length ?? 0) > 0)) {
    fail("SUMMARY_IDENTITY_INVALID","$.artifactIdentity","evidence-root-artifact-identity");
  }
  const evidencePaths = new Set();
  if (summary.evidence !== undefined) validateEvidence(summary.evidence,catalog.matrixEvidenceIds,"$.evidence",pass,gate,summary.artifactIdentity,evidencePaths);
  if (summary.productionLikeEvidence !== undefined) validateEvidence(summary.productionLikeEvidence,catalog.productionLikeEvidenceIds,"$.productionLikeEvidence",pass,gate,summary.artifactIdentity,evidencePaths);
  if (summary.matrices === undefined) return;
  if (summary.artifactIdentity === undefined && summary.matrices.some((matrix) => matrix.cases.length > 0)) {
    fail("SUMMARY_IDENTITY_INVALID","$.artifactIdentity","matrix-case-root-artifact-identity");
  }
  const matrixIds = uniqueIds(summary.matrices,"matrixId",new Set(catalog.matrixIds),"$.matrices");
  const expectedIdentitySha256 = summary.artifactIdentity === undefined ? undefined : artifactIdentitySha256(summary.artifactIdentity);
  const procedureSets = [];
  for (let index=0; index<summary.matrices.length; index+=1) {
    const item=summary.matrices[index]; const path=`$.matrices[${index}]`; validateCounts(item,gate,path,2);
    if (item.mediumFindingDisposition !== undefined) {
      if (item.mediumFindingDisposition.ownerAlias !== catalog.ownerAliasByMatrix[item.matrixId]) fail("SUMMARY_FINDING_POLICY_INVALID",`${path}.mediumFindingDisposition.ownerAlias`,"owner-alias");
    }
    const procedures = uniqueIds(item.cases,"procedureId",new Set(catalog.procedureIds),`${path}.cases`);
    for (let caseIndex=0; caseIndex<item.cases.length; caseIndex+=1) {
      const caseItem=item.cases[caseIndex]; const mapping=catalog.procedureById[caseItem.procedureId]; const casePath=`${path}.cases[${caseIndex}]`;
      if (expectedIdentitySha256 !== undefined && caseItem.artifactIdentitySha256 !== expectedIdentitySha256) {
        fail("SUMMARY_IDENTITY_INVALID",`${casePath}.artifactIdentitySha256`,"root-artifact-identity-digest");
      }
      validateIdentityEvidencePath(caseItem.localEvidencePath,summary.artifactIdentity,`${casePath}.localEvidencePath`);
      if (mapping.matrixId!==item.matrixId || mapping.targetAlias!==caseItem.targetAlias || !mapping.expectedStatuses.includes(caseItem.expectedStatus)) fail("SUMMARY_PROCEDURE_MAPPING_INVALID",casePath,"procedure-mapping");
      if (pass && (caseItem.observedStatus!==caseItem.expectedStatus || caseItem.redactionResult!=="PASS")) fail("SUMMARY_PROCEDURE_MAPPING_INVALID",casePath,"pass-case");
    }
    const expected=catalog.procedureIds.filter((procedureId)=>catalog.procedureById[procedureId].matrixId===item.matrixId);
    procedureSets.push([procedures,expected,`${path}.cases`]);
    if (pass && item.result!=="PASS") fail("SUMMARY_ID_SET_MISMATCH",`${path}.result`,"pass-result");
  }
  if (pass) for (const [procedures,expected,path] of procedureSets) exactSet(procedures,expected,path);
  if (pass) exactSet(matrixIds,catalog.matrixIds,"$.matrices");
}
function firstSchemaPath(errors) {
  return errors.map((error)=>error.split(":",1)[0].replace(/\.(\d+)(?=\.|$)/g,"[$1]")).sort()[0] ?? "$";
}
function validateAbuseEnvelope(summary) {
  if (!isObject(summary) || !Number.isInteger(summary.schemaVersion)) fail("SUMMARY_VERSION_UNSUPPORTED","$.schemaVersion","integer-version");
  if (typeof summary.status!=="string") fail("SUMMARY_V2_SCHEMA_INVALID","$.status","string-status");
}
const SCHEME=/(?:https?|ftp):\/\//i;
const CREDENTIAL=/\b(?:authorization|cookie|bearer|basic|private[ _-]?key|sessionid)\b/i;
const IDENTIFIER=/(?:user|device|advertising|account|request)[_-]?(?:id|identifier)/i;
const LEGACY_COMMAND_DEFENSE=/\b(?:curl|wget|ssh|scp|sftp|bash|zsh|fish|powershell|pwsh|cmd|python|node|ruby|perl|kubectl|docker|podman|psql|mysql|redis-cli|adb)\b|--(?:header|data|request)\b/i;
const LEGACY_USER_AGENT_TOKEN_DEFENSE=/\b[0-9A-Za-z_$]*UserAgent[0-9A-Za-z_$]*\b/i;
const LEGACY_PRODUCT_VERSION_DEFENSE=/(?:^|[\s(])[!#$%&'*+.^_`|~0-9A-Za-z-]+\/\d+(?:\.\d+)*(?=$|[\s;)])/;
const BASE64URL_CANDIDATE=/[A-Za-z0-9_-]{12,}/g;
const STANDARD_BASE64_PATH_CANDIDATE=/(?=([A-Za-z0-9+/]{12,4096}={0,2})(?=[^A-Za-z0-9+/=]|$))/g;
const MAX_PRIVACY_STRING_BYTES=4096;
const MAX_PRIVACY_DECODE_DEPTH=8;
function decodeBounded(value) {
  let current=value.normalize("NFKC");
  for (let count=0; count<2; count+=1) { try { const next=decodeURIComponent(current); if (next===current) break; current=next; } catch { break; } }
  return current.toLowerCase();
}
function networkToken(value) {
  return value.trim().replace(/^[('"`<{]+/, "").replace(/[)'"`>},;.!?]+$/, "");
}
function normalizedIpHost(value) {
  let host=value.trim().replace(/^\[/, "").replace(/\]$/, "");
  const zoneIndex=host.lastIndexOf("%");
  if (zoneIndex>0) host=host.slice(0,zoneIndex);
  return isIP(host) ? host : "";
}
function normalizedUrlHost(value) {
  try { return normalizedIpHost(new URL(value).hostname); } catch { return ""; }
}
function normalizedAuthorityHost(value) {
  const token=networkToken(value);
  if (!token||/\s/.test(token)||/^(?:\/|\.\/|\.\.\/)/.test(token)||token.startsWith(".")) return "";
  return normalizedUrlHost(`http://${token}`);
}
function containsAddress(value) {
  const tokens=value.match(/[^\s,;=]+/g) ?? [];
  return tokens.some((rawToken)=>{
    const token=networkToken(rawToken);
    const cidrCandidate=token.replace(/\/\d{1,3}$/, "");
    if (normalizedIpHost(cidrCandidate)) return true;
    if (/^(?:https?|ftp):\/\//i.test(token) && normalizedUrlHost(token)) return true;
    return Boolean(normalizedAuthorityHost(token));
  });
}
function containsLegacyAuthorityEndpoint(value) {
  const tokens=value.match(/[^\s,;=]+/g) ?? [];
  return tokens.some((rawToken)=>{
    const token=networkToken(rawToken);
    if (!token||/^(?:\/|\.\/|\.\.\/)/.test(token)||token.startsWith(".")) return false;
    if (!/^[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+(?::\d{1,5})?(?:\/[^\s]*)?$/.test(token)) return false;
    if (!token.includes(":")&&!token.includes("/")) return false;
    try { const parsed=new URL(`http://${token}`); return !isIP(parsed.hostname)&&parsed.hostname.includes("."); } catch { return false; }
  });
}
function decodeCanonicalBase64(value) {
  if (value.length > 4096 || !/^[A-Za-z0-9+/_-]+={0,2}$/.test(value)) return "";
  const urlSafe = !/[+/=]/.test(value);
  try {
    const bytes=Buffer.from(value,urlSafe ? "base64url" : "base64");
    if (bytes.length===0 || (urlSafe ? bytes.toString("base64url")!==value : bytes.toString("base64")!==value)) return "";
    const decoded=new TextDecoder("utf-8",{fatal:true}).decode(bytes);
    return Array.from(decoded).every((character)=>character.codePointAt(0)>=0x20&&character.codePointAt(0)!==0x7f) ? decoded : "";
  } catch { return ""; }
}
function scanPrivacy(value, gate, version, path="$", seen=new Set(), decodeDepth=0) {
  if (value!==null && typeof value==="object") {
    if (seen.has(value)) return;
    seen.add(value);
  }
  if (typeof value==="string") {
    if (seen.has(value)) return;
    seen.add(value);
    if (Buffer.byteLength(value,"utf8")>MAX_PRIVACY_STRING_BYTES) fail("SUMMARY_PRIVACY_VIOLATION",path,"string-byte-limit");
    const decoded=decodeBounded(value); const compact=decoded.replace(/\s+/g,"");
    const forbidden=gate.manualRehearsalPolicy.forbiddenInEvidence.concat(
      Object.values(gate.rehearsalMatrices).flatMap((matrix)=>matrix.forbiddenSummaryValues),
      gate.latestQaEvidenceStatus.redactionPolicy.forbiddenInGitHubEvidence,
      gate.productionLikeEvidencePolicy.forbiddenClosureEvidence,
    );
    const legacyCommand=version===1 && (LEGACY_COMMAND_DEFENSE.test(decoded)||LEGACY_COMMAND_DEFENSE.test(compact));
    const legacyAuthority=version===1 && containsLegacyAuthorityEndpoint(decoded);
    const legacyUserAgent=version===1 && (/user-agent:/.test(compact)||LEGACY_USER_AGENT_TOKEN_DEFENSE.test(value)||LEGACY_PRODUCT_VERSION_DEFENSE.test(value));
    if (SCHEME.test(compact)||CREDENTIAL.test(compact)||IDENTIFIER.test(compact)||legacyCommand||legacyAuthority||legacyUserAgent||containsAddress(decoded)||forbidden.some((item)=>compact.includes(item.toLowerCase().replace(/\s+/g,"")))) fail("SUMMARY_PRIVACY_VIOLATION",path,"sensitive-string");
    const candidates=value.match(BASE64URL_CANDIDATE) ?? [];
    if (/^[A-Za-z0-9+/_-]+={0,2}$/.test(value)) candidates.push(value);
    for (const candidate of candidates) {
      const base64Decoded=decodeCanonicalBase64(candidate);
      if (base64Decoded) {
        if (decodeDepth>=MAX_PRIVACY_DECODE_DEPTH) fail("SUMMARY_PRIVACY_VIOLATION",path,"base64-depth-limit");
        scanPrivacy(base64Decoded,gate,version,path,seen,decodeDepth+1);
      }
    }
    for (const [,candidate] of value.matchAll(STANDARD_BASE64_PATH_CANDIDATE)) {
      if (!candidate.includes("/")) continue;
      const base64Decoded=decodeCanonicalBase64(candidate);
      if (base64Decoded) {
        if (decodeDepth>=MAX_PRIVACY_DECODE_DEPTH) fail("SUMMARY_PRIVACY_VIOLATION",path,"base64-depth-limit");
        scanPrivacy(base64Decoded,gate,version,path,seen,decodeDepth+1);
      }
    }
    return;
  }
  if (Array.isArray(value)) { value.forEach((item,index)=>scanPrivacy(item,gate,version,`${path}[${index}]`,seen,decodeDepth)); return; }
  if (isObject(value)) for (const [key,child] of Object.entries(value)) scanPrivacy(child,gate,version,`${path}.${key}`,seen,decodeDepth);
}
export function validateAbusePenetrationSummary(summary, gate, { requirePass=false } = {}) {
  if (!isJsonLike(summary)) fail("SUMMARY_V2_SCHEMA_INVALID","$","json-like");
  validateAbuseEnvelope(summary);
  if (requirePass && summary.schemaVersion!==gate.summaryContract.requirePassVersion) fail("SUMMARY_VERSION_UNSUPPORTED","$.schemaVersion","require-pass-version");
  if (summary.schemaVersion===1) { validateLegacyV1(summary,gate,requirePass); scanPrivacy(summary,gate,1); return Object.freeze({schemaVersion:1,status:summary.status}); }
  if (summary.schemaVersion!==gate.summaryContract.currentVersion) fail("SUMMARY_VERSION_UNSUPPORTED","$.schemaVersion","supported-version");
  const catalog=deriveSummaryCatalog(gate); const result=validateSchema(buildAbusePenetrationSummaryV2Schema(gate,catalog),summary);
  if (!result.ok) fail("SUMMARY_V2_SCHEMA_INVALID",firstSchemaPath(result.errors),"structural-schema");
  validateV2Semantics(summary,gate,catalog); scanPrivacy(summary,gate,2);
  if (requirePass && summary.status!=="PASS") fail("SUMMARY_REQUIRE_PASS_FAILED","$.status","root-pass");
  return Object.freeze({schemaVersion:2,status:summary.status});
}
function parseArgs(args) {
  const result={summaryPath:null,gatePath:"release/product-gates/abuse-penetration-rehearsal-gate.json",requirePass:false};
  const seen=new Set();
  for (let index=0; index<args.length; index+=1) {
    const flag=args[index]; if (!["--summary","--gate","--require-pass"].includes(flag)||seen.has(flag)) fail("SUMMARY_CLI_INVALID","$","arguments"); seen.add(flag);
    if (flag==="--require-pass") { result.requirePass=true; continue; }
    const value=args[index+1]; if (!value||value.startsWith("--")) fail("SUMMARY_CLI_INVALID","$","arguments"); index+=1;
    if (flag==="--summary") result.summaryPath=value; else result.gatePath=value;
  }
  if (!result.summaryPath) fail("SUMMARY_CLI_INVALID","$","summary-required"); return result;
}
async function readJsonSafe(file,kind) {
  let source; try { source=await readFile(file,"utf8"); } catch { fail("SUMMARY_INPUT_READ_FAILED","$",`${kind}-read`); }
  try { return JSON.parse(source); } catch { fail("SUMMARY_JSON_INVALID","$",`${kind}-json`); }
}
export async function runCli(args=process.argv.slice(2)) {
  const options=parseArgs(args); const [summary,gate]=await Promise.all([readJsonSafe(options.summaryPath,"summary"),readJsonSafe(options.gatePath,"gate")]);
  validateAbusePenetrationSummary(summary,gate,{requirePass:options.requirePass});
}

if (process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
  runCli().catch((error)=>{ console.error(error instanceof SummaryValidationError ? error.message : "SUMMARY_INTERNAL_ERROR at $ (unexpected)"); process.exitCode=1; });
}
