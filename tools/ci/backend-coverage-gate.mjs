import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  lstatSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  rmdirSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, join, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const POLICY_SHA256 = '78b16cc6a62f9625c051c2d0fe4f9ac61341180e53983bdbc3fbd35257bc968b';
const BASELINE_SHA256 = '4349171637df6356999a5f1112cfcb4496b37608efe80c1934afc92e1d23a101';
const POLICY_PHASE_A = 'DISCOVERY_REMOTE_RED';
const POLICY_PHASE_B = 'ENFORCED_DECREASE_ONLY';
const BASELINE_PHASE_A = 'UNREVIEWED_DISCOVERY';
const BASELINE_PHASE_B = 'REVIEWED_DECREASE_ONLY';
const ISSUE_URL = 'https://github.com/AquilaXk/easysubway-backend/issues/32';
const REPOSITORY = 'AquilaXk/easysubway-backend';
const SOURCE_ROOT = 'backend/src/main/java/';
const PACKAGE_ROOT = `${SOURCE_ROOT}com/easysubway/`;
const REPORT_PATH = 'backend/build/reports/jacoco/test/jacocoTestReport.xml';
const EVIDENCE_PATH = 'backend/build/jacoco/jacocoTestReport-evidence.json';
const EXCLUSION = {
  id: 'SPRING_BOOT_ENTRYPOINT',
  classFile: 'com/easysubway/EasySubwayBackendApplication.class',
  sourcePath: 'backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java',
  reason: 'Spring Boot entrypoint contains no critical business behavior and is covered by startup/context contracts.',
  ownerIssueUrl: ISSUE_URL,
  removalCondition: 'Remove when the entrypoint is admitted to critical executable coverage without synthetic startup behavior.',
  reviewTriggers: ['SOURCE_BYTES_CHANGE', 'CLASS_PATH_CHANGE', 'STARTUP_CONTRACT_CHANGE'],
};
const SCOPE_RULES = [
  { id: 'JOURNEY_ROUTE', prefixes: ['journey/', 'route/', 'profile/', 'transit/'], ownerIssueUrl: ISSUE_URL, reason: 'Journey request, planner, accessibility profile and transit domain identity' },
  { id: 'ARTIFACT_RELEASE', prefixes: ['datapack/'], ownerIssueUrl: ISSUE_URL, reason: 'catalog, signature, admission, release evidence and promotion boundaries' },
  { id: 'AUTH_SECURITY', prefixes: ['common/security/', 'admin/authorization/', 'admin/audit/'], ownerIssueUrl: ISSUE_URL, reason: 'authentication, privileged authorization and audit' },
  { id: 'FACILITY_REPORT', prefixes: ['report/'], ownerIssueUrl: ISSUE_URL, reason: 'receipt, upload, abuse control, privacy and review lifecycle' },
  { id: 'REALTIME_STRICT', prefixes: ['realtime/'], ownerIssueUrl: ISSUE_URL, reason: 'provider, freshness and cache strict boundary' },
  { id: 'ACCESSIBILITY_QUALITY', prefixes: ['quality/', 'operator/'], ownerIssueUrl: ISSUE_URL, reason: 'accessibility data quality and operator-facing truthfulness' },
  { id: 'HEALTH_READINESS', prefixes: ['health/'], ownerIssueUrl: ISSUE_URL, reason: 'component and readiness evidence' },
  { id: 'USER_PRIVACY', prefixes: ['user/'], ownerIssueUrl: ISSUE_URL, reason: 'user-data deletion and anonymization boundary' },
];
const REASONS = [
  'BASELINE_UNREVIEWED', 'PRODUCER_DRIFT', 'EXCLUSION_DRIFT', 'MISSING_REPORT_SOURCE',
  'NEWLY_MEASURABLE_SOURCE', 'CHANGED_REVIEWED_MISSING_SOURCE', 'NEW_CRITICAL_SOURCE',
  'REMOVED_CRITICAL_SOURCE', 'SOURCE_RENAME_UNREVIEWED', 'LINE_MISSED_INCREASE',
  'LINE_RATIO_DECREASE', 'NEWLY_MEASURABLE_BRANCH', 'BRANCH_MISSED_INCREASE',
  'BRANCH_RATIO_DECREASE', 'BOUNDARY_LINE_DECREASE', 'BOUNDARY_BRANCH_DECREASE',
];
const ARTIFACT_FILES = [
  'jacocoTestReport.xml', 'backend-critical-coverage-baseline.json',
  'backend-critical-coverage-result.json', 'backend-critical-coverage-summary.md',
  'backend-critical-coverage.sha256',
];
const COUNTER_TYPES = ['INSTRUCTION', 'BRANCH', 'LINE', 'COMPLEXITY', 'METHOD', 'CLASS'];
const RESULT_KEYS = ['schemaVersion', 'artifactKind', 'repository', 'phase', 'outcome', 'reasons', 'identity', 'producer', 'comparison', 'coverage', 'boundaries', 'exclusions', 'inventory', 'artifacts'];
const fail = (message) => { throw new Error(`invalid backend critical coverage gate: ${message}`); };
const digest = (value) => createHash('sha256').update(value).digest('hex');
const canonical = (value) => `${JSON.stringify(value, null, 2)}\n`;
const compactCanonical = (value) => `${JSON.stringify(value)}\n`;
const exactKeys = (value, expected, label) => {
  if (value === null || typeof value !== 'object' || Array.isArray(value) || JSON.stringify(Object.keys(value)) !== JSON.stringify(expected)) fail(`${label} keys mismatch`);
};
const exactArray = (value, expected, label) => {
  if (!Array.isArray(value) || JSON.stringify(value) !== JSON.stringify(expected)) fail(`${label} mismatch`);
};
const text = (value, label) => { if (typeof value !== 'string' || value.length === 0) fail(`${label} must be text`); return value; };
const sha = (value, label) => { if (!/^[a-f0-9]{64}$/.test(value ?? '')) fail(`${label} must be sha256`); return value; };
const commitSha = (value, label) => { if (!/^[a-f0-9]{40}$/.test(value ?? '')) fail(`${label} must be a commit SHA`); return value; };
const safeInteger = (value, label) => { if (!Number.isSafeInteger(value) || value < 0) fail(`${label} must be a nonnegative safe integer`); return value; };
const safePath = (value, label) => {
  if (typeof value !== 'string' || !/^[A-Za-z0-9$._/-]+$/.test(value) || value.startsWith('/') || value.includes('..') || value.includes('//') || value.includes('\\')) fail(`${label} must be a canonical repository path`);
  return value;
};
const isWithin = (root, candidate) => candidate === root || candidate.startsWith(`${root}${sep}`);
const regularFile = (path, label) => {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) fail(`${label} must be a regular non-symlink file`);
  return stat;
};
const decodeUtf8 = (bytes, label, maxBytes = 64 * 1024 * 1024, allowNul = false) => {
  if (!Buffer.isBuffer(bytes) || bytes.length === 0 || bytes.length > maxBytes) fail(`${label} byte size is invalid`);
  let value;
  try { value = new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch { fail(`${label} is not UTF-8`); }
  if ((!allowNul && value.includes('\0')) || value.startsWith('\uFEFF')) fail(`${label} contains forbidden bytes`);
  return value;
};

export const basisPoints = (missed, covered) => {
  safeInteger(missed, 'counter missed'); safeInteger(covered, 'counter covered');
  const total = missed + covered;
  return total === 0 ? null : Math.floor((covered * 10000) / total);
};
const counter = (missed, covered) => ({ missed, covered, total: missed + covered, basisPoints: basisPoints(missed, covered) });
const sumCounters = (values) => counter(values.reduce((sum, item) => sum + item.missed, 0), values.reduce((sum, item) => sum + item.covered, 0));
const validateCounter = (value, label) => {
  exactKeys(value, ['missed', 'covered', 'total', 'basisPoints'], label);
  safeInteger(value.missed, `${label}.missed`); safeInteger(value.covered, `${label}.covered`); safeInteger(value.total, `${label}.total`);
  if (value.total !== value.missed + value.covered || value.basisPoints !== basisPoints(value.missed, value.covered)) fail(`${label} is inconsistent`);
  return true;
};

export const parseCanonicalJson = (input, label = 'JSON') => {
  const value = typeof input === 'string' ? input : decodeUtf8(input, label, 16 * 1024 * 1024);
  if (value.startsWith('\uFEFF') || value.includes('\0')) fail(`${label} contains forbidden bytes`);
  let parsed;
  try { parsed = JSON.parse(value); } catch { fail(`${label} is malformed`); }
  if (canonical(parsed) !== value) fail(`${label} is not canonical JSON`);
  return parsed;
};
const parseCompactCanonicalJson = (input, label) => {
  const value = typeof input === 'string' ? input : decodeUtf8(input, label, 4 * 1024 * 1024);
  let parsed;
  try { parsed = JSON.parse(value); } catch { fail(`${label} is malformed`); }
  if (compactCanonical(parsed) !== value) fail(`${label} is not compact canonical JSON`);
  return parsed;
};

const expectedComparison = {
  counters: ['LINE', 'BRANCH'], zeroDenominatorBasisPoints: null,
  line: { missed: 'NON_INCREASING', basisPoints: 'NON_DECREASING' },
  branch: { missed: 'NON_INCREASING', basisPoints: 'NON_DECREASING' },
  newSource: 'REVIEW_REQUIRED', removedSource: 'TERMINAL_DISPOSITION_REQUIRED',
  changedMissingSource: 'FAIL', unchangedReviewedMissingSource: 'ALLOW_EXACT_PATH_SHA_ONLY',
  rename: 'EXACT_REVIEWED_OLD_NEW_SAME_SHA_ONLY', baselineRewrite: 'EXPLICIT_REVIEWED_PR_ONLY',
};
export function validatePolicy(policy) {
  exactKeys(policy, ['schemaVersion', 'artifactKind', 'policyId', 'issue', 'repository', 'phase', 'toolchain', 'scopeRules', 'exclusions', 'comparison', 'resultContract', 'artifactContract'], 'policy');
  if (policy.schemaVersion !== 1 || policy.artifactKind !== 'backend-critical-coverage-policy-v1' || policy.policyId !== 'backend-critical-coverage' || policy.repository !== REPOSITORY || ![POLICY_PHASE_A, POLICY_PHASE_B].includes(policy.phase)) fail('policy identity mismatch');
  exactKeys(policy.issue, ['number', 'url'], 'policy issue');
  if (policy.issue.number !== 32 || policy.issue.url !== ISSUE_URL) fail('policy issue mismatch');
  exactKeys(policy.toolchain, ['gradleVersion', 'javaVendor', 'javaLanguageVersion', 'jacocoVersion', 'testTask', 'reportTask', 'reportPath'], 'policy toolchain');
  if (JSON.stringify(policy.toolchain) !== JSON.stringify({ gradleVersion: '8.14.5', javaVendor: 'ADOPTIUM', javaLanguageVersion: 21, jacocoVersion: '0.8.13', testTask: 'test', reportTask: 'jacocoTestReport', reportPath: REPORT_PATH })) fail('policy toolchain mismatch');
  if (JSON.stringify(policy.scopeRules) !== JSON.stringify(SCOPE_RULES)) fail('policy scope rules mismatch');
  if (JSON.stringify(policy.exclusions) !== JSON.stringify([EXCLUSION])) fail('policy exclusion mismatch');
  if (JSON.stringify(policy.comparison) !== JSON.stringify(expectedComparison)) fail('policy comparison mismatch');
  exactKeys(policy.resultContract, ['schemaVersion', 'artifactKind', 'resultFile', 'summaryFile', 'digestFile', 'reasonOrder'], 'result contract');
  if (policy.resultContract.schemaVersion !== 1 || policy.resultContract.artifactKind !== 'backend-critical-coverage-result-v1' || policy.resultContract.resultFile !== 'backend-critical-coverage-result.json' || policy.resultContract.summaryFile !== 'backend-critical-coverage-summary.md' || policy.resultContract.digestFile !== 'backend-critical-coverage.sha256') fail('result contract mismatch');
  exactArray(policy.resultContract.reasonOrder, REASONS, 'result reason order');
  exactKeys(policy.artifactContract, ['directoryName', 'files', 'uploadNamePrefix', 'retentionDays', 'ifNoFilesFound'], 'artifact contract');
  if (policy.artifactContract.directoryName !== 'backend-critical-coverage' || policy.artifactContract.uploadNamePrefix !== 'backend-critical-coverage-' || policy.artifactContract.retentionDays !== 5 || policy.artifactContract.ifNoFilesFound !== 'error') fail('artifact contract mismatch');
  exactArray(policy.artifactContract.files, ARTIFACT_FILES, 'artifact files');
  return true;
}

export function validateExclusionPolicy(exclusionPolicy, policy) {
  validatePolicy(policy);
  exactKeys(exclusionPolicy, ['schemaVersion', 'policyId', 'phase', 'reportOnly', 'globalPercentFailGate', 'classDirectoryExcludes', 'exclusions', 'sonarCoverageExclusions', 'notes'], 'JaCoCo exclusion policy');
  if (exclusionPolicy.schemaVersion !== 1 || exclusionPolicy.policyId !== 'backend-jacoco-exclusion' || exclusionPolicy.phase !== policy.phase || exclusionPolicy.reportOnly !== true || exclusionPolicy.globalPercentFailGate !== false) fail('JaCoCo exclusion policy identity mismatch');
  exactArray(exclusionPolicy.classDirectoryExcludes, [EXCLUSION.classFile], 'JaCoCo class exclusions');
  if (JSON.stringify(exclusionPolicy.exclusions) !== JSON.stringify([EXCLUSION])) fail('JaCoCo exclusion metadata mismatch');
  exactArray(exclusionPolicy.sonarCoverageExclusions, ['backend/src/main/resources/**', 'tools/**'], 'Sonar coverage exclusions');
  if (exclusionPolicy.notes !== 'JaCoCo Gradle reporting remains global-percent-free; backend-critical-coverage enforces reviewed decrease-only evidence.') fail('JaCoCo exclusion note mismatch');
  return true;
}

export function validateStaticGate(gate, policy) {
  validatePolicy(policy);
  if (gate === null || typeof gate !== 'object' || !Array.isArray(gate.tools)) fail('static gate tools are missing');
  const matches = gate.tools.filter(({ id }) => id === 'jacoco');
  if (matches.length !== 1) fail('static gate JaCoCo entry mismatch');
  const jacoco = matches[0];
  exactKeys(jacoco, ['id', 'enforcement', 'firstAllowedGate', 'evidence', 'requires'], 'static gate JaCoCo');
  const enforcement = policy.phase === POLICY_PHASE_A ? 'discovery_remote_red' : 'required_fail_closed_decrease_only';
  if (jacoco.enforcement !== enforcement || jacoco.firstAllowedGate !== 'backend-critical-coverage') fail('static gate JaCoCo enforcement mismatch');
  exactKeys(jacoco.evidence, ['coveragePolicy', 'coverageBaseline', 'coverageExclusionPolicy', 'requiredValidator', 'resultArtifact', 'failMode', 'globalPercentFailGate'], 'static gate JaCoCo evidence');
  const expectedFailMode = policy.phase === POLICY_PHASE_A
    ? 'Phase A validates and uploads current evidence, then fails with DISCOVERY_REMOTE_RED; no Gradle global percentage gate'
    : 'Phase B revalidates current evidence and requires decrease-only PASS; no Gradle global percentage gate';
  if (JSON.stringify(jacoco.evidence) !== JSON.stringify({
    coveragePolicy: 'backend/quality/jacoco-coverage-policy.json',
    coverageBaseline: 'backend/quality/jacoco-coverage-baseline.json',
    coverageExclusionPolicy: 'backend/quality/jacoco-exclusion-policy.json',
    requiredValidator: 'tools/ci/backend-coverage-gate.mjs',
    resultArtifact: 'backend-critical-coverage-${GITHUB_SHA}/backend-critical-coverage-result.json',
    failMode: expectedFailMode,
    globalPercentFailGate: false,
  })) fail('static gate JaCoCo evidence mismatch');
  exactArray(jacoco.requires, ['reviewed current critical source inventory', 'exact JaCoCo XML and source identity', 'decrease-only line and branch evidence'], 'static gate JaCoCo requirements');
  return true;
}

export function validateBuildScript(buildScript, wrapperProperties) {
  if (typeof buildScript !== 'string' || typeof wrapperProperties !== 'string') fail('build producer source is invalid');
  for (const literal of [
    "toolVersion = '0.8.13'",
    "'com/easysubway/EasySubwayBackendApplication.class'",
    "tasks.register('writeJacocoCoverageEvidence')",
    "layout.buildDirectory.file('jacoco/jacocoTestReport-evidence.json')",
    'outputs.upToDateWhen { false }',
    "tasks.named('jacocoTestReport')",
    'finalizedBy writeJacocoCoverageEvidence',
    "vendor: 'ADOPTIUM'",
    'classDirectoryExcludes: jacocoClassDirectoryExcludes',
  ]) if (!buildScript.includes(literal)) fail(`build producer missing ${literal}`);
  if (buildScript.includes("'**/*Application.class'")) fail('build producer retains broad Application exclusion');
  const wrapperMatches = [...wrapperProperties.matchAll(/distributionUrl=.*gradle-([0-9.]+)-bin\.zip/g)];
  if (wrapperMatches.length !== 1 || wrapperMatches[0][1] !== '8.14.5') fail('Gradle wrapper version mismatch');
  return true;
}

const renderTopProperty = (key, value, comma) => {
  const lines = JSON.stringify(value, null, 2).split('\n');
  lines[0] = `  ${JSON.stringify(key)}: ${lines[0]}`;
  for (let index = 1; index < lines.length; index += 1) lines[index] = `  ${lines[index]}`;
  if (comma) lines[lines.length - 1] += ',';
  return lines;
};
export const serializeBaseline = (baseline) => {
  const keys = ['schemaVersion', 'artifactKind', 'repository', 'phase', 'provenance', 'producer', 'scope', 'exclusions', 'sources', 'boundaries', 'renames'];
  const lines = ['{'];
  keys.forEach((key, index) => {
    const comma = index < keys.length - 1;
    if (key === 'sources' && Array.isArray(baseline.sources) && baseline.sources.length > 0) {
      lines.push('  "sources": [');
      baseline.sources.forEach((source, sourceIndex) => lines.push(`    ${JSON.stringify(source)}${sourceIndex < baseline.sources.length - 1 ? ',' : ''}`));
      lines.push(`  ]${comma ? ',' : ''}`);
    } else lines.push(...renderTopProperty(key, baseline[key], comma));
  });
  lines.push('}');
  return `${lines.join('\n')}\n`;
};
export const parseBaselineBytes = (input) => {
  const value = typeof input === 'string' ? input : decodeUtf8(input, 'baseline', 32 * 1024 * 1024);
  let parsed;
  try { parsed = JSON.parse(value); } catch { fail('baseline is malformed'); }
  if (serializeBaseline(parsed) !== value) fail('baseline is not canonical JSON');
  return parsed;
};

const validateSourceRow = (source, policy, label) => {
  exactKeys(source, ['path', 'sha256', 'packageName', 'sourceFileName', 'boundaryIds', 'reportPresence', 'absenceDisposition', 'line', 'branch'], label);
  safePath(source.path, `${label}.path`); sha(source.sha256, `${label}.sha256`); text(source.packageName, `${label}.packageName`); text(source.sourceFileName, `${label}.sourceFileName`);
  if (`${SOURCE_ROOT}${source.packageName.replaceAll('.', '/')}/${source.sourceFileName}` !== source.path || !/^[A-Za-z0-9_$]+\.java$/.test(source.sourceFileName)) fail(`${label} source identity mismatch`);
  const allowedBoundaries = policy.scopeRules.map(({ id }) => id);
  if (!Array.isArray(source.boundaryIds) || new Set(source.boundaryIds).size !== source.boundaryIds.length || source.boundaryIds.some((id) => !allowedBoundaries.includes(id)) || JSON.stringify(source.boundaryIds) !== JSON.stringify(allowedBoundaries.filter((id) => source.boundaryIds.includes(id)))) fail(`${label} boundary order mismatch`);
  const local = source.path.startsWith(PACKAGE_ROOT) ? source.path.slice(PACKAGE_ROOT.length) : null;
  const expectedBoundaries = local === null ? [] : policy.scopeRules.filter(({ prefixes }) => prefixes.some((prefix) => local.startsWith(prefix))).map(({ id }) => id);
  if (JSON.stringify(source.boundaryIds) !== JSON.stringify(expectedBoundaries)) fail(`${label} boundary membership mismatch`);
  if (source.reportPresence === 'PRESENT') {
    if (source.absenceDisposition !== null) fail(`${label} present disposition mismatch`);
    validateCounter(source.line, `${label}.line`); validateCounter(source.branch, `${label}.branch`);
  } else if (source.reportPresence === 'MISSING') {
    if (source.absenceDisposition !== 'NO_EXECUTABLE_BYTECODE_REVIEWED' || source.line !== null || source.branch !== null || source.boundaryIds.length === 0) fail(`${label} missing disposition mismatch`);
  } else if (source.reportPresence === 'EXCLUDED') {
    if (source.path !== EXCLUSION.sourcePath || source.absenceDisposition !== EXCLUSION.id || source.line !== null || source.branch !== null || source.boundaryIds.length !== 0) fail(`${label} excluded disposition mismatch`);
  } else fail(`${label} report presence mismatch`);
};
const validateBoundary = (boundary, policy, index, label) => {
  exactKeys(boundary, ['id', 'sourceCount', 'reportedSourceCount', 'missingSourceCount', 'line', 'branch'], label);
  if (boundary.id !== policy.scopeRules[index]?.id) fail(`${label} identity/order mismatch`);
  for (const key of ['sourceCount', 'reportedSourceCount', 'missingSourceCount']) safeInteger(boundary[key], `${label}.${key}`);
  if (boundary.sourceCount !== boundary.reportedSourceCount + boundary.missingSourceCount) fail(`${label} counts mismatch`);
  validateCounter(boundary.line, `${label}.line`); validateCounter(boundary.branch, `${label}.branch`);
};
export function validateBaseline(baseline, policy) {
  validatePolicy(policy);
  exactKeys(baseline, ['schemaVersion', 'artifactKind', 'repository', 'phase', 'provenance', 'producer', 'scope', 'exclusions', 'sources', 'boundaries', 'renames'], 'baseline');
  if (baseline.schemaVersion !== 1 || baseline.artifactKind !== 'backend-critical-coverage-baseline-v1' || baseline.repository !== REPOSITORY) fail('baseline identity mismatch');
  if (policy.phase === POLICY_PHASE_A) {
    if (baseline.phase !== BASELINE_PHASE_A || baseline.provenance !== null || baseline.producer !== null || baseline.scope !== null || JSON.stringify(baseline.exclusions) !== '[]' || JSON.stringify(baseline.sources) !== '[]' || JSON.stringify(baseline.boundaries) !== '[]' || JSON.stringify(baseline.renames) !== '[]') fail('discovery baseline must be empty');
    return true;
  }
  if (policy.phase !== POLICY_PHASE_B || baseline.phase !== BASELINE_PHASE_B) fail('policy/baseline phase mismatch');
  exactKeys(baseline.provenance, ['runUrl', 'artifactId', 'testedMergeSha', 'pullRequestHeadSha', 'resultSha256', 'rawXmlSha256', 'reviewedAt'], 'baseline provenance');
  if (!/^https:\/\/github\.com\/AquilaXk\/easysubway-backend\/actions\/runs\/[1-9]\d*$/.test(baseline.provenance.runUrl) || !/^[1-9]\d*$/.test(String(baseline.provenance.artifactId)) || commitSha(baseline.provenance.testedMergeSha, 'baseline tested merge') === commitSha(baseline.provenance.pullRequestHeadSha, 'baseline PR head') || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(baseline.provenance.reviewedAt)) fail('baseline provenance mismatch');
  sha(baseline.provenance.resultSha256, 'baseline result digest'); sha(baseline.provenance.rawXmlSha256, 'baseline raw XML digest');
  exactKeys(baseline.producer, ['gradleVersion', 'javaVendor', 'javaLanguageVersion', 'javaLauncherSha256', 'jacocoVersion', 'testTask', 'reportTask', 'policySha256', 'sourceInventorySha256'], 'baseline producer');
  if (baseline.producer.gradleVersion !== '8.14.5' || baseline.producer.javaVendor !== 'ADOPTIUM' || baseline.producer.javaLanguageVersion !== 21 || baseline.producer.jacocoVersion !== '0.8.13' || baseline.producer.testTask !== 'test' || baseline.producer.reportTask !== 'jacocoTestReport') fail('baseline producer mismatch');
  for (const key of ['javaLauncherSha256', 'policySha256', 'sourceInventorySha256']) sha(baseline.producer[key], `baseline producer ${key}`);
  exactKeys(baseline.scope, ['criticalSourceCount', 'reportedSourceCount', 'missingSourceCount', 'excludedSourceCount'], 'baseline scope');
  for (const key of Object.keys(baseline.scope)) safeInteger(baseline.scope[key], `baseline scope ${key}`);
  if (baseline.scope.criticalSourceCount !== baseline.scope.reportedSourceCount + baseline.scope.missingSourceCount || baseline.scope.excludedSourceCount !== 1) fail('baseline scope counts mismatch');
  if (!Array.isArray(baseline.sources) || baseline.sources.length !== baseline.scope.criticalSourceCount + 1) fail('baseline source count mismatch');
  const paths = baseline.sources.map(({ path }) => path);
  if (new Set(paths).size !== paths.length || JSON.stringify(paths) !== JSON.stringify([...paths].sort((left, right) => left.localeCompare(right)))) fail('baseline sources must be unique and sorted');
  baseline.sources.forEach((source, index) => validateSourceRow(source, policy, `baseline source ${index}`));
  if (!Array.isArray(baseline.exclusions) || baseline.exclusions.length !== 1) fail('baseline exclusion count mismatch');
  exactKeys(baseline.exclusions[0], ['id', 'sourcePath', 'classFile', 'sha256'], 'baseline exclusion');
  if (baseline.exclusions[0].id !== EXCLUSION.id || baseline.exclusions[0].sourcePath !== EXCLUSION.sourcePath || baseline.exclusions[0].classFile !== EXCLUSION.classFile) fail('baseline exclusion mismatch');
  sha(baseline.exclusions[0].sha256, 'baseline exclusion digest');
  const excluded = baseline.sources.find(({ reportPresence }) => reportPresence === 'EXCLUDED');
  if (!excluded || excluded.sha256 !== baseline.exclusions[0].sha256 || baseline.sources.filter(({ reportPresence }) => reportPresence === 'PRESENT').length !== baseline.scope.reportedSourceCount || baseline.sources.filter(({ reportPresence }) => reportPresence === 'MISSING').length !== baseline.scope.missingSourceCount) fail('baseline source projection mismatch');
  const sourceIdentity = baseline.sources.map(({ path, sha256, boundaryIds }) => ({ path, sha256, boundaryIds }));
  if (baseline.producer.sourceInventorySha256 !== digest(compactCanonical(sourceIdentity))) fail('baseline source inventory digest mismatch');
  if (!Array.isArray(baseline.boundaries) || baseline.boundaries.length !== policy.scopeRules.length) fail('baseline boundary count mismatch');
  baseline.boundaries.forEach((boundary, index) => validateBoundary(boundary, policy, index, `baseline boundary ${index}`));
  for (const [index, rule] of policy.scopeRules.entries()) {
    const members = baseline.sources.filter(({ boundaryIds }) => boundaryIds.includes(rule.id));
    const present = members.filter(({ reportPresence }) => reportPresence === 'PRESENT');
    const expected = { id: rule.id, sourceCount: members.length, reportedSourceCount: present.length, missingSourceCount: members.length - present.length, line: sumCounters(present.map(({ line }) => line)), branch: sumCounters(present.map(({ branch }) => branch)) };
    if (JSON.stringify(baseline.boundaries[index]) !== JSON.stringify(expected)) fail(`baseline boundary ${rule.id} projection mismatch`);
  }
  if (!Array.isArray(baseline.renames)) fail('baseline renames must be an array');
  const renamePaths = new Set();
  baseline.renames.forEach((rename, index) => {
    exactKeys(rename, ['oldPath', 'newPath', 'sha256', 'ownerIssueUrl', 'reason'], `baseline rename ${index}`);
    safePath(rename.oldPath, 'rename old path'); safePath(rename.newPath, 'rename new path'); sha(rename.sha256, 'rename digest'); text(rename.reason, 'rename reason');
    if (rename.oldPath === rename.newPath || rename.ownerIssueUrl !== ISSUE_URL || renamePaths.has(rename.oldPath) || renamePaths.has(rename.newPath)) fail('baseline rename mapping mismatch');
    renamePaths.add(rename.oldPath); renamePaths.add(rename.newPath);
  });
  return true;
}

const decodeEntities = (value) => {
  if (/&(?!lt;|gt;|amp;|quot;|apos;)/.test(value)) fail('XML entity is invalid');
  return value.replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&apos;', "'");
};
const tokenizeXml = (xml) => {
  const declaration = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  const doctype = '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">';
  const prefix = new RegExp(`^${declaration.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*${doctype.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*`);
  const matched = prefix.exec(xml);
  if (!matched || /<!ENTITY|<!\[CDATA\[|<!DOCTYPE/.test(xml.slice(matched[0].length))) fail('JaCoCo XML declaration or doctype mismatch');
  const body = xml.slice(matched[0].length), document = { name: '#document', attrs: {}, children: [] }, stack = [document];
  let cursor = 0;
  const tagEnd = (start) => {
    let quoted = false;
    for (let index = start + 1; index < body.length; index += 1) {
      if (body[index] === '"') quoted = !quoted;
      else if (body[index] === '<') fail('XML tag is malformed');
      else if (body[index] === '>' && !quoted) return index;
    }
    fail('XML tag is unterminated');
  };
  while (cursor < body.length) {
    const start = body.indexOf('<', cursor);
    const between = start < 0 ? body.slice(cursor) : body.slice(cursor, start);
    if (between.trim() !== '' || between.includes(']]>') || between.includes('&')) fail('XML text is not allowed');
    if (start < 0) { cursor = body.length; break; }
    if (body.startsWith('<!--', start)) {
      const end = body.indexOf('-->', start + 4);
      if (end < 0 || !/^<!--(?:[^-]|-(?!-))*-->$/.test(body.slice(start, end + 3))) fail('XML comment is malformed');
      cursor = end + 3; continue;
    }
    const end = tagEnd(start), raw = body.slice(start, end + 1); cursor = end + 1;
    const close = /^<\/([A-Za-z][A-Za-z0-9_.-]*)\s*>$/.exec(raw);
    if (close) { if (stack.length === 1 || stack.pop().name !== close[1]) fail('XML tags are mismatched'); continue; }
    const open = /^<([A-Za-z][A-Za-z0-9_.-]*)((?:\s+[A-Za-z][A-Za-z0-9_.-]*="(?:[^"<&]|&(?:lt|gt|amp|quot|apos);)*")*)\s*(\/?)>$/.exec(raw);
    if (!open || stack.length === 1 && document.children.length > 0) fail('XML token is malformed');
    const attrs = {};
    for (const match of open[2].matchAll(/\s+([A-Za-z][A-Za-z0-9_.-]*)="((?:[^"<&]|&(?:lt|gt|amp|quot|apos);)*)"/g)) {
      if (Object.hasOwn(attrs, match[1])) fail('duplicate XML attribute');
      attrs[match[1]] = decodeEntities(match[2]);
    }
    const node = { name: open[1], attrs, children: [] }; stack.at(-1).children.push(node);
    if (open[3] !== '/') stack.push(node);
  }
  if (stack.length !== 1 || document.children.length !== 1) fail('XML root is malformed');
  return document.children[0];
};
const childNames = (node, allowed, label) => { if (node.children.some(({ name }) => !allowed.includes(name))) fail(`${label} has unknown child`); };
const attributes = (node, allowed, required, label) => {
  if (Object.keys(node.attrs).some((key) => !allowed.includes(key)) || required.some((key) => !Object.hasOwn(node.attrs, key))) fail(`${label} attributes mismatch`);
};
const xmlInteger = (value, label) => { if (!/^(?:0|[1-9]\d*)$/.test(value ?? '') || Number(value) > Number.MAX_SAFE_INTEGER) fail(`${label} integer is invalid`); return Number(value); };
const counterMap = (node, label) => {
  const result = new Map();
  for (const item of node.children.filter(({ name }) => name === 'counter')) {
    attributes(item, ['type', 'missed', 'covered'], ['type', 'missed', 'covered'], `${label} counter`);
    if (item.children.length !== 0 || !COUNTER_TYPES.includes(item.attrs.type) || result.has(item.attrs.type)) fail(`${label} counter is invalid or duplicate`);
    result.set(item.attrs.type, counter(xmlInteger(item.attrs.missed, `${label} missed`), xmlInteger(item.attrs.covered, `${label} covered`)));
  }
  return result;
};
const equalCounter = (actual, expected, label) => { if (JSON.stringify(actual) !== JSON.stringify(expected)) fail(`${label} counter mismatch`); };
const equalOrOmittedZeroCounter = (counters, type, expected, label) => {
  if (counters.has(type)) equalCounter(counters.get(type), expected, label);
  else if (expected.total !== 0) fail(`${label} counter is missing`);
};

export function parseJacocoReport(xml) {
  if (typeof xml !== 'string' || Buffer.byteLength(xml) === 0 || Buffer.byteLength(xml) > 64 * 1024 * 1024 || xml.includes('\0') || xml.startsWith('\uFEFF')) fail('JaCoCo XML bytes are invalid');
  const root = tokenizeXml(xml);
  if (root.name !== 'report') fail('JaCoCo XML requires report root');
  attributes(root, ['name'], ['name'], 'report'); childNames(root, ['sessioninfo', 'package', 'counter'], 'report');
  text(root.attrs.name, 'report name');
  for (const session of root.children.filter(({ name }) => name === 'sessioninfo')) {
    attributes(session, ['id', 'start', 'dump'], ['id', 'start', 'dump'], 'sessioninfo');
    if (session.children.length !== 0) fail('sessioninfo must be empty'); text(session.attrs.id, 'session id');
    const start = xmlInteger(session.attrs.start, 'session start'), dump = xmlInteger(session.attrs.dump, 'session dump'); if (dump < start) fail('session timestamp order mismatch');
  }
  const packages = root.children.filter(({ name }) => name === 'package'), packageNames = new Set(), sources = [];
  for (const packageNode of packages) {
    attributes(packageNode, ['name'], ['name'], 'package'); childNames(packageNode, ['class', 'sourcefile', 'counter'], 'package');
    if (!/^[A-Za-z_$][A-Za-z0-9_$]*(?:\/[A-Za-z_$][A-Za-z0-9_$]*)*$/.test(packageNode.attrs.name) || packageNames.has(packageNode.attrs.name)) fail('package name is invalid or duplicate');
    packageNames.add(packageNode.attrs.name);
    const classNames = new Set();
    for (const classNode of packageNode.children.filter(({ name }) => name === 'class')) {
      attributes(classNode, ['name', 'sourcefilename'], ['name'], 'class'); childNames(classNode, ['method', 'counter'], 'class');
      if (classNames.has(classNode.attrs.name) || !classNode.attrs.name.startsWith(`${packageNode.attrs.name}/`) || classNode.attrs.sourcefilename !== undefined && !/^[A-Za-z0-9_$]+\.java$/.test(classNode.attrs.sourcefilename)) fail('class identity is invalid or duplicate');
      classNames.add(classNode.attrs.name); counterMap(classNode, 'class');
      const methods = new Set();
      for (const method of classNode.children.filter(({ name }) => name === 'method')) {
        attributes(method, ['name', 'desc', 'line'], ['name', 'desc'], 'method'); childNames(method, ['counter'], 'method');
        text(method.attrs.name, 'method name'); text(method.attrs.desc, 'method descriptor'); if (method.attrs.line !== undefined) xmlInteger(method.attrs.line, 'method line');
        const identity = `${method.attrs.name}\0${method.attrs.desc}`; if (methods.has(identity)) fail('method identity is duplicate'); methods.add(identity); counterMap(method, 'method');
      }
    }
    const sourceNames = new Set(), packageSourceCounters = [];
    for (const sourceNode of packageNode.children.filter(({ name }) => name === 'sourcefile')) {
      attributes(sourceNode, ['name'], ['name'], 'sourcefile'); childNames(sourceNode, ['line', 'counter'], 'sourcefile');
      if (!/^[A-Za-z0-9_$]+\.java$/.test(sourceNode.attrs.name) || sourceNames.has(sourceNode.attrs.name)) fail('sourcefile identity is invalid or duplicate');
      sourceNames.add(sourceNode.attrs.name);
      let lineMissed = 0, lineCovered = 0, branchMissed = 0, branchCovered = 0; const lineNumbers = new Set();
      for (const lineNode of sourceNode.children.filter(({ name }) => name === 'line')) {
        attributes(lineNode, ['nr', 'mi', 'ci', 'mb', 'cb'], ['nr', 'mi', 'ci', 'mb', 'cb'], 'line');
        if (lineNode.children.length !== 0) fail('line must be empty');
        const nr = xmlInteger(lineNode.attrs.nr, 'line number'); if (nr === 0 || lineNumbers.has(nr)) fail('line number is invalid or duplicate'); lineNumbers.add(nr);
        const mi = xmlInteger(lineNode.attrs.mi, 'missed instructions'), ci = xmlInteger(lineNode.attrs.ci, 'covered instructions');
        branchMissed += xmlInteger(lineNode.attrs.mb, 'missed branches'); branchCovered += xmlInteger(lineNode.attrs.cb, 'covered branches');
        if (ci > 0) lineCovered += 1; else if (mi > 0) lineMissed += 1; else fail('line has no executable instructions');
      }
      const counters = counterMap(sourceNode, 'sourcefile'), lineValue = counter(lineMissed, lineCovered), branchValue = counter(branchMissed, branchCovered);
      equalOrOmittedZeroCounter(counters, 'LINE', lineValue, 'sourcefile LINE');
      equalOrOmittedZeroCounter(counters, 'BRANCH', branchValue, 'sourcefile BRANCH');
      const normalized = { packageName: packageNode.attrs.name.replaceAll('/', '.'), sourceFileName: sourceNode.attrs.name, line: lineValue, branch: branchValue };
      sources.push(normalized); packageSourceCounters.push(normalized);
    }
    const counters = counterMap(packageNode, 'package');
    const expectedLine = sumCounters(packageSourceCounters.map(({ line }) => line)), expectedBranch = sumCounters(packageSourceCounters.map(({ branch }) => branch));
    equalOrOmittedZeroCounter(counters, 'LINE', expectedLine, 'package LINE');
    equalOrOmittedZeroCounter(counters, 'BRANCH', expectedBranch, 'package BRANCH');
  }
  const rootCounters = counterMap(root, 'report'), expectedLine = sumCounters(sources.map(({ line }) => line)), expectedBranch = sumCounters(sources.map(({ branch }) => branch));
  equalOrOmittedZeroCounter(rootCounters, 'LINE', expectedLine, 'report LINE');
  equalOrOmittedZeroCounter(rootCounters, 'BRANCH', expectedBranch, 'report BRANCH');
  sources.sort((left, right) => `${left.packageName}/${left.sourceFileName}`.localeCompare(`${right.packageName}/${right.sourceFileName}`));
  return { sources, line: expectedLine, branch: expectedBranch };
}

const git = (root, args) => execFileSync('git', ['-C', root, ...args], { encoding: 'buffer', maxBuffer: 32 * 1024 * 1024 });
export function inventorySources(repositoryRoot, policy, { runGit = git } = {}) {
  validatePolicy(policy);
  const root = realpathSync(repositoryRoot), output = decodeUtf8(runGit(root, ['ls-files', '-s', '-z', '--', 'backend/src/main/java']), 'tracked source inventory', 32 * 1024 * 1024, true);
  const all = [];
  for (const record of output.split('\0')) {
    if (record === '') continue;
    const match = /^(\d{6}) ([a-f0-9]{40,64}) (\d)\t(.+)$/.exec(record);
    if (!match) fail('tracked source inventory record is malformed');
    const [, mode, , stage, path] = match;
    if (!path.endsWith('.java')) continue;
    safePath(path, 'tracked Java source');
    if (stage !== '0') fail('tracked Java source has nonzero stage');
    const localPath = path.startsWith(PACKAGE_ROOT) ? path.slice(PACKAGE_ROOT.length) : null;
    const isCritical = localPath !== null && policy.scopeRules.some(({ prefixes }) => prefixes.some((prefix) => localPath.startsWith(prefix)));
    if (mode !== '100644') {
      if (isCritical || path === EXCLUSION.sourcePath) fail('scoped Java source is not mode 100644');
      continue;
    }
    const absolute = resolve(root, path); if (!isWithin(root, absolute)) fail('tracked Java source escapes repository');
    regularFile(absolute, 'tracked Java source');
    if (!isWithin(root, realpathSync(absolute))) fail('tracked Java source resolves outside repository');
    const relativePackage = path.slice(SOURCE_ROOT.length), slash = relativePackage.lastIndexOf('/');
    if (!path.startsWith(SOURCE_ROOT) || slash <= 0) fail('tracked Java source package path is invalid');
    all.push({ path, sha256: digest(readFileSync(absolute)), packageName: relativePackage.slice(0, slash).replaceAll('/', '.'), sourceFileName: relativePackage.slice(slash + 1) });
  }
  all.sort((left, right) => left.path.localeCompare(right.path));
  const scoped = [];
  for (const source of all) {
    const local = source.path.startsWith(PACKAGE_ROOT) ? source.path.slice(PACKAGE_ROOT.length) : null;
    const boundaryIds = local === null ? [] : policy.scopeRules.filter(({ prefixes }) => prefixes.some((prefix) => local.startsWith(prefix))).map(({ id }) => id);
    if (boundaryIds.length > 0 || source.path === EXCLUSION.sourcePath) scoped.push({ ...source, boundaryIds });
  }
  if (!scoped.some(({ path }) => path === EXCLUSION.sourcePath)) fail('entrypoint exclusion source is missing');
  return { root, all, scoped };
}

export function projectCoverage(policy, report, inventory) {
  validatePolicy(policy);
  if (!Array.isArray(report.sources) || !Array.isArray(inventory.all) || !Array.isArray(inventory.scoped)) fail('coverage projection inputs are invalid');
  const tracked = new Map(inventory.all.map((source) => [`${source.packageName}\0${source.sourceFileName}`, source]));
  const reported = new Map();
  for (const source of report.sources) {
    const key = `${source.packageName}\0${source.sourceFileName}`;
    if (!tracked.has(key) || reported.has(key)) fail('JaCoCo report source is unknown or ambiguous');
    reported.set(key, source);
  }
  const sources = inventory.scoped.map((source) => {
    if (source.path === EXCLUSION.sourcePath) return { ...source, reportPresence: 'EXCLUDED', absenceDisposition: EXCLUSION.id, line: null, branch: null };
    const reportSource = reported.get(`${source.packageName}\0${source.sourceFileName}`);
    return reportSource ? { ...source, reportPresence: 'PRESENT', absenceDisposition: null, line: reportSource.line, branch: reportSource.branch } : { ...source, reportPresence: 'MISSING', absenceDisposition: null, line: null, branch: null };
  });
  const critical = sources.filter(({ reportPresence }) => reportPresence !== 'EXCLUDED'), present = critical.filter(({ reportPresence }) => reportPresence === 'PRESENT');
  const coverage = { sourceCount: critical.length, reportedSourceCount: present.length, missingSourceCount: critical.length - present.length, line: sumCounters(present.map(({ line }) => line)), branch: sumCounters(present.map(({ branch }) => branch)) };
  const boundaries = policy.scopeRules.map(({ id }) => {
    const members = critical.filter(({ boundaryIds }) => boundaryIds.includes(id)), covered = members.filter(({ reportPresence }) => reportPresence === 'PRESENT');
    return { id, sourceCount: members.length, reportedSourceCount: covered.length, missingSourceCount: members.length - covered.length, line: sumCounters(covered.map(({ line }) => line)), branch: sumCounters(covered.map(({ branch }) => branch)) };
  });
  const identity = sources.map(({ path, sha256, boundaryIds }) => ({ path, sha256, boundaryIds }));
  return { sources, coverage, boundaries, sourceInventorySha256: digest(compactCanonical(identity)), exclusions: [{ id: EXCLUSION.id, sourcePath: EXCLUSION.sourcePath, classFile: EXCLUSION.classFile, sha256: sources.find(({ path }) => path === EXCLUSION.sourcePath).sha256 }] };
}

export function validateGradleEvidence(evidence, { policy, repositoryRoot, javaHome, rawXmlSha256 }) {
  exactKeys(evidence, ['schemaVersion', 'gradleVersion', 'java', 'jacoco', 'tasks', 'report'], 'Gradle evidence');
  if (evidence.schemaVersion !== 1 || evidence.gradleVersion !== policy.toolchain.gradleVersion) fail('Gradle evidence mismatch');
  exactKeys(evidence.java, ['vendor', 'languageVersion', 'launcherPath', 'launcherSha256'], 'Gradle Java evidence');
  if (evidence.java.vendor !== 'ADOPTIUM' || evidence.java.languageVersion !== 21) fail('Gradle Java evidence mismatch');
  const root = realpathSync(repositoryRoot), home = realpathSync(javaHome), launcher = resolve(evidence.java.launcherPath);
  if (launcher !== resolve(home, 'bin/java') || !isWithin(home, launcher)) fail('Java launcher path mismatch'); regularFile(launcher, 'Java launcher');
  if (digest(readFileSync(launcher)) !== evidence.java.launcherSha256) fail('Java launcher digest mismatch');
  exactKeys(evidence.jacoco, ['toolVersion'], 'Gradle JaCoCo evidence'); if (evidence.jacoco.toolVersion !== policy.toolchain.jacocoVersion) fail('JaCoCo version mismatch');
  exactKeys(evidence.tasks, ['test', 'report'], 'Gradle task evidence'); if (evidence.tasks.test !== 'test' || evidence.tasks.report !== 'jacocoTestReport') fail('Gradle task evidence mismatch');
  exactKeys(evidence.report, ['path', 'sha256', 'classDirectoryExcludes'], 'Gradle report evidence');
  if (evidence.report.path !== REPORT_PATH || evidence.report.sha256 !== rawXmlSha256 || JSON.stringify(evidence.report.classDirectoryExcludes) !== JSON.stringify([EXCLUSION.classFile])) fail('Gradle report evidence mismatch');
  if (resolve(root, evidence.report.path) !== resolve(root, REPORT_PATH)) fail('Gradle report path mismatch');
  return { gradleVersion: evidence.gradleVersion, javaVendor: evidence.java.vendor, javaLanguageVersion: evidence.java.languageVersion, javaLauncherSha256: evidence.java.launcherSha256, jacocoVersion: evidence.jacoco.toolVersion, testTask: evidence.tasks.test, reportTask: evidence.tasks.report };
}

const baselineSourceForCurrent = (baseline, currentPath) => {
  const direct = baseline.sources.find(({ path }) => path === currentPath);
  if (direct) return direct;
  const rename = baseline.renames.find(({ newPath }) => newPath === currentPath);
  return rename ? baseline.sources.find(({ path }) => path === rename.oldPath) ?? null : null;
};
const applyReviewedMissingDispositions = (projection, baseline) => ({
  ...projection,
  sources: projection.sources.map((source) => {
    if (source.reportPresence !== 'MISSING') return source;
    const reviewed = baselineSourceForCurrent(baseline, source.path);
    return reviewed?.reportPresence === 'MISSING' && reviewed.sha256 === source.sha256
      ? { ...source, absenceDisposition: 'NO_EXECUTABLE_BYTECODE_REVIEWED' }
      : source;
  }),
});
export function compareBaseline({ policy, baseline, projection, producer }) {
  const flags = Object.fromEntries(REASONS.map((reason) => [reason, false]));
  const currentByPath = new Map(projection.sources.map((source) => [source.path, source])), baselineByPath = new Map(baseline.sources.map((source) => [source.path, source]));
  const renamedSources = [], acceptedOld = new Set(), acceptedNew = new Set();
  for (const rename of baseline.renames) {
    const oldSource = baselineByPath.get(rename.oldPath), current = currentByPath.get(rename.newPath);
    if (!oldSource || currentByPath.has(rename.oldPath) || !current || oldSource.sha256 !== rename.sha256 || current.sha256 !== rename.sha256) flags.SOURCE_RENAME_UNREVIEWED = true;
    else { renamedSources.push(rename); acceptedOld.add(rename.oldPath); acceptedNew.add(rename.newPath); }
  }
  const newSources = projection.sources.filter(({ path }) => !baselineByPath.has(path) && !acceptedNew.has(path)).map(({ path }) => path).sort();
  const removedSources = baseline.sources.filter(({ path }) => !currentByPath.has(path) && !acceptedOld.has(path)).map(({ path }) => path).sort();
  if (newSources.length) flags.NEW_CRITICAL_SOURCE = true;
  if (removedSources.length) flags.REMOVED_CRITICAL_SOURCE = true;
  const changedMissingSources = [];
  const historicalProducer = baseline.producer;
  if (historicalProducer.gradleVersion !== producer.gradleVersion || historicalProducer.javaVendor !== producer.javaVendor || historicalProducer.javaLanguageVersion !== producer.javaLanguageVersion || historicalProducer.javaLauncherSha256 !== producer.javaLauncherSha256 || historicalProducer.jacocoVersion !== producer.jacocoVersion || historicalProducer.testTask !== producer.testTask || historicalProducer.reportTask !== producer.reportTask) flags.PRODUCER_DRIFT = true;
  if (baseline.exclusions[0].sha256 !== projection.exclusions[0].sha256) flags.EXCLUSION_DRIFT = true;
  for (const current of projection.sources) {
    if (current.reportPresence === 'EXCLUDED') continue;
    const previous = baselineSourceForCurrent(baseline, current.path); if (!previous) { if (current.reportPresence === 'MISSING') flags.MISSING_REPORT_SOURCE = true; continue; }
    if (previous.reportPresence === 'MISSING') {
      if (previous.sha256 !== current.sha256) { flags.CHANGED_REVIEWED_MISSING_SOURCE = true; changedMissingSources.push(current.path); }
      if (current.reportPresence === 'PRESENT') flags.NEWLY_MEASURABLE_SOURCE = true;
      continue;
    }
    if (current.reportPresence !== 'PRESENT') { flags.MISSING_REPORT_SOURCE = true; continue; }
    if (current.line.missed > previous.line.missed) flags.LINE_MISSED_INCREASE = true;
    if (current.line.basisPoints < previous.line.basisPoints) flags.LINE_RATIO_DECREASE = true;
    if (previous.branch.total === 0 && current.branch.total > 0) flags.NEWLY_MEASURABLE_BRANCH = true;
    else {
      if (current.branch.missed > previous.branch.missed) flags.BRANCH_MISSED_INCREASE = true;
      if (previous.branch.basisPoints !== null && current.branch.basisPoints < previous.branch.basisPoints) flags.BRANCH_RATIO_DECREASE = true;
    }
  }
  for (const [index, current] of projection.boundaries.entries()) {
    const previous = baseline.boundaries[index];
    if (!previous || previous.id !== current.id) { flags.NEW_CRITICAL_SOURCE = true; continue; }
    if (current.line.missed > previous.line.missed || previous.line.basisPoints !== null && current.line.basisPoints < previous.line.basisPoints) flags.BOUNDARY_LINE_DECREASE = true;
    if (previous.branch.total === 0 && current.branch.total > 0) flags.NEWLY_MEASURABLE_BRANCH = true;
    else if (current.branch.missed > previous.branch.missed || previous.branch.basisPoints !== null && current.branch.basisPoints < previous.branch.basisPoints) flags.BOUNDARY_BRANCH_DECREASE = true;
  }
  return { flags, comparison: { baselinePhase: baseline.phase, baselineSourceCount: baseline.scope.criticalSourceCount, newSources, removedSources, renamedSources, changedMissingSources } };
}
export function deriveDecision({ phase, reasonOrder = REASONS, flags = {} }) {
  exactArray(reasonOrder, REASONS, 'decision reason order');
  if (phase === POLICY_PHASE_A) return { outcome: 'DISCOVERY_REMOTE_RED', reasons: ['BASELINE_UNREVIEWED'] };
  if (phase !== POLICY_PHASE_B) fail('decision phase mismatch');
  const reasons = reasonOrder.filter((reason) => flags[reason] === true);
  return { outcome: reasons.length === 0 ? 'PASS' : 'FAIL', reasons };
}

const parseIdentity = ({ event, sourceSha, pullRequestHeadSha, runUrl, repositoryRoot, runGit = git }) => {
  if (!['pull_request', 'push', 'workflow_dispatch'].includes(event)) fail('event identity mismatch');
  commitSha(sourceSha, 'source SHA');
  const head = decodeUtf8(runGit(realpathSync(repositoryRoot), ['rev-parse', 'HEAD']), 'git HEAD', 1024).trim(); if (head !== sourceSha) fail('source SHA is not checked-out HEAD');
  let prHead = null;
  if (event === 'pull_request') { prHead = commitSha(pullRequestHeadSha, 'pull request head SHA'); if (prHead === sourceSha) fail('pull request head equals tested merge'); }
  else if (pullRequestHeadSha !== 'none') fail('non-PR head must be none');
  if (!/^https:\/\/github\.com\/AquilaXk\/easysubway-backend\/actions\/runs\/[1-9]\d*$/.test(runUrl)) fail('run URL mismatch');
  return { event, sourceSha, pullRequestHeadSha: prHead, runUrl };
};

const loadInputs = (
  { repoRoot, policyPath, baselinePath, rawXmlPath, gradleEvidencePath, javaHome, event, sourceSha, pullRequestHeadSha, runUrl, runGit = git },
  { expectedPolicySha256 = POLICY_SHA256, expectedBaselineSha256 = BASELINE_SHA256 } = {},
) => {
  const root = realpathSync(repoRoot);
  const fixedInputs = [
    [policyPath, 'backend/quality/jacoco-coverage-policy.json', 'policy'],
    [baselinePath, 'backend/quality/jacoco-coverage-baseline.json', 'baseline'],
    [rawXmlPath, REPORT_PATH, 'JaCoCo XML'],
    [gradleEvidencePath, EVIDENCE_PATH, 'Gradle evidence'],
  ];
  for (const [provided, expected, label] of fixedInputs) {
    if (resolve(provided) !== resolve(root, expected)) fail(`${label} path mismatch`);
    regularFile(resolve(provided), label);
  }
  const policyBytes = readFileSync(policyPath), baselineBytes = readFileSync(baselinePath), rawXmlBytes = readFileSync(rawXmlPath), evidenceBytes = readFileSync(gradleEvidencePath);
  const policyDigest = digest(policyBytes), baselineDigest = digest(baselineBytes);
  if (policyDigest !== expectedPolicySha256 || baselineDigest !== expectedBaselineSha256) fail('reviewed policy or baseline digest mismatch');
  const policy = parseCanonicalJson(policyBytes, 'policy'), baseline = parseBaselineBytes(baselineBytes); validatePolicy(policy); validateBaseline(baseline, policy);
  const exclusionPolicy = parseCanonicalJson(readFileSync(join(root, 'backend/quality/jacoco-exclusion-policy.json')), 'JaCoCo exclusion policy');
  const staticGate = parseCanonicalJson(readFileSync(join(root, 'backend/quality/static-analysis-gate.json')), 'static analysis gate');
  validateExclusionPolicy(exclusionPolicy, policy); validateStaticGate(staticGate, policy);
  validateBuildScript(readFileSync(join(root, 'backend/build.gradle'), 'utf8'), readFileSync(join(root, 'backend/gradle/wrapper/gradle-wrapper.properties'), 'utf8'));
  validateWorkflow(readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8'));
  const rawXml = decodeUtf8(rawXmlBytes, 'JaCoCo XML'), rawXmlSha256 = digest(rawXmlBytes), report = parseJacocoReport(rawXml);
  const evidence = parseCompactCanonicalJson(evidenceBytes, 'Gradle evidence'), runtime = validateGradleEvidence(evidence, { policy, repositoryRoot: root, javaHome, rawXmlSha256 });
  const inventory = inventorySources(root, policy, { runGit });
  let projection = projectCoverage(policy, report, inventory);
  if (policy.phase === POLICY_PHASE_B) projection = applyReviewedMissingDispositions(projection, baseline);
  const identity = parseIdentity({ event, sourceSha, pullRequestHeadSha, runUrl, repositoryRoot: root, runGit });
  const producer = { ...runtime, policySha256: policyDigest, baselineSha256: baselineDigest, rawXmlSha256, sourceInventorySha256: projection.sourceInventorySha256 };
  let comparison = { baselinePhase: null, baselineSourceCount: null, newSources: [], removedSources: [], renamedSources: [], changedMissingSources: [] }, flags = {};
  if (policy.phase === POLICY_PHASE_B) ({ comparison, flags } = compareBaseline({ policy, baseline, projection, producer }));
  const decision = deriveDecision({ phase: policy.phase, reasonOrder: policy.resultContract.reasonOrder, flags });
  const coverage = { ...projection.coverage, baselineLine: policy.phase === POLICY_PHASE_B ? baseline.sources.filter(({ reportPresence }) => reportPresence === 'PRESENT').length === 0 ? counter(0, 0) : sumCounters(baseline.sources.filter(({ reportPresence }) => reportPresence === 'PRESENT').map(({ line }) => line)) : null, baselineBranch: policy.phase === POLICY_PHASE_B ? sumCounters(baseline.sources.filter(({ reportPresence }) => reportPresence === 'PRESENT').map(({ branch }) => branch)) : null };
  const boundaries = projection.boundaries.map((boundary, index) => ({ ...boundary, baselineLine: policy.phase === POLICY_PHASE_B ? baseline.boundaries[index].line : null, baselineBranch: policy.phase === POLICY_PHASE_B ? baseline.boundaries[index].branch : null }));
  const result = {
    schemaVersion: 1, artifactKind: 'backend-critical-coverage-result-v1', repository: REPOSITORY,
    phase: policy.phase, outcome: decision.outcome, reasons: decision.reasons, identity, producer, comparison,
    coverage, boundaries, exclusions: projection.exclusions,
    inventory: { summary: { inventorySourceCount: projection.sources.length, criticalSourceCount: projection.coverage.sourceCount, reportedSourceCount: projection.coverage.reportedSourceCount, missingSourceCount: projection.coverage.missingSourceCount, excludedSourceCount: 1 }, sources: projection.sources },
    artifacts: { rawXmlFile: 'jacocoTestReport.xml', rawXmlSha256, baselineFile: 'backend-critical-coverage-baseline.json', baselineSha256: baselineDigest, resultFile: 'backend-critical-coverage-result.json', summaryFile: 'backend-critical-coverage-summary.md', digestFile: 'backend-critical-coverage.sha256' },
  };
  return { root, policy, baseline, policyBytes, baselineBytes, rawXmlBytes, result };
};

export const renderSummary = (result) => {
  exactKeys(result, RESULT_KEYS, 'result');
  const lines = [
    '# Backend critical coverage', '',
    `- phase: ${result.phase}`, `- outcome: ${result.outcome}`, `- reasons: ${result.reasons.length ? result.reasons.join(', ') : 'none'}`,
    `- sourceSha: ${result.identity.sourceSha}`, `- pullRequestHeadSha: ${result.identity.pullRequestHeadSha ?? 'none'}`,
    `- gradle: ${result.producer.gradleVersion}`, `- java: ${result.producer.javaVendor} ${result.producer.javaLanguageVersion}`, `- javaLauncherSha256: ${result.producer.javaLauncherSha256}`, `- jacoco: ${result.producer.jacocoVersion}`,
    `- policySha256: ${result.producer.policySha256}`, `- baselineSha256: ${result.producer.baselineSha256}`, `- rawXmlSha256: ${result.producer.rawXmlSha256}`, `- sourceInventorySha256: ${result.producer.sourceInventorySha256}`,
    `- sources: ${result.coverage.sourceCount} (${result.coverage.reportedSourceCount} reported / ${result.coverage.missingSourceCount} missing)`,
    `- line: ${result.coverage.line.covered}/${result.coverage.line.total} (${result.coverage.line.basisPoints ?? 'null'} bp; missed ${result.coverage.line.missed})`,
    `- branch: ${result.coverage.branch.covered}/${result.coverage.branch.total} (${result.coverage.branch.basisPoints ?? 'null'} bp; missed ${result.coverage.branch.missed})`,
    `- baselineLine: ${result.coverage.baselineLine === null ? 'none' : `${result.coverage.baselineLine.covered}/${result.coverage.baselineLine.total} (${result.coverage.baselineLine.basisPoints ?? 'null'} bp; missed ${result.coverage.baselineLine.missed})`}`,
    `- baselineBranch: ${result.coverage.baselineBranch === null ? 'none' : `${result.coverage.baselineBranch.covered}/${result.coverage.baselineBranch.total} (${result.coverage.baselineBranch.basisPoints ?? 'null'} bp; missed ${result.coverage.baselineBranch.missed})`}`, '',
    '## Boundaries', '',
    ...result.boundaries.map((boundary) => `- ${boundary.id}: sources ${boundary.sourceCount}, line ${boundary.line.covered}/${boundary.line.total} (${boundary.line.basisPoints ?? 'null'} bp; baseline ${boundary.baselineLine === null ? 'none' : `${boundary.baselineLine.covered}/${boundary.baselineLine.total} (${boundary.baselineLine.basisPoints ?? 'null'} bp)`}), branch ${boundary.branch.covered}/${boundary.branch.total} (${boundary.branch.basisPoints ?? 'null'} bp; baseline ${boundary.baselineBranch === null ? 'none' : `${boundary.baselineBranch.covered}/${boundary.baselineBranch.total} (${boundary.baselineBranch.basisPoints ?? 'null'} bp)`})`), '',
    `- newSources: ${result.comparison.newSources.length}`, `- removedSources: ${result.comparison.removedSources.length}`, `- renamedSources: ${result.comparison.renamedSources.length}`, `- changedMissingSources: ${result.comparison.changedMissingSources.length}`, '',
    `- exclusions: ${result.exclusions.length}`, `- artifact.rawXml: ${result.artifacts.rawXmlFile}`, `- artifact.baseline: ${result.artifacts.baselineFile}`, `- artifact.result: ${result.artifacts.resultFile}`, `- artifact.summary: ${result.artifacts.summaryFile}`, `- artifact.digest: ${result.artifacts.digestFile}`, '',
  ];
  return `${lines.join('\n')}\n`;
};

const prepareArtifactDirectory = (artifactDirectory) => {
  if (basename(artifactDirectory) !== 'backend-critical-coverage') fail('artifact directory name mismatch');
  const parent = dirname(resolve(artifactDirectory));
  const parentStat = lstatSync(parent); if (!parentStat.isDirectory() || parentStat.isSymbolicLink()) fail('artifact parent is unsafe');
  try {
    const stat = lstatSync(artifactDirectory); if (!stat.isDirectory() || stat.isSymbolicLink() || readdirSync(artifactDirectory).length !== 0) fail('artifact directory must be absent or empty'); rmdirSync(artifactDirectory);
  } catch (error) { if (error?.code !== 'ENOENT') throw error; }
  return mkdtempSync(`${resolve(artifactDirectory)}.stage-`);
};
export function writeArtifact({ artifactDirectory, rawXmlBytes, baselineBytes, result }) {
  const stage = prepareArtifactDirectory(artifactDirectory);
  try {
    writeFileSync(join(stage, 'jacocoTestReport.xml'), rawXmlBytes);
    writeFileSync(join(stage, 'backend-critical-coverage-baseline.json'), baselineBytes);
    const resultBytes = Buffer.from(canonical(result)); writeFileSync(join(stage, 'backend-critical-coverage-result.json'), resultBytes);
    writeFileSync(join(stage, 'backend-critical-coverage-summary.md'), renderSummary(result));
    writeFileSync(join(stage, 'backend-critical-coverage.sha256'), `${digest(resultBytes)}  backend-critical-coverage-result.json\n`);
    const files = readdirSync(stage).sort(); if (JSON.stringify(files) !== JSON.stringify([...ARTIFACT_FILES].sort())) fail('staged artifact file set mismatch');
    renameSync(stage, resolve(artifactDirectory));
  } catch (error) { rmSync(stage, { recursive: true, force: true }); throw error; }
  return true;
}

export function verifyArtifactDirectory({ artifactDirectory, inputs, expectedPolicySha256 = POLICY_SHA256, expectedBaselineSha256 = BASELINE_SHA256 }) {
  const files = readdirSync(artifactDirectory).sort(); if (JSON.stringify(files) !== JSON.stringify([...ARTIFACT_FILES].sort())) fail('artifact file set mismatch');
  const current = loadInputs(inputs, { expectedPolicySha256, expectedBaselineSha256 }), resultBytes = readFileSync(join(artifactDirectory, 'backend-critical-coverage-result.json'));
  const result = parseCanonicalJson(resultBytes, 'result'); exactKeys(result, RESULT_KEYS, 'result');
  if (canonical(current.result) !== resultBytes.toString('utf8')) fail('artifact result does not match current inputs');
  if (!readFileSync(join(artifactDirectory, 'jacocoTestReport.xml')).equals(current.rawXmlBytes) || !readFileSync(join(artifactDirectory, 'backend-critical-coverage-baseline.json')).equals(current.baselineBytes)) fail('artifact inputs are stale');
  if (readFileSync(join(artifactDirectory, 'backend-critical-coverage-summary.md'), 'utf8') !== renderSummary(result)) fail('artifact summary mismatch');
  if (readFileSync(join(artifactDirectory, 'backend-critical-coverage.sha256'), 'utf8') !== `${digest(resultBytes)}  backend-critical-coverage-result.json\n`) fail('artifact digest mismatch');
  return result;
}

export function validateWorkflow(workflow) {
  for (const literal of [
    'id: test_backend', 'id: coverage_analyze', 'id: coverage_artifact', 'id: coverage_summary',
    'node tools/ci/backend-coverage-gate.mjs analyze', 'node tools/ci/backend-coverage-gate.mjs verdict',
    'if: always() && !cancelled()', 'uses: actions/upload-artifact@bbbca2ddaa5d8feaa63e36b76fdaad77386f024f', 'name: backend-critical-coverage-${{ github.sha }}',
    'retention-days: 5', 'if-no-files-found: error', '--gradle-evidence', '--summary-outcome',
  ]) if (!workflow.includes(literal)) fail(`workflow missing ${literal}`);
  const coverageHeadExpression = "COVERAGE_PULL_REQUEST_HEAD_SHA: ${{ github.event.pull_request.head.sha || 'none' }}";
  if (workflow.split(coverageHeadExpression).length !== 3 || workflow.split('--pull-request-head-sha "$COVERAGE_PULL_REQUEST_HEAD_SHA"').length !== 3) fail('coverage workflow PR-head binding mismatch');
  const start = workflow.indexOf('      - name: Analyze backend critical coverage');
  const end = workflow.indexOf('      - name: Build image without push', start);
  if (start < 0 || end <= start || /continue-on-error/.test(workflow.slice(start, end))) fail('coverage workflow cannot continue on error');
  const block = (name, next) => {
    const blockStart = workflow.indexOf(`      - name: ${name}`), blockEnd = workflow.indexOf(`      - name: ${next}`, blockStart + 1);
    if (blockStart < 0 || blockEnd <= blockStart) fail(`workflow step ${name} is not bounded`);
    return workflow.slice(blockStart, blockEnd);
  };
  const analyze = block('Analyze backend critical coverage', 'Upload backend critical coverage artifact');
  const upload = block('Upload backend critical coverage artifact', 'Append backend critical coverage summary');
  const summary = block('Append backend critical coverage summary', 'Enforce backend critical coverage verdict');
  const verdict = block('Enforce backend critical coverage verdict', 'Build image without push');
  const inputFlags = ['--repo-root', '--policy', '--baseline', '--raw-xml', '--gradle-evidence', '--java-home', '--event', '--source-sha', '--pull-request-head-sha', '--run-url', '--artifact-dir'];
  for (const [slice, id, command] of [[analyze, 'coverage_analyze', 'analyze'], [verdict, null, 'verdict']]) {
    if (id !== null && !slice.includes(`id: ${id}`) || !slice.includes(`node tools/ci/backend-coverage-gate.mjs ${command}`) || inputFlags.some((flag) => !slice.includes(flag)) || /continue-on-error/.test(slice)) fail(`workflow ${command} step mismatch`);
  }
  for (const literal of [
    'id: coverage_artifact', 'if: always() && !cancelled()',
    'uses: actions/upload-artifact@bbbca2ddaa5d8feaa63e36b76fdaad77386f024f',
    'name: backend-critical-coverage-${{ github.sha }}',
    '${{ runner.temp }}/backend-critical-coverage/jacocoTestReport.xml',
    '${{ runner.temp }}/backend-critical-coverage/backend-critical-coverage-baseline.json',
    '${{ runner.temp }}/backend-critical-coverage/backend-critical-coverage-result.json',
    '${{ runner.temp }}/backend-critical-coverage/backend-critical-coverage-summary.md',
    '${{ runner.temp }}/backend-critical-coverage/backend-critical-coverage.sha256',
    'retention-days: 5', 'if-no-files-found: error',
  ]) if (!upload.includes(literal)) fail(`workflow upload step missing ${literal}`);
  if (!summary.includes('id: coverage_summary') || !summary.includes("steps.coverage_analyze.outcome == 'success'") || !summary.includes('backend-critical-coverage-summary.md') || /continue-on-error/.test(summary)) fail('workflow summary step mismatch');
  for (const literal of ['if: always()', '--test-outcome', '--analysis-outcome', '--upload-outcome', '--summary-outcome', 'steps.test_backend.outcome', 'steps.coverage_analyze.outcome', 'steps.coverage_artifact.outcome', 'steps.coverage_summary.outcome']) if (!verdict.includes(literal)) fail(`workflow verdict step missing ${literal}`);
  return true;
}

const parseArgs = (values) => {
  const result = {};
  if (values.length % 2 !== 0) fail('CLI arguments must be flag/value pairs');
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index], value = values[index + 1];
    if (!/^--[a-z][a-z-]*$/.test(key) || Object.hasOwn(result, key)) fail('CLI argument is invalid or duplicate');
    result[key] = value;
  }
  return result;
};
const requireArgs = (args, expected) => {
  if (JSON.stringify(Object.keys(args).sort()) !== JSON.stringify(expected.map((key) => `--${key}`).sort())) fail('CLI argument set mismatch');
  return Object.fromEntries(expected.map((key) => [key.replaceAll('-', '_'), args[`--${key}`]]));
};
const inputArgs = (args) => ({ repoRoot: args.repo_root, policyPath: args.policy, baselinePath: args.baseline, rawXmlPath: args.raw_xml, gradleEvidencePath: args.gradle_evidence, javaHome: args.java_home, event: args.event, sourceSha: args.source_sha, pullRequestHeadSha: args.pull_request_head_sha, runUrl: args.run_url });

export function runCli(argv = process.argv.slice(2), { expectedPolicySha256 = POLICY_SHA256, expectedBaselineSha256 = BASELINE_SHA256 } = {}) {
  const [command, ...values] = argv;
  try {
    if (command === 'analyze') {
      const args = requireArgs(parseArgs(values), ['repo-root', 'policy', 'baseline', 'raw-xml', 'gradle-evidence', 'java-home', 'event', 'source-sha', 'pull-request-head-sha', 'run-url', 'artifact-dir']);
      const loaded = loadInputs(inputArgs(args), { expectedPolicySha256, expectedBaselineSha256 }); writeArtifact({ artifactDirectory: args.artifact_dir, rawXmlBytes: loaded.rawXmlBytes, baselineBytes: loaded.baselineBytes, result: loaded.result }); return 0;
    }
    if (command === 'verdict') {
      const args = requireArgs(parseArgs(values), ['repo-root', 'policy', 'baseline', 'raw-xml', 'gradle-evidence', 'java-home', 'event', 'source-sha', 'pull-request-head-sha', 'run-url', 'artifact-dir', 'test-outcome', 'analysis-outcome', 'upload-outcome', 'summary-outcome']);
      for (const key of ['test_outcome', 'analysis_outcome', 'upload_outcome', 'summary_outcome']) if (args[key] !== 'success') fail(`${key} is not success`);
      const result = verifyArtifactDirectory({ artifactDirectory: args.artifact_dir, inputs: inputArgs(args), expectedPolicySha256, expectedBaselineSha256 });
      if (result.outcome !== 'PASS') fail(`${result.outcome}: ${result.reasons.join(',')}`);
      return 0;
    }
    fail('command must be analyze or verdict');
  } catch (error) {
    process.stderr.write(`backend-critical-coverage: ${error?.message ?? 'unknown failure'}\n`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = runCli();
