import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import {
  basisPoints,
  compareBaseline,
  deriveDecision,
  inventorySources,
  parseCanonicalJson,
  parseJacocoReport,
  projectCoverage,
  runCli,
  serializeBaseline,
  validateBaseline,
  validateBuildScript,
  validateExclusionPolicy,
  validatePolicy,
  validateStaticGate,
  validateWorkflow,
  verifyArtifactDirectory,
  writeArtifact,
} from './backend-coverage-gate.mjs';

const digest = (value) => createHash('sha256').update(value).digest('hex');
const canonical = (value) => `${JSON.stringify(value, null, 2)}\n`;
const replaceOccurrence = (value, target, replacement, occurrence) => {
  let index = -1, from = 0;
  for (let count = 0; count < occurrence; count += 1) {
    index = value.indexOf(target, from);
    assert.notEqual(index, -1);
    from = index + target.length;
  }
  return `${value.slice(0, index)}${replacement}${value.slice(index + target.length)}`;
};

const trackedPolicyBytes = readFileSync(new URL('../../backend/quality/jacoco-coverage-policy.json', import.meta.url), 'utf8');
const trackedBaselineBytes = readFileSync(new URL('../../backend/quality/jacoco-coverage-baseline.json', import.meta.url), 'utf8');
const fixturePolicy = () => structuredClone(JSON.parse(trackedPolicyBytes));
const repositoryRoot = fileURLToPath(new URL('../../', import.meta.url));

const discoveryBaseline = {
  schemaVersion: 1, artifactKind: 'backend-critical-coverage-baseline-v1',
  repository: 'AquilaXk/easysubway-backend', phase: 'UNREVIEWED_DISCOVERY',
  provenance: null, producer: null, scope: null, exclusions: [], sources: [], boundaries: [], renames: [],
};

const jacocoXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="fixture"><sessioninfo id="session" start="1" dump="2"/><package name="com/easysubway/journey"><class name="com/easysubway/journey/Example" sourcefilename="Example.java"><method name="run" desc="()V" line="1"><counter type="INSTRUCTION" missed="0" covered="1"/><counter type="LINE" missed="0" covered="1"/><counter type="COMPLEXITY" missed="0" covered="1"/><counter type="METHOD" missed="0" covered="1"/></method><counter type="INSTRUCTION" missed="0" covered="1"/><counter type="LINE" missed="0" covered="1"/><counter type="COMPLEXITY" missed="0" covered="1"/><counter type="METHOD" missed="0" covered="1"/><counter type="CLASS" missed="0" covered="1"/></class><sourcefile name="Example.java"><line nr="1" mi="0" ci="1" mb="0" cb="2"/><counter type="INSTRUCTION" missed="0" covered="1"/><counter type="BRANCH" missed="0" covered="2"/><counter type="LINE" missed="0" covered="1"/><counter type="COMPLEXITY" missed="0" covered="1"/><counter type="METHOD" missed="0" covered="1"/><counter type="CLASS" missed="0" covered="1"/></sourcefile><counter type="INSTRUCTION" missed="0" covered="1"/><counter type="BRANCH" missed="0" covered="2"/><counter type="LINE" missed="0" covered="1"/><counter type="COMPLEXITY" missed="0" covered="1"/><counter type="METHOD" missed="0" covered="1"/><counter type="CLASS" missed="0" covered="1"/></package><counter type="INSTRUCTION" missed="0" covered="1"/><counter type="BRANCH" missed="0" covered="2"/><counter type="LINE" missed="0" covered="1"/><counter type="COMPLEXITY" missed="0" covered="1"/><counter type="METHOD" missed="0" covered="1"/><counter type="CLASS" missed="0" covered="1"/></report>
`;
const emptyJacocoXml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="empty"/>
`;

const phaseTwoFixture = () => {
  const policy = fixturePolicy(); policy.phase = 'ENFORCED_DECREASE_ONLY';
  const line = { missed: 1, covered: 3, total: 4, basisPoints: 7500 };
  const branch = { missed: 0, covered: 0, total: 0, basisPoints: null };
  const excluded = { path: 'backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', sha256: 'a'.repeat(64), packageName: 'com.easysubway', sourceFileName: 'EasySubwayBackendApplication.java', boundaryIds: [], reportPresence: 'EXCLUDED', absenceDisposition: 'SPRING_BOOT_ENTRYPOINT', line: null, branch: null };
  const journey = { path: 'backend/src/main/java/com/easysubway/journey/Example.java', sha256: 'b'.repeat(64), packageName: 'com.easysubway.journey', sourceFileName: 'Example.java', boundaryIds: ['JOURNEY_ROUTE'], reportPresence: 'PRESENT', absenceDisposition: null, line, branch };
  const sources = [excluded, journey];
  const sourceInventorySha256 = digest(`${JSON.stringify(sources.map(({ path, sha256, boundaryIds }) => ({ path, sha256, boundaryIds })))}\n`);
  const boundaries = policy.scopeRules.map(({ id }) => id === 'JOURNEY_ROUTE'
    ? { id, sourceCount: 1, reportedSourceCount: 1, missingSourceCount: 0, line, branch }
    : { id, sourceCount: 0, reportedSourceCount: 0, missingSourceCount: 0, line: { missed: 0, covered: 0, total: 0, basisPoints: null }, branch: { missed: 0, covered: 0, total: 0, basisPoints: null } });
  const producer = { gradleVersion: '8.14.5', javaVendor: 'ADOPTIUM', javaLanguageVersion: 21, javaLauncherSha256: 'c'.repeat(64), jacocoVersion: '0.8.13', testTask: 'test', reportTask: 'jacocoTestReport', policySha256: 'd'.repeat(64), sourceInventorySha256 };
  const baseline = {
    schemaVersion: 1, artifactKind: 'backend-critical-coverage-baseline-v1', repository: 'AquilaXk/easysubway-backend', phase: 'REVIEWED_DECREASE_ONLY',
    provenance: { runUrl: 'https://github.com/AquilaXk/easysubway-backend/actions/runs/1', artifactId: 1, testedMergeSha: '1'.repeat(40), pullRequestHeadSha: '2'.repeat(40), resultSha256: '3'.repeat(64), rawXmlSha256: '4'.repeat(64), reviewedAt: '2026-08-10T00:00:00Z' },
    producer, scope: { criticalSourceCount: 1, reportedSourceCount: 1, missingSourceCount: 0, excludedSourceCount: 1 },
    exclusions: [{ id: 'SPRING_BOOT_ENTRYPOINT', sourcePath: excluded.path, classFile: 'com/easysubway/EasySubwayBackendApplication.class', sha256: excluded.sha256 }],
    sources, boundaries, renames: [],
  };
  const projection = { sources: structuredClone(sources), coverage: { sourceCount: 1, reportedSourceCount: 1, missingSourceCount: 0, line, branch }, boundaries: structuredClone(boundaries), sourceInventorySha256, exclusions: structuredClone(baseline.exclusions) };
  return { policy, baseline, projection, producer };
};

test('Phase B policy, reviewed baseline and JaCoCo report are closed evidence', () => {
  assert.equal(digest(trackedPolicyBytes), '78b16cc6a62f9625c051c2d0fe4f9ac61341180e53983bdbc3fbd35257bc968b');
  assert.equal(digest(trackedBaselineBytes), '595cbe4ddab098160e234a459ed7f347c14b57dfc7fb205e0d939ef63d38d435');
  assert.deepEqual(parseCanonicalJson(trackedPolicyBytes, 'policy'), fixturePolicy());
  assert.equal(validatePolicy(fixturePolicy()), true);
  const trackedBaseline = JSON.parse(trackedBaselineBytes);
  assert.equal(validateBaseline(trackedBaseline, fixturePolicy()), true);
  assert.equal(serializeBaseline(trackedBaseline), trackedBaselineBytes);
  const reorderedBaseline = structuredClone(trackedBaseline); reorderedBaseline.sources.reverse();
  assert.throws(() => validateBaseline(reorderedBaseline, fixturePolicy()), /unique and sorted/);
  const phaseAPolicy = fixturePolicy(); phaseAPolicy.phase = 'DISCOVERY_REMOTE_RED';
  assert.equal(validateBaseline(discoveryBaseline, phaseAPolicy), true);
  const report = parseJacocoReport(jacocoXml);
  assert.deepEqual(report.sources, [{
    packageName: 'com.easysubway.journey', sourceFileName: 'Example.java',
    line: { missed: 0, covered: 1, total: 1, basisPoints: 10000 },
    branch: { missed: 0, covered: 2, total: 2, basisPoints: 10000 },
  }]);
  const withEmptySource = parseJacocoReport(jacocoXml.replace('</sourcefile>', '</sourcefile><sourcefile name="Empty.java"/>'));
  assert.deepEqual(withEmptySource.sources.at(0), {
    packageName: 'com.easysubway.journey', sourceFileName: 'Empty.java',
    line: { missed: 0, covered: 0, total: 0, basisPoints: null },
    branch: { missed: 0, covered: 0, total: 0, basisPoints: null },
  });
  assert.equal(parseJacocoReport(jacocoXml.replace('</package>', '</package><package name="com/easysubway/empty"/>')).sources.length, 1);
  assert.deepEqual(parseJacocoReport(emptyJacocoXml), {
    sources: [],
    line: { missed: 0, covered: 0, total: 0, basisPoints: null },
    branch: { missed: 0, covered: 0, total: 0, basisPoints: null },
  });
  assert.equal(digest(jacocoXml).length, 64);
});

test('counter and decision semantics fail closed', () => {
  assert.equal(basisPoints(0, 0), null);
  assert.equal(basisPoints(1, 2), 6666);
  assert.deepEqual(deriveDecision({ phase: 'DISCOVERY_REMOTE_RED', reasonOrder: fixturePolicy().resultContract.reasonOrder }), {
    outcome: 'DISCOVERY_REMOTE_RED', reasons: ['BASELINE_UNREVIEWED'],
  });
  for (const mutate of [
    (value) => { value.comparison.counters.reverse(); },
    (value) => { value.exclusions[0].classFile = '**/*Application.class'; },
    (value) => { value.scopeRules[0].prefixes = ['../journey/']; },
    (value) => { value.artifactContract.files.pop(); },
  ]) {
    const invalid = fixturePolicy(); mutate(invalid);
    assert.throws(() => validatePolicy(invalid));
  }
});

test('Phase B baseline and decrease-only reasons are derived from current evidence', () => {
  const fixture = phaseTwoFixture();
  assert.equal(validateBaseline(fixture.baseline, fixture.policy), true);
  assert.equal(JSON.parse(serializeBaseline(fixture.baseline)).phase, 'REVIEWED_DECREASE_ONLY');
  const same = compareBaseline(fixture);
  assert.deepEqual(deriveDecision({ phase: fixture.policy.phase, flags: same.flags }), { outcome: 'PASS', reasons: [] });

  const lineDecrease = phaseTwoFixture();
  lineDecrease.projection.sources[1].line = { missed: 2, covered: 3, total: 5, basisPoints: 6000 };
  lineDecrease.projection.boundaries[0].line = lineDecrease.projection.sources[1].line;
  assert.deepEqual(deriveDecision({ phase: lineDecrease.policy.phase, flags: compareBaseline(lineDecrease).flags }).reasons, ['LINE_MISSED_INCREASE', 'LINE_RATIO_DECREASE', 'BOUNDARY_LINE_DECREASE']);

  const newBranch = phaseTwoFixture();
  newBranch.projection.sources[1].branch = { missed: 1, covered: 1, total: 2, basisPoints: 5000 };
  newBranch.projection.boundaries[0].branch = newBranch.projection.sources[1].branch;
  assert.deepEqual(deriveDecision({ phase: newBranch.policy.phase, flags: compareBaseline(newBranch).flags }).reasons, ['NEWLY_MEASURABLE_BRANCH']);

  const missing = phaseTwoFixture();
  missing.projection.sources[1] = { ...missing.projection.sources[1], reportPresence: 'MISSING', absenceDisposition: null, line: null, branch: null };
  assert.deepEqual(deriveDecision({ phase: missing.policy.phase, flags: compareBaseline(missing).flags }).reasons, ['MISSING_REPORT_SOURCE']);
});

test('canonical JSON rejects duplicate keys, BOM and trailing data', () => {
  assert.throws(() => parseCanonicalJson('{"a":1,"a":2}\n', 'fixture'));
  assert.throws(() => parseCanonicalJson(`\uFEFF${canonical({ a: 1 })}`, 'fixture'));
  assert.throws(() => parseCanonicalJson(`${canonical({ a: 1 })}x`, 'fixture'));
});

test('report grammar and counter relationships reject ambiguity', () => {
  const aggregateCounters = '<counter type="BRANCH" missed="0" covered="2"/><counter type="LINE" missed="0" covered="1"/>';
  for (const invalid of [
    jacocoXml.replace('Report 1.1', 'Report 1.2'),
    jacocoXml.replace('</report>', '<group name="unexpected"/></report>'),
    jacocoXml.replace('<sourcefile name="Example.java">', '<sourcefile name="Example.java" name="Duplicate.java">'),
    jacocoXml.replace(aggregateCounters, '<counter type="BRANCH" missed="0" covered="2"/><counter type="LINE" missed="1" covered="0"/>'),
    ...[1, 2, 3].map((occurrence) => replaceOccurrence(jacocoXml, aggregateCounters, '<counter type="BRANCH" missed="0" covered="2"/>', occurrence)),
    ...[1, 2, 3].map((occurrence) => replaceOccurrence(jacocoXml, aggregateCounters, '<counter type="LINE" missed="0" covered="1"/>', occurrence)),
    jacocoXml.replace('</sourcefile>', '<line nr="1" mi="0" ci="1" mb="0" cb="0"/></sourcefile>'),
    jacocoXml.replace('</report>', '<!ENTITY unsafe "value"></report>'),
  ]) assert.throws(() => parseJacocoReport(invalid));
});

test('tracked build, exclusion, static gate, workflow and source inventory agree', () => {
  const policy = fixturePolicy();
  const exclusion = parseCanonicalJson(readFileSync(new URL('../../backend/quality/jacoco-exclusion-policy.json', import.meta.url)), 'exclusion policy');
  const staticGate = parseCanonicalJson(readFileSync(new URL('../../backend/quality/static-analysis-gate.json', import.meta.url)), 'static gate');
  assert.equal(validateExclusionPolicy(exclusion, policy), true);
  assert.equal(validateStaticGate(staticGate, policy), true);
  assert.equal(validateBuildScript(
    readFileSync(new URL('../../backend/build.gradle', import.meta.url), 'utf8'),
    readFileSync(new URL('../../backend/gradle/wrapper/gradle-wrapper.properties', import.meta.url), 'utf8'),
  ), true);
  const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
  assert.equal(validateWorkflow(workflow), true);
  assert.throws(() => validateWorkflow(workflow.replaceAll('COVERAGE_PULL_REQUEST_HEAD_SHA', 'PULL_REQUEST_HEAD_SHA')), /PR-head binding/);
  const inventory = inventorySources(repositoryRoot, policy);
  const critical = inventory.scoped.filter(({ boundaryIds }) => boundaryIds.length > 0);
  assert.ok(critical.length > 0);
  assert.ok(critical.every(({ path, boundaryIds }) => path.startsWith('backend/src/main/java/com/easysubway/') && boundaryIds.length > 0));
  assert.deepEqual(inventory.scoped.filter(({ path }) => path.endsWith('/EasySubwayBackendApplication.java')).map(({ boundaryIds }) => boundaryIds), [[]]);
});

test('tracked Java inventory admits a legal dollar-sign source filename', () => {
  const directory = mkdtempSync(join(tmpdir(), 'backend-coverage-dollar-source-'));
  const put = (path, value) => { mkdirSync(join(directory, path, '..'), { recursive: true }); writeFileSync(join(directory, path), value); };
  try {
    put('backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', 'package com.easysubway;\nfinal class EasySubwayBackendApplication {}\n');
    put('backend/src/main/java/com/easysubway/journey/A$B.java', 'package com.easysubway.journey;\nfinal class A$B {}\n');
    execFileSync('git', ['init', '-q'], { cwd: directory });
    execFileSync('git', ['config', 'user.name', 'Coverage Fixture'], { cwd: directory });
    execFileSync('git', ['config', 'user.email', 'coverage@example.invalid'], { cwd: directory });
    execFileSync('git', ['add', '.'], { cwd: directory });
    execFileSync('git', ['commit', '-qm', 'fixture'], { cwd: directory });
    const inventory = inventorySources(directory, fixturePolicy());
    assert.deepEqual(inventory.scoped.map(({ path, sourceFileName }) => [path, sourceFileName]), [
      ['backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', 'EasySubwayBackendApplication.java'],
      ['backend/src/main/java/com/easysubway/journey/A$B.java', 'A$B.java'],
    ]);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('coverage projection keeps missing and excluded evidence distinct', () => {
  const example = { path: 'backend/src/main/java/com/easysubway/journey/Example.java', sha256: 'a'.repeat(64), packageName: 'com.easysubway.journey', sourceFileName: 'Example.java', boundaryIds: ['JOURNEY_ROUTE'] };
  const missing = { path: 'backend/src/main/java/com/easysubway/journey/Missing.java', sha256: 'b'.repeat(64), packageName: 'com.easysubway.journey', sourceFileName: 'Missing.java', boundaryIds: ['JOURNEY_ROUTE'] };
  const excluded = { path: 'backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', sha256: 'c'.repeat(64), packageName: 'com.easysubway', sourceFileName: 'EasySubwayBackendApplication.java', boundaryIds: [] };
  const report = parseJacocoReport(jacocoXml);
  const projection = projectCoverage(fixturePolicy(), report, { all: [example, missing, excluded], scoped: [example, missing, excluded] });
  assert.deepEqual(projection.sources.map(({ reportPresence, absenceDisposition }) => [reportPresence, absenceDisposition]), [
    ['PRESENT', null], ['MISSING', null], ['EXCLUDED', 'SPRING_BOOT_ENTRYPOINT'],
  ]);
  assert.deepEqual(projection.coverage, {
    sourceCount: 2, reportedSourceCount: 1, missingSourceCount: 1,
    line: { missed: 0, covered: 1, total: 1, basisPoints: 10000 },
    branch: { missed: 0, covered: 2, total: 2, basisPoints: 10000 },
  });
});

test('five-file artifact write is exact and detached', () => {
  const directory = mkdtempSync(join(tmpdir(), 'backend-coverage-artifact-'));
  const artifactDirectory = join(directory, 'backend-critical-coverage');
  const zero = { missed: 0, covered: 0, total: 0, basisPoints: null };
  const result = {
    schemaVersion: 1, artifactKind: 'backend-critical-coverage-result-v1', repository: 'AquilaXk/easysubway-backend', phase: 'DISCOVERY_REMOTE_RED', outcome: 'DISCOVERY_REMOTE_RED', reasons: ['BASELINE_UNREVIEWED'],
    identity: { event: 'pull_request', sourceSha: 'd'.repeat(40), pullRequestHeadSha: 'e'.repeat(40), runUrl: 'https://github.com/AquilaXk/easysubway-backend/actions/runs/1' },
    producer: { gradleVersion: '8.14.5', javaVendor: 'ADOPTIUM', javaLanguageVersion: 21, javaLauncherSha256: 'f'.repeat(64), jacocoVersion: '0.8.13', policySha256: 'a'.repeat(64), baselineSha256: 'b'.repeat(64), rawXmlSha256: digest(jacocoXml), sourceInventorySha256: 'c'.repeat(64) },
    comparison: { baselinePhase: null, baselineSourceCount: null, newSources: [], removedSources: [], renamedSources: [], changedMissingSources: [] },
    coverage: { sourceCount: 0, reportedSourceCount: 0, missingSourceCount: 0, line: zero, branch: zero, baselineLine: null, baselineBranch: null },
    boundaries: [], exclusions: [], inventory: { summary: { inventorySourceCount: 0, criticalSourceCount: 0, reportedSourceCount: 0, missingSourceCount: 0, excludedSourceCount: 0 }, sources: [] },
    artifacts: { rawXmlFile: 'jacocoTestReport.xml', rawXmlSha256: digest(jacocoXml), baselineFile: 'backend-critical-coverage-baseline.json', baselineSha256: digest(canonical(discoveryBaseline)), resultFile: 'backend-critical-coverage-result.json', summaryFile: 'backend-critical-coverage-summary.md', digestFile: 'backend-critical-coverage.sha256' },
  };
  try {
    assert.equal(writeArtifact({ artifactDirectory, rawXmlBytes: Buffer.from(jacocoXml), baselineBytes: Buffer.from(canonical(discoveryBaseline)), result }), true);
    assert.deepEqual(readdirSync(artifactDirectory).sort(), ['backend-critical-coverage-baseline.json', 'backend-critical-coverage-result.json', 'backend-critical-coverage-summary.md', 'backend-critical-coverage.sha256', 'jacocoTestReport.xml']);
    const resultBytes = readFileSync(join(artifactDirectory, 'backend-critical-coverage-result.json'));
    assert.equal(readFileSync(join(artifactDirectory, 'backend-critical-coverage.sha256'), 'utf8'), `${digest(resultBytes)}  backend-critical-coverage-result.json\n`);
    assert.throws(() => writeArtifact({ artifactDirectory, rawXmlBytes: Buffer.from(jacocoXml), baselineBytes: Buffer.from(canonical(discoveryBaseline)), result }));
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('Phase A CLI binds checkout, producer, report, sources and artifact bytes', () => {
  const directory = mkdtempSync(join(tmpdir(), 'backend-coverage-cli-'));
  const put = (path, value) => { mkdirSync(join(directory, path, '..'), { recursive: true }); writeFileSync(join(directory, path), value); };
  try {
    const policy = fixturePolicy(); policy.phase = 'DISCOVERY_REMOTE_RED';
    const policyBytes = canonical(policy), baselineBytes = canonical(discoveryBaseline);
    const exclusionPolicy = JSON.parse(readFileSync(new URL('../../backend/quality/jacoco-exclusion-policy.json', import.meta.url), 'utf8'));
    exclusionPolicy.phase = 'DISCOVERY_REMOTE_RED';
    const staticGate = JSON.parse(readFileSync(new URL('../../backend/quality/static-analysis-gate.json', import.meta.url), 'utf8'));
    const jacoco = staticGate.tools.find(({ id }) => id === 'jacoco');
    jacoco.enforcement = 'discovery_remote_red';
    jacoco.evidence.failMode = 'Phase A validates and uploads current evidence, then fails with DISCOVERY_REMOTE_RED; no Gradle global percentage gate';
    put('backend/quality/jacoco-coverage-policy.json', policyBytes);
    put('backend/quality/jacoco-coverage-baseline.json', baselineBytes);
    put('backend/quality/jacoco-exclusion-policy.json', canonical(exclusionPolicy));
    put('backend/quality/static-analysis-gate.json', canonical(staticGate));
    put('backend/build.gradle', readFileSync(new URL('../../backend/build.gradle', import.meta.url)));
    put('backend/gradle/wrapper/gradle-wrapper.properties', readFileSync(new URL('../../backend/gradle/wrapper/gradle-wrapper.properties', import.meta.url)));
    put('.github/workflows/ci.yml', readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url)));
    put('backend/src/main/java/com/easysubway/journey/Example.java', 'package com.easysubway.journey;\nfinal class Example {}\n');
    put('backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', 'package com.easysubway;\nfinal class EasySubwayBackendApplication {}\n');
    put('backend/build/reports/jacoco/test/jacocoTestReport.xml', jacocoXml);
    put('jdk/bin/java', 'fixture-java');
    const evidence = {
      schemaVersion: 1, gradleVersion: '8.14.5',
      java: { vendor: 'ADOPTIUM', languageVersion: 21, launcherPath: realpathSync(join(directory, 'jdk/bin/java')), launcherSha256: digest('fixture-java') },
      jacoco: { toolVersion: '0.8.13' }, tasks: { test: 'test', report: 'jacocoTestReport' },
      report: { path: 'backend/build/reports/jacoco/test/jacocoTestReport.xml', sha256: digest(jacocoXml), classDirectoryExcludes: ['com/easysubway/EasySubwayBackendApplication.class'] },
    };
    put('backend/build/jacoco/jacocoTestReport-evidence.json', `${JSON.stringify(evidence)}\n`);
    execFileSync('git', ['init', '-q'], { cwd: directory });
    execFileSync('git', ['config', 'user.name', 'Coverage Fixture'], { cwd: directory });
    execFileSync('git', ['config', 'user.email', 'coverage@example.invalid'], { cwd: directory });
    execFileSync('git', ['add', '.'], { cwd: directory });
    execFileSync('git', ['commit', '-qm', 'fixture'], { cwd: directory });
    const sourceSha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: directory, encoding: 'utf8' }).trim();
    const canonicalDirectory = realpathSync(directory);
    const artifactParent = join(canonicalDirectory, 'runner-temp'); mkdirSync(artifactParent);
    const artifactDirectory = join(artifactParent, 'backend-critical-coverage');
    const inputs = {
      repoRoot: canonicalDirectory,
      policyPath: join(canonicalDirectory, 'backend/quality/jacoco-coverage-policy.json'),
      baselinePath: join(canonicalDirectory, 'backend/quality/jacoco-coverage-baseline.json'),
      rawXmlPath: join(canonicalDirectory, 'backend/build/reports/jacoco/test/jacocoTestReport.xml'),
      gradleEvidencePath: join(canonicalDirectory, 'backend/build/jacoco/jacocoTestReport-evidence.json'),
      javaHome: join(canonicalDirectory, 'jdk'), event: 'pull_request', sourceSha,
      pullRequestHeadSha: 'f'.repeat(40),
      runUrl: 'https://github.com/AquilaXk/easysubway-backend/actions/runs/1',
    };
    const flags = ['--repo-root', inputs.repoRoot, '--policy', inputs.policyPath, '--baseline', inputs.baselinePath, '--raw-xml', inputs.rawXmlPath, '--gradle-evidence', inputs.gradleEvidencePath, '--java-home', inputs.javaHome, '--event', inputs.event, '--source-sha', inputs.sourceSha, '--pull-request-head-sha', inputs.pullRequestHeadSha, '--run-url', inputs.runUrl, '--artifact-dir', artifactDirectory];
    const expectedDigests = { expectedPolicySha256: digest(policyBytes), expectedBaselineSha256: digest(baselineBytes) };
    assert.equal(runCli(['analyze', ...flags], expectedDigests), 0);
    const result = verifyArtifactDirectory({ artifactDirectory, inputs, ...expectedDigests });
    assert.equal(result.outcome, 'DISCOVERY_REMOTE_RED');
    assert.deepEqual(result.reasons, ['BASELINE_UNREVIEWED']);
    assert.deepEqual(result.inventory.summary, { inventorySourceCount: 2, criticalSourceCount: 1, reportedSourceCount: 1, missingSourceCount: 0, excludedSourceCount: 1 });
    writeFileSync(join(artifactDirectory, 'backend-critical-coverage-summary.md'), 'tampered\n');
    assert.throws(() => verifyArtifactDirectory({ artifactDirectory, inputs, ...expectedDigests }), /summary mismatch/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('fixture filesystem is isolated', () => {
  const directory = mkdtempSync(join(tmpdir(), 'backend-coverage-'));
  try {
    mkdirSync(join(directory, 'artifact'));
    writeFileSync(join(directory, 'artifact', 'report.xml'), jacocoXml);
    assert.equal(readFileSync(join(directory, 'artifact', 'report.xml'), 'utf8'), jacocoXml);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
