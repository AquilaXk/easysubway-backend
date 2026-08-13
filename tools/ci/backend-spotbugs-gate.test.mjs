import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import { inspectMembers, pullRequestHeadSha, reconcileLedger, renderSummary, resolvePriorPolicy, safeProject, sanitizeReports, validateEvidence, validateExcludeFilter, validateMemberBindings, validatePolicy, validatePriorLedger, validateReport, validateSuppressedBindings, validateWorkflow } from './backend-spotbugs-gate.mjs';

const digest = (value) => createHash('sha256').update(value).digest('hex');
const sourcePath = 'backend/src/main/java/com/example/Example.java';
const policy = () => ({
  schemaVersion: 1, artifactKind: 'backend-spotbugs-policy-v1', gateId: 'backend-spotbugs-main',
  issue: { url: 'https://github.com/AquilaXk/easysubway-backend/issues/4', title: '[Build][Backend][P1] current SpotBugs findings 정리·enforcement 전환' },
  origin: { repository: 'AquilaXk/easysubway-backend', foundationSha: '3a15efb833b37d5ce051e9591161311dd7952c79' },
  toolchain: {
    gradleVersion: null,
    spotbugsGradlePlugin: { id: 'com.github.spotbugs', requestedVersion: '6.2.2', buildScriptSha256: '1ece9f0725d2a0cb8754a105d8be770f16818c4aa47a3c8fd393ca288ea9a86b', implementationClass: null, implementationJarSha256: null },
    spotbugsEngine: { toolVersion: null, classpath: null }, javaLauncher: { vendorSpec: 'ADOPTIUM', languageVersion: 21 }, task: 'spotbugsMain'
  },
  analysis: { sourceSet: 'main', sourceRoot: 'backend/src/main/java', classOutputRoot: 'backend/build/classes/java/main', excludeFilter: 'backend/quality/spotbugs-exclude.xml', gradleIgnoreFailures: true },
  spotbugsTest: { disposition: 'NOT_REQUIRED_CURRENT', reason: 'test classes are not shipped runtime code', reviewTriggers: ['test output becomes packaged/runtime', 'custom source set mixes test and production outputs', 'bootJar or image admits test classes', 'Gradle source-set/classpath semantics change'] },
  exclusions: [{ className: 'com.easysubway.EasySubwayBackendApplication', sourcePath: 'backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', sourceSha256: 'd0e6c8a5ab74a8c10fead9443573e9acb5b4c240e71f85246744a18b1601aa53', reason: 'bootstrap-only Spring Boot/scheduling entrypoint; product business logic 없음', ownerIssueUrl: 'https://github.com/AquilaXk/easysubway-backend/issues/4', ownerIssueTitle: '[Build][Backend][P1] current SpotBugs findings 정리·enforcement 전환', ownerIssueState: 'OPEN', removalCondition: 'Backend #4 reviews the class against the current report and either removes the filter or records the exact terminal justification.', reviewTriggers: ['source byte change', 'class/member/annotation or responsibility change', 'plugin/JDK/task/source-set change', 'broader class/package Match', 'Backend #4 remediation'] }],
  findingIdentity: { algorithm: 'sha256-canonical-json-v1', fields: ['bugPattern', 'category', 'priority', 'rank', 'className', 'methodName', 'methodSignature', 'sourcePath', 'startLine', 'endLine', 'sourceSha256', 'analyzerInstanceHash'] },
  allowedDispositions: ['FIX_REQUIRED', 'FIXED', 'FALSE_POSITIVE_EXACT_SUPPRESSION', 'ACCEPTED_BOUNDED_RISK', 'GENERATED_OR_NON_OWNED_EXCLUSION'], findings: [],
  transition: { phase: 'DISCOVERY_REMOTE_RED', foundationOwnerIssueUrl: 'https://github.com/AquilaXk/easysubway-backend/issues/35', finalOwnerIssueUrl: 'https://github.com/AquilaXk/easysubway-backend/issues/4', foundationFindingCount: 195, foundationFindingIdentitiesSha256: '405bdc428a32ac1c642ff02900e6f5de2bb45a12362ae4a7477f01dcff6e5dd0', finalRequirements: ['ignoreFailures=false', 'FIX_REQUIRED count 0', 'every remaining finding has an exact terminal disposition'] }
});
const xml = () => '<?xml version="1.0" encoding="UTF-8"?><!-- current SpotBugs report --><BugCollection><BugInstance type="EI_EXPOSE_REP" category="MALICIOUS_CODE" priority="2" rank="18" instanceHash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" instanceOccurrenceNum="0" instanceOccurrenceMax="0"><Class classname="com.example.Example"/><Method classname="com.example.Example" name="&lt;init&gt;" signature="()V"/><SourceLine classname="com.example.Example" sourcepath="com/example/Example.java" start="2" end="2"/></BugInstance><Errors errors="0" missingClasses="0"/><FindBugsSummary total_bugs="1"/></BugCollection>';
const evidence = (dir) => ({
  schemaVersion: 1, gradle: { version: '8.14.3' }, plugin: { id: 'com.github.spotbugs', requestedVersion: '6.2.2', implementationClass: 'example.Plugin', implementationPath: join(dir, 'plugin.jar'), implementationSha256: digest('plugin') }, engine: { toolVersion: '4.9.8', classpath: [{ component: 'x:y:1', artifact: 'engine.jar', path: join(dir, 'engine.jar'), sha256: digest('engine') }] }, java: { requestedVendor: 'ADOPTIUM', vendorMatchesRequestedSpec: true, vendor: 'Eclipse Temurin', languageVersion: 21, runtimeVersion: '21.0.8+9', jvmVersion: '21.0.8+9', installationPath: dir, launcherPath: join(dir, 'bin/java'), launcherSha256: digest('java') }, task: { name: 'spotbugsMain', path: ':spotbugsMain', declaredType: 'com.github.spotbugs.snom.SpotBugsTask', runtimeType: 'com.github.spotbugs.snom.SpotBugsTask_Decorated', runtimeTypeAssignableToDeclared: true, ignoreFailures: true, sourceDirs: [{ path: join(dir, 'backend/src/main/java'), repositoryPath: 'backend/src/main/java' }], classDirs: [{ path: join(dir, 'backend/build/classes/java/main'), repositoryPath: 'backend/build/classes/java/main' }], sources: [{ path: join(dir, sourcePath), repositoryPath: sourcePath, sha256: digest('a\nb\nc\n') }], classes: [{ path: join(dir, 'backend/build/classes/java/main/com/example/Example.class'), repositoryPath: 'backend/build/classes/java/main/com/example/Example.class', sha256: digest('class') }], auxClassPaths: [{ component: 'x:y:1', artifact: 'aux.jar', path: join(dir, 'aux.jar'), sha256: digest('aux') }], pluginJarFiles: [{ component: 'x:detector:1', artifact: 'detector.jar', path: join(dir, 'detector.jar'), sha256: digest('detector') }], excludeFilter: 'backend/quality/spotbugs-exclude.xml', xmlOutput: 'backend/build/reports/spotbugs/spotbugsMain.xml', htmlOutput: 'backend/build/reports/spotbugs/spotbugsMain.html' }
});

test('tracked tests and policy are self-contained reviewed inventory evidence', () => {
  const testSource = readFileSync(new URL('./backend-spotbugs-gate.test.mjs', import.meta.url), 'utf8');
  const gateSource = readFileSync(new URL('./backend-spotbugs-gate.mjs', import.meta.url), 'utf8');
  assert.doesNotMatch(testSource, new RegExp(['easysubway', 'backend', '35', '31323747558'].join('-')));
  assert.match(gateSource, /classpathDigest: '524ec92aeabf6ef4dfd5e0ecec7a6551d8d9ca35d83cd1fbad2295447405cdd1'/);
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  assert.equal(digest(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url))), '96f976f410d7c618d59954ef7590b0ead5a0634f952172173b5a53c91541e495');
  assert.equal(digest(JSON.stringify(tracked.findings.map(({ identity }) => identity))), '405bdc428a32ac1c642ff02900e6f5de2bb45a12362ae4a7477f01dcff6e5dd0');
  assert.equal(tracked.findings[0].identity, '5994a5bb6b4c75a7ae92a4c62d5cb7d3b831c38f264e93c2699ed4e94ed2219e');
  assert.equal(tracked.findings.at(-1).identity, '33589339d5de1740438fbf4e4cd8c74505c776de053b876f93ffe140078bfae4');
});

test('policy fixed literals and captured JDK javap execution fail closed', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  for (const mutate of [
    (value) => { value.issue.title = 'wrong'; },
    (value) => { value.toolchain.spotbugsGradlePlugin.buildScriptSha256 = null; },
    (value) => { value.spotbugsTest.reason = ''; },
    (value) => { value.spotbugsTest.reviewTriggers.reverse(); },
    (value) => { value.exclusions[0].reason = 'wrong'; },
    (value) => { value.exclusions[0].removalCondition = 'wrong'; },
    (value) => { value.exclusions[0].reviewTriggers = []; },
    (value) => { value.transition.finalRequirements.reverse(); }
  ]) { const invalid = structuredClone(tracked); mutate(invalid); assert.throws(() => validatePolicy(invalid, { today: '2026-08-10' })); }
  const dir = mkdtempSync(join(tmpdir(), 'spotbugs-javap-'));
  try {
    const installation = join(dir, 'captured-jdk'), javap = join(installation, 'bin/javap'); mkdirSync(dirname(javap), { recursive: true }); writeFileSync(join(installation, 'bin/java'), 'java'); writeFileSync(javap, 'javap');
    const exec = (...args) => { assert.equal(args[0], javap); assert.deepEqual(args[1], ['-p', '-s', '-classpath', `/classes${process.platform === 'win32' ? ';' : ':'}/aux`, 'com.example.Example']); return 'public com.example.Example();\n  descriptor: ()V\n'; };
    assert.deepEqual(inspectMembers({ className: 'com.example.Example', classDirs: ['/classes'], auxClassPaths: ['/aux'], javapPath: javap, javaInstallationPath: installation, exec }), new Map([['<init>\u0000()V', 1]]));
    assert.throws(() => inspectMembers({ className: 'com.example.Example', classDirs: ['/classes'], auxClassPaths: ['/aux'], javapPath: '/usr/bin/javap', javaInstallationPath: installation, exec }), /captured JDK/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('remediation summary is an exact result-derived closure artifact', () => {
  const digestValue = 'a'.repeat(64);
  const result = { sourceSha: 'b'.repeat(40), artifactKind: 'backend-spotbugs-result-v1', analyzer: { reviewState: { phase: 'REMEDIATION_IN_PROGRESS', pluginImplementationReviewed: true, engineReviewed: true, memberBindingReviewed: true, findingsReviewed: true } }, summary: { ledgerTotal: 195, reported: 195, fixRequired: 195, fixed: 0, falsePositiveExactSuppression: 0, acceptedBoundedRisk: 0, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 }, inputs: { sourceDigest: digestValue, classDigest: digestValue, classpathDigest: digestValue, pluginClasspathDigest: digestValue, pluginImplementationDigest: digestValue, engineClasspathDigest: digestValue, javaLauncherDigest: digestValue, policyDigest: digestValue, priorPolicyDigest: digestValue, excludeFilterDigest: digestValue }, reports: { transform: { id: 'spotbugs-report-sanitizer-v1', rawXmlSha256: digestValue, rawHtmlSha256: digestValue }, xml: { sha256: digestValue }, html: { sha256: digestValue } }, outcome: 'PASS' };
  const summary = renderSummary(result);
  for (const expected of ['review.pluginImplementationReviewed: true', 'review.engineReviewed: true', 'review.memberBindingReviewed: true', 'review.findingsReviewed: true', 'summary.ledgerTotal: 195', 'summary.fixed: 0', 'summary.stale: 0', 'inputs.priorPolicyDigest: ' + digestValue, 'inputs.excludeFilterDigest: ' + digestValue, 'reports.transform.rawXmlSha256: ' + digestValue, 'reports.html.sha256: ' + digestValue, 'outcome: PASS']) assert.match(summary, new RegExp(expected.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.doesNotMatch(summary, /undefined/);
  assert.notEqual(summary, renderSummary({ ...result, outcome: 'FAIL' }));
});

test('tracked remediation policy preserves prior children and projects the current immutable ledger', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  assert.equal(tracked.transition.phase, 'REMEDIATION_IN_PROGRESS');
  assert.equal(tracked.toolchain.gradleVersion, '8.14.5');
  assert.equal(tracked.toolchain.spotbugsGradlePlugin.implementationClass, 'com.github.spotbugs.snom.SpotBugsPlugin');
  assert.equal(tracked.toolchain.spotbugsEngine.toolVersion, '4.9.3');
  assert.equal(tracked.toolchain.spotbugsEngine.classpath.length, 26);
  assert.equal(tracked.findings.length, 195);
  assert.equal(validatePolicy(tracked, { today: '2026-08-10' }), true);
  assert.equal(digest(JSON.stringify(tracked.toolchain.spotbugsEngine.classpath)), '0178af73534a3919830c3bae141dff716dbeed2e13ef31faabcc1dfb6947db69');
  assert.equal(digest(JSON.stringify(tracked.findings.map(({ identity }) => identity))), '405bdc428a32ac1c642ff02900e6f5de2bb45a12362ae4a7477f01dcff6e5dd0');
  const report = tracked.findings.filter(({ sourcePath }) => sourcePath.includes('/report/'));
  assert.equal(report.length, 28);
  assert.deepEqual(report.slice(0, 5).map(({ disposition }) => disposition), [
    'FALSE_POSITIVE_EXACT_SUPPRESSION',
    'FALSE_POSITIVE_EXACT_SUPPRESSION',
    'FALSE_POSITIVE_EXACT_SUPPRESSION',
    'FALSE_POSITIVE_EXACT_SUPPRESSION',
    'FIXED',
  ]);
  const lifecycle = reconcileLedger(tracked, tracked.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED'));
  assert.deepEqual(lifecycle, { ledgerTotal: 195, reported: 16, fixRequired: 16, fixed: 111, falsePositiveExactSuppression: 66, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
  assert.deepEqual(report.filter(({ disposition }) => disposition === 'FIXED').map(({ identity }) => identity), [
    'bf9106f46fd3c8a55e1199dd6c9e83d982b7ca5101e8997a4629142cb42788f5',
    'd0285ecad578ac2c773fb1e80d5c4530f9dc4d1b9795aa59eb46dafe3a13d5f8',
    '7aa0345a8052b4fabb96b934737fcfdbcce07112ecdb302b572cca6173def36a',
    '8450c5f8a0bfa79f95039c6d3e3ff38a6fdfdbe4c084fcde92ea84506e107f20',
    '5bfd37b14d28e28cc3775fc1658c393d24b48ec46671f262a6e9c4c70bdfaea6',
    '3674aec45e59c5e51f5e896f89fc92989bc5414a5c423d49f75196d943ba81a1',
    '8ae97efdd8755a38b718f2c46a1ea549f2f0de04816062c0e442e1ae488b7abc',
    'faa55494bc463e77f0fd85bb22b5e074930562a3cd09cbd5ea6b9f016dc86662',
    'aff4470672e5a6b771955dd3f6051440733ef43e4e9eb57f4b91df51519a3b98',
    '92891d6c5431c871d0d20010b0d3b50b349d38823f71736ff084bd0246423cd0',
  ]);
  for (const finding of report.filter(({ disposition }) => disposition !== 'FIX_REQUIRED')) {
    assert.equal(finding.ownerIssueUrl, 'https://github.com/AquilaXk/easysubway-backend/issues/107');
    assert.equal(finding.ownerIssueTitle, '[Build][Backend][P1] report domain SpotBugs remediation');
    assert.equal(finding.ownerIssueState, 'OPEN');
    assert.equal(finding.expiresAt, '2026-11-07');
  }
  const fixedReason = 'Backend #107 removed this exact report-domain finding with a focused source-level remediation.';
  const fixedRemoval = 'Reopen Backend #107 if this exact finding or a source-equivalent replacement reappears.';
  const fixedTrigger = 'Review this terminal decision when the exact source, report, analyzer, or Backend #107 evidence changes.';
  for (const finding of report.filter(({ disposition }) => disposition === 'FIXED')) {
    assert.deepEqual([finding.reason, finding.removalCondition, finding.reviewTrigger, finding.suppression], [fixedReason, fixedRemoval, fixedTrigger, null]);
  }
  const signed = report.filter(({ disposition }) => disposition === 'ACCEPTED_BOUNDED_RISK');
  const constructors = report.filter(({ bugPattern }) => bugPattern === 'CT_CONSTRUCTOR_THROW');
  const monitor = report.filter(({ bugPattern }) => bugPattern === 'JLM_JSR166_UTILCONCURRENT_MONITORENTER');
  assert.equal(constructors.length, 15);
  assert.equal(monitor.length, 1);
  assert.equal(signed.length, 2);
  const categoryMetadata = [
    [constructors, [
      'Backend #107 reviewed this constructor as intentional fail-fast configuration validation; no partially initialized instance escapes.',
      'Remove this exact suppression when this constructor no longer performs the reviewed fail-fast validation.',
      'Review this decision on constructor source/signature, startup wiring, exception semantics, analyzer, or Backend #107 owner-state change.',
      'Backend #107 exact fail-fast constructor validation.',
    ]],
    [monitor, [
      'Backend #107 verified that this synchronized monitor is private, final, and never exposed; it only serializes bounded key admission.',
      'Remove this exact suppression when bounded key admission no longer synchronizes on this private monitor.',
      'Review this decision on monitor visibility/identity, counter admission concurrency, source/member, analyzer, or Backend #107 owner-state change.',
      'Backend #107 exact private-monitor admission contract.',
    ]],
    [signed, [
      'Backend #107 accepted the transient signed-upload header projection: the signer creates it locally, retains no external alias, and stores no mutable security state.',
      'Remove this exact suppression when the upload-header projection becomes immutable or gains persistent mutable state.',
      'Review this decision on header construction/consumer mutation, signer state, source/member, analyzer, or Backend #107 owner-state change.',
      'Backend #107 exact transient upload-header projection.',
    ]],
  ];
  for (const [findings, [reason, removalCondition, reviewTrigger, suppressionReason]] of categoryMetadata) {
    for (const finding of findings) {
      assert.deepEqual([finding.reason, finding.removalCondition, finding.reviewTrigger, finding.suppression.reason], [reason, removalCondition, reviewTrigger, suppressionReason]);
    }
  }
  for (const finding of report.filter(({ suppression }) => suppression !== null)) {
    assert.deepEqual(Object.keys(finding.suppression), ['kind', 'bugPattern', 'className', 'methodName', 'params', 'returns', 'reason']);
    assert.equal(finding.suppression.kind, 'EXCLUDE_FILTER_EXACT_METHOD');
  }
  const gate = JSON.parse(readFileSync(new URL('../../backend/quality/static-analysis-gate.json', import.meta.url), 'utf8'));
  const spotbugs = gate.tools.find(({ id }) => id === 'spotbugs');
  assert.equal(gate.enforcementStatus, 'spotbugs-remediation-ledger-progressive-required');
  assert.equal(spotbugs.enforcement, 'required_fail_closed_remediation_ledger');
  assert.match(spotbugs.evidence.failMode, /ignoreFailures=true/);
  assert.match(spotbugs.evidence.failMode, /Backend #4/);
  const requiredIndex = tracked.findings.findIndex(({ disposition }) => disposition === 'FIX_REQUIRED');
  assert.notEqual(requiredIndex, -1);
  for (const mutate of [
    (value) => { value.toolchain.gradleVersion = null; },
    (value) => { value.toolchain.spotbugsGradlePlugin.implementationClass = 'wrong.Plugin'; },
    (value) => { value.toolchain.spotbugsEngine.toolVersion = '4.9.4'; },
    (value) => { value.toolchain.spotbugsEngine.classpath.reverse(); },
    (value) => { value.findings.pop(); },
    (value) => { value.findings.push(structuredClone(value.findings[0])); },
    (value) => { value.findings[requiredIndex].sourceSha256 = '0'.repeat(64); },
    (value) => { value.findings[requiredIndex].ownerIssueUrl = 'https://example.invalid/owner'; },
    (value) => { value.findings[requiredIndex].ownerIssueTitle = 'wrong'; },
    (value) => { value.findings[requiredIndex].ownerIssueState = 'CLOSED'; },
    (value) => { value.findings[requiredIndex].expiresAt = '2026-01-01'; },
    (value) => { value.findings[requiredIndex].reason = 'wrong'; },
    (value) => { value.findings[requiredIndex].sourcePath = 'backend/src/main/java/**/*.java'; }
  ]) { const invalid = structuredClone(tracked); mutate(invalid); assert.throws(() => validatePolicy(invalid, { today: '2026-08-10' })); }
});

test('Backend #180 realtime residual projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const identities = new Set([
    '18a9102c97a9dcecf33bdc8b96df4d480ca77b8c12ef1c409b6cdbdbbd722ba1',
    'e24561a945496501149019470f0c36e1c0571cd9533742551fbbd85664c7b66f',
    '25642ed9378e9b3c9ff9120ad305ecd693a5e67a1f55fe149a44d47e2ff92a5c',
    '4d2b92a5e80a1ae590fa76101f666aced1abdcb15ed49f5f4aa8f1d550f632a5',
    'c1b5ddee1670fcc5721bfb26072bd580e30d8461dbfd77bab85f036d755b3dd7',
  ]);
  const realtime = tracked.findings.filter(({ identity }) => identities.has(identity));
  assert.deepEqual(realtime.map(({ identity }) => identity), [...identities]);
  for (const finding of realtime) {
    assert.deepEqual(
      [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
      ['FIXED', 'https://github.com/AquilaXk/easysubway-backend/issues/180', '[Build][Backend][P1] realtime residual SpotBugs remediation', 'OPEN', 'Backend #180 removed this exact realtime finding with a focused source-level remediation.', 'Reopen Backend #180 if this exact finding or a source-equivalent replacement reappears.', 'Review this terminal decision when the exact source, realtime contract, analyzer, or Backend #180 evidence changes.', '2026-11-10', null],
    );
  }
});

test('Backend #196 internal route result projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const identities = new Set([
    '725bd6ed3e47a6c1153fc7cd3f669358d891ca9c7ba07db970d291aac0591f9c',
    '945288a965bfd4064bbe879f9b4a4478fd27d3d5f4d0b01e9f63114198575200',
    '66182d8e77aab79b0217f583bda192e58e798c9445d1c81b9a3167c9e94529db',
    '7027220fd4ecac7172811199cca1e6f71cfe2b26bc6d81974d3433a5f471bd66',
    'c5b97d28f2f0361784c6808b5d2e7175ae8a1d8755c89f8b0c38523056d34207',
    'bc43fc55d494660bca793356d2ae8bff00ab3546248fec1649afb33744553eb4',
  ]);
  const internalRouteResult = tracked.findings.filter(
    ({ className }) => className === 'com.easysubway.route.domain.InternalRouteResult',
  );
  assert.deepEqual(internalRouteResult.map(({ identity }) => identity), [...identities]);
  for (const finding of internalRouteResult) {
    assert.deepEqual(
      [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
      ['FIXED', 'https://github.com/AquilaXk/easysubway-backend/issues/196', '[Build][Backend][P1] InternalRouteResult SpotBugs remediation', 'OPEN', 'Backend #196 removed this exact internal route result finding with a focused source-level remediation.', 'Reopen Backend #196 if this exact finding or a source-equivalent replacement reappears.', 'Review this terminal decision when the exact source, internal route result contract, analyzer, or Backend #196 evidence changes.', '2026-11-12', null],
    );
  }
});

test('Backend #198 route search result projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const identities = new Set([
    '18c0546adad49b991a7c3ab334a63f6c135219fc956a88b783a0371de1641010',
    '66e277ed77293f4eea8fc80bd726f5abe3aa1c90432faa7d9d8c8a1a34d433f9',
    '1f5ce76d04546aba972c265508eddf8c680b081293b3fba1d2cf2f6223e4cfd2',
    '809919b616e66de21158776f428f5c82d572e2a544c9d0f8b218c76d407aa20b',
    'a3d18f6dac851545ab26a1d5df3c1c6030f0dc8d9a5890c1e3e21d14a721ab43',
    'c7a9cc889569e4f750572da2b3ffff3394f791b07cd2cfb18c43f1105b3bbe9a',
  ]);
  const routeSearchResult = tracked.findings.filter(
    ({ className }) => className === 'com.easysubway.route.domain.RouteSearchResult',
  );
  assert.deepEqual(routeSearchResult.map(({ identity }) => identity), [...identities]);
  for (const finding of routeSearchResult) {
    assert.deepEqual(
      [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
      ['FIXED', 'https://github.com/AquilaXk/easysubway-backend/issues/198', '[Build][Backend][P1] RouteSearchResult SpotBugs remediation', 'OPEN', 'Backend #198 removed this exact route search result finding with a focused defensive-copy remediation.', 'Reopen Backend #198 if this exact finding or a source-equivalent replacement reappears.', 'Review this terminal decision when the exact source, route search result contract, analyzer, or Backend #198 evidence changes.', '2026-11-12', null],
    );
  }
});

test('Backend #201 Play Integrity verdict projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const identities = new Set([
    'b512d5a026618ce7d7a5c9ebb21304c3aa236d6c2905729a481f37625502056a',
    'bfcb242ad4b646a48b376f195554806d0d469b6e2a05c26732d2358c130038c9',
    '6b37495e6130f843acedfc9d100e31daff99f2eaa26a44479d97ccd7cb164818',
    '0cba2cb5768835d911631c218d155ce7e2ec2ba17649c2996726d7a08dc9ea76',
  ]);
  const playIntegrityVerdict = tracked.findings.filter(
    ({ className }) => className === 'com.easysubway.route.application.port.out.PlayIntegrityDecoder$PlayIntegrityVerdict',
  );
  assert.deepEqual(playIntegrityVerdict.map(({ identity }) => identity), [...identities]);
  for (const finding of playIntegrityVerdict) {
    assert.deepEqual(
      [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
      ['FIXED', 'https://github.com/AquilaXk/easysubway-backend/issues/201', '[Build][Backend][P1] PlayIntegrityVerdict SpotBugs remediation', 'OPEN', 'Backend #201 removed this exact Play Integrity verdict finding with a focused defensive-copy remediation.', 'Reopen Backend #201 if this exact finding or a source-equivalent replacement reappears.', 'Review this terminal decision when the exact source, Play Integrity verdict contract, analyzer, or Backend #201 evidence changes.', '2026-11-12', null],
    );
  }
});

test('Backend #204 route feedback dashboard view projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const identities = new Set([
    '7e7131c8e6dadb24589254e26b879f91303da27aaf38f4212a932a7df5ec6aa4',
    'aafe82ca2e54e3feb9b000b222a7c4fdaed92063910f5682ba1d50e7a844b898',
    'e049522acaef4408e9ed00c059f52cdbaf8cb318832939dc3bac5439e25613b8',
    '25dc750c258a7ca6770299cc352ad9af4904e4069720d355ef47881c31f72826',
    '6b74d92782efa6e9c135dbbc045467a7de422afddfc01319219c8238db6567cf',
    '58516585d7de8b7168e6cc8be1d97c2586c0f66e2a8ce2849aadff9ad14fc7eb',
  ]);
  const routeFeedbackDashboardView = tracked.findings.filter(
    ({ className }) => className === 'com.easysubway.route.adapter.in.web.RouteFeedbackDashboardView',
  );
  assert.deepEqual(routeFeedbackDashboardView.map(({ identity }) => identity), [...identities]);
  for (const finding of routeFeedbackDashboardView) {
    assert.deepEqual(
      [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
      ['FIXED', 'https://github.com/AquilaXk/easysubway-backend/issues/204', '[Build][Backend][P1] RouteFeedbackDashboardView SpotBugs remediation', 'OPEN', 'Backend #204 removed this exact route feedback dashboard view finding with a focused defensive-copy remediation.', 'Reopen Backend #204 if this exact finding or a source-equivalent replacement reappears.', 'Review this terminal decision when the exact source, route feedback dashboard view contract, analyzer, or Backend #204 evidence changes.', '2026-11-12', null],
    );
  }
});

test('Backend #208 route search dashboard view projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const identities = new Set([
    '2a15d96f586892b37210460208cce47b47f00508c28394ff0661b6c669a252c5',
    'de09fd0765262e8204aa41a33fe7b35fc3d5641d39bc7c3b60bf8220e129ba44',
    '7296addbc76bc40ff34fc8b0c5ef67e7dfab10893176bfe068a8c4a8f5d6b48c',
    'a1da3600274b85464f759279c34d11661e7536879f2cb421c9621323e1e54f13',
    '605271074f3e0cde1bff5d6cfd10c47fe39ffe927617542bfcba41db17b2e71f',
    '3a1cc000125b94e232559e41fd95ba2b035a7faaaaf252611f8e5258d27342d8',
    'f80af52cdc62644a0aea3eca6d72efae0ba1246332f3648f406d24bb0c38ef16',
    'fd2a77196de86e554d668fedbffb20bfec9f717fe0fb100f7308542b1978b1aa',
    '947a92e7b94bb930a6e8c55407a4ae2c5b5acd723bc67277f41de16e09f6adab',
    '6e448882b57626d838af577bace53315d6315932c55ee2701078736ebf5dc63d',
    '6935b5b412a3913445bdf5abd48902a855658b656bf075a730e9d9357a6d22cf',
    '22e491623fe2c7f18342ad5a9ef9fff8d87b74bbbfae57394d13fbbdc5f99f9e',
    'cb84a3b2222a0d95f504ca72765cd2fa7d912075c87bda836830c060ef52f63f',
    '0f0f5083621a25564e76b45e8b3f9a8ecad2e9824ac5013badcf7928f0b5473a',
  ]);
  const routeSearchDashboardView = tracked.findings.filter(
    ({ className }) => className === 'com.easysubway.route.adapter.in.web.RouteSearchDashboardView',
  );
  assert.deepEqual(routeSearchDashboardView.map(({ identity }) => identity), [...identities]);
  for (const finding of routeSearchDashboardView) {
    assert.deepEqual(
      [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
      ['FIXED', 'https://github.com/AquilaXk/easysubway-backend/issues/208', '[Build][Backend][P1] RouteSearchDashboardView SpotBugs 14건 terminal disposition', 'OPEN', 'Backend #208 removed this exact route search dashboard view finding with a focused defensive-copy remediation.', 'Reopen Backend #208 if this exact finding or a source-equivalent replacement reappears.', 'Review this terminal decision when the exact source, route search dashboard view contract, analyzer, or Backend #208 evidence changes.', '2026-11-12', null],
    );
  }
});

test('Backend #216 RouteV2Metrics injected registry disposition is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const finding = tracked.findings.find(
    ({ identity }) => identity === '60957e4ae604cc4723282f9c71999bba29a7e3f9b4004333fea2b3c18193e3ea',
  );
  assert.deepEqual(
    [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
    [
      'FALSE_POSITIVE_EXACT_SUPPRESSION',
      'https://github.com/AquilaXk/easysubway-backend/issues/216',
      '[Build][Backend][P1] RouteV2Metrics injected registry SpotBugs exact disposition',
      'OPEN',
      'Backend #216 verified that RouteV2Metrics intentionally retains the injected MeterRegistry identity so every metric is registered in the same registry; it is behavior infrastructure, not caller-owned value data.',
      'Remove this exact suppression when RouteV2Metrics no longer retains the registry or MeterRegistry becomes a copyable value contract.',
      'Review this decision on registry identity/mutability, Micrometer wiring, source/member, analyzer, or Backend #216 owner-state change.',
      '2026-11-13',
      {
        kind: 'EXCLUDE_FILTER_EXACT_METHOD',
        bugPattern: 'EI_EXPOSE_REP2',
        className: 'com.easysubway.route.adapter.in.web.RouteV2Metrics',
        methodName: '<init>',
        params: 'io.micrometer.core.instrument.MeterRegistry',
        returns: 'void',
        reason: 'Backend #216 exact injected MeterRegistry identity contract.',
      },
    ],
  );
  const excludeFilter = readFileSync(new URL('../../backend/quality/spotbugs-exclude.xml', import.meta.url), 'utf8');
  assert.match(excludeFilter, /<Bug pattern="EI_EXPOSE_REP2"\/>\s*<Class name="com\.easysubway\.route\.adapter\.in\.web\.RouteV2Metrics"\/>\s*<Method name="&lt;init&gt;" params="io\.micrometer\.core\.instrument\.MeterRegistry" returns="void"\/>/u);
  assert.equal((excludeFilter.match(/<Match>/g) ?? []).length, 69);
  assert.deepEqual(reconcileLedger(tracked, tracked.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED')), { ledgerTotal: 195, reported: 16, fixRequired: 16, fixed: 111, falsePositiveExactSuppression: 66, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
});

test('Backend #218 realtime cancelled train list remediation is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const finding = tracked.findings.find(
    ({ identity }) => identity === 'b9168e403643e3200ade4d55153c94f5dd6f760b556efe5a700afb1a87506feb',
  );
  assert.deepEqual(
    [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
    [
      'FIXED',
      'https://github.com/AquilaXk/easysubway-backend/issues/218',
      '[Build][Backend][P1] realtime cancelled-train immutable-list SpotBugs remediation',
      'OPEN',
      'Backend #218 removed this exact realtime cancelled-train list finding with an explicit immutable snapshot after preserving normalization.',
      'Reopen Backend #218 if this exact finding or a source-equivalent replacement reappears.',
      'Review this terminal decision when the exact source, cancelled-train normalization contract, analyzer, or Backend #218 evidence changes.',
      '2026-11-13',
      null,
    ],
  );
  assert.deepEqual(reconcileLedger(tracked, tracked.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED')), { ledgerTotal: 195, reported: 16, fixRequired: 16, fixed: 111, falsePositiveExactSuppression: 66, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
});

test('Backend #220 route controller final-type remediation is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const finding = tracked.findings.find(
    ({ identity }) => identity === '0e15849ec7cbdf9c89813f1b2377c2f77c6b3402649b5f40b3fa43b4cab4bf84',
  );
  assert.deepEqual(
    [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
    [
      'FIXED',
      'https://github.com/AquilaXk/easysubway-backend/issues/220',
      '[Build][Backend][P1] RouteSearchController final-type SpotBugs remediation',
      'OPEN',
      'Backend #220 removed this exact route controller constructor finding by closing the package-private controller type to inheritance.',
      'Reopen Backend #220 if this exact finding or a source-equivalent replacement reappears.',
      'Review this terminal decision when the exact source, Spring controller construction contract, analyzer, or Backend #220 evidence changes.',
      '2026-11-13',
      null,
    ],
  );
  const controller = readFileSync(new URL('../../backend/src/main/java/com/easysubway/route/adapter/in/web/RouteSearchController.java', import.meta.url), 'utf8');
  assert.match(controller, /^final class RouteSearchController \{/mu);
  assert.doesNotMatch(controller, /^public\s+final\s+class RouteSearchController\b/mu);
  assert.deepEqual(reconcileLedger(tracked, tracked.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED')), { ledgerTotal: 195, reported: 16, fixRequired: 16, fixed: 111, falsePositiveExactSuppression: 66, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
});

test('Backend #224 session controller final-type remediation is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const finding = tracked.findings.find(
    ({ identity }) => identity === '5fc71aa4967ea3fa6886e2c841f6d9dc86dea8c8a11036235bd34558de5b568c',
  );
  assert.deepEqual(
    [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
    [
      'FIXED',
      'https://github.com/AquilaXk/easysubway-backend/issues/224',
      '[Build][Backend][P1] RouteV2SessionController final-type SpotBugs remediation',
      'OPEN',
      'Backend #224 removed this exact session controller constructor finding by closing the package-private controller type to inheritance.',
      'Reopen Backend #224 if this exact finding or a source-equivalent replacement reappears.',
      'Review this terminal decision when the exact source, authenticated session controller construction contract, analyzer, or Backend #224 evidence changes.',
      '2026-11-13',
      null,
    ],
  );
  const controller = readFileSync(new URL('../../backend/src/main/java/com/easysubway/route/adapter/in/web/RouteV2SessionController.java', import.meta.url), 'utf8');
  assert.match(controller, /^final class RouteV2SessionController \{/mu);
  assert.doesNotMatch(controller, /^public\s+final\s+class RouteV2SessionController\b/mu);
  assert.deepEqual(reconcileLedger(tracked, tracked.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED')), { ledgerTotal: 195, reported: 16, fixRequired: 16, fixed: 111, falsePositiveExactSuppression: 66, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
});

test('Backend #226 route v2 access store constructor dispositions are exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const cases = [
    ['0194c7b39dcf80db0c068e653288184c2c53193de71fb0ad5aae73f84b4150c9', 'javax.sql.DataSource,int'],
    ['23d7297f5cc5352a987b23570fa9ba5d0fa8eec26911e8fe6910c1aad6fa39c0', 'org.springframework.jdbc.core.JdbcTemplate'],
    ['e1f9e05c372bba6a8297c83cf624a300dced181324ab09b3ee4db8fadb222c03', 'org.springframework.jdbc.core.JdbcTemplate,int'],
  ];
  for (const [identity, params] of cases) {
    const finding = tracked.findings.find((candidate) => candidate.identity === identity);
    assert.deepEqual(
      [finding.disposition, finding.ownerIssueUrl, finding.ownerIssueTitle, finding.ownerIssueState, finding.reason, finding.removalCondition, finding.reviewTrigger, finding.expiresAt, finding.suppression],
      [
        'FALSE_POSITIVE_EXACT_SUPPRESSION',
        'https://github.com/AquilaXk/easysubway-backend/issues/226',
        '[Build][Backend][P1] JdbcRouteV2AccessStore exact-constructor SpotBugs disposition',
        'OPEN',
        'Backend #226 reviewed these exact constructors as intentional fail-fast request-limit and dependency initialization; no partially initialized store escapes.',
        'Remove this suppression if the constructor no longer fails fast, the repository transaction proxy contract changes, or the exact finding disappears.',
        'Review this decision on source/member identity, constructor validation, Spring transaction proxy semantics, analyzer, or Backend #226 owner-state change.',
        '2026-11-13',
        {
          kind: 'EXCLUDE_FILTER_EXACT_METHOD',
          bugPattern: 'CT_CONSTRUCTOR_THROW',
          className: 'com.easysubway.route.adapter.out.persistence.JdbcRouteV2AccessStore',
          methodName: '<init>',
          params,
          returns: 'void',
          reason: 'Backend #226 exact fail-fast Route V2 access-store constructor initialization.',
        },
      ],
    );
  }
  const excludeFilter = readFileSync(new URL('../../backend/quality/spotbugs-exclude.xml', import.meta.url), 'utf8');
  for (const [, params] of cases) {
    const escapedParams = params.replaceAll('.', '\\.');
    assert.match(excludeFilter, new RegExp(`<Bug pattern="CT_CONSTRUCTOR_THROW"/>\\s*<Class name="com\\.easysubway\\.route\\.adapter\\.out\\.persistence\\.JdbcRouteV2AccessStore"/>\\s*<Method name="&lt;init&gt;" params="${escapedParams}" returns="void"/>`, 'u'));
  }
  assert.equal((excludeFilter.match(/<Match>/g) ?? []).length, 72);
  const source = readFileSync(new URL('../../backend/src/main/java/com/easysubway/route/adapter/out/persistence/JdbcRouteV2AccessStore.java', import.meta.url), 'utf8');
  assert.match(source, /^public class JdbcRouteV2AccessStore implements RouteV2AccessStore \{/mu);
  assert.doesNotMatch(source, /^public\s+final\s+class JdbcRouteV2AccessStore\b/mu);
  assert.equal((source.match(/@Transactional/g) ?? []).length, 2);
  assert.deepEqual(reconcileLedger(tracked, tracked.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED')), { ledgerTotal: 195, reported: 13, fixRequired: 13, fixed: 111, falsePositiveExactSuppression: 69, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
});

test('Backend #110 datapack projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const datapack = tracked.findings.filter(({ sourcePath }) => sourcePath.includes('/datapack/'));
  assert.equal(datapack.length, 26);
  assert.equal(new Set(datapack.map(({ sourcePath }) => sourcePath)).size, 14);
  assert.equal(digest(`${JSON.stringify(datapack.map(({ identity }) => identity))}\n`), 'ec819b85d5a55653bd06a926a125694efb09cba0f2389841f3cbb8852f89cdc4');
  assert.deepEqual(reconcileLedger(tracked, tracked.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED')), { ledgerTotal: 195, reported: 16, fixRequired: 16, fixed: 111, falsePositiveExactSuppression: 66, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
  const fixed = datapack.filter(({ disposition }) => disposition === 'FIXED');
  assert.deepEqual(fixed.map(({ identity }) => identity), ['d233aa50bd7f69d165daa6336008536ec372c51d9739a669c7d202dac64bd481', '6105aefe9ddc33aa82dc2dbc1dec6e4913558a4b4d00f4ba5f86e4583c8e0a57', '20f57710c70f578826127d148bcb6fff9ddf182b5a0919da897fdbada2e20546', '90c28725d7010af274f86071b28b259a19b6e2102d211ad03450a5ef289aa0dc']);
  const ct = datapack.filter(({ bugPattern, disposition }) => bugPattern === 'CT_CONSTRUCTOR_THROW' && disposition === 'FALSE_POSITIVE_EXACT_SUPPRESSION');
  const di = datapack.filter(({ bugPattern, disposition }) => bugPattern === 'EI_EXPOSE_REP2' && disposition === 'FALSE_POSITIVE_EXACT_SUPPRESSION');
  assert.equal(ct.length, 13);
  assert.equal(di.length, 9);
  for (const finding of datapack) {
    assert.equal(finding.ownerIssueUrl, 'https://github.com/AquilaXk/easysubway-backend/issues/110');
    assert.equal(finding.ownerIssueTitle, '[Build][Backend][P1] datapack domain SpotBugs remediation');
    assert.equal(finding.ownerIssueState, 'OPEN');
    assert.equal(finding.expiresAt, '2026-11-07');
  }
  for (const finding of fixed) {
    assert.equal(finding.reason, 'Backend #110 removed this exact datapack-domain finding with a focused source-level remediation.');
    assert.equal(finding.removalCondition, 'Reopen Backend #110 if this exact finding or a source-equivalent replacement reappears.');
    assert.equal(finding.reviewTrigger, 'Review this terminal decision when the exact source, datapack contract, analyzer, or Backend #110 evidence changes.');
    assert.equal(finding.suppression, null);
  }
  for (const finding of ct) {
    assert.equal(finding.reason, 'Backend #110 reviewed this constructor as intentional fail-fast datapack infrastructure initialization; no partially initialized instance escapes.');
    assert.equal(finding.removalCondition, 'Remove this exact suppression when this constructor no longer performs the reviewed fail-fast initialization.');
    assert.equal(finding.reviewTrigger, 'Review this decision on constructor source/signature, startup wiring, exception semantics, analyzer, or Backend #110 owner-state change.');
    assert.equal(finding.suppression.reason, 'Backend #110 exact fail-fast datapack constructor initialization.');
  }
  for (const finding of di) {
    assert.equal(finding.reason, 'Backend #110 verified that this constructor intentionally retains the injected collaborator identity; it is behavior infrastructure, not caller-owned value data.');
    assert.equal(finding.removalCondition, 'Remove this exact suppression when the constructor no longer retains this collaborator or the dependency becomes a copyable value contract.');
    assert.equal(finding.reviewTrigger, 'Review this decision on collaborator type/identity/mutability, constructor wiring, source/member, analyzer, or Backend #110 owner-state change.');
    assert.equal(finding.suppression.reason, 'Backend #110 exact injected-collaborator identity contract.');
  }
  for (const finding of [...ct, ...di]) {
    assert.equal(Object.keys(finding.suppression).join('|'), 'kind|bugPattern|className|methodName|params|returns|reason');
    assert.equal(finding.suppression.kind, 'EXCLUDE_FILTER_EXACT_METHOD');
    assert.equal(finding.suppression.bugPattern, finding.bugPattern);
    assert.equal(finding.suppression.className, finding.className);
    assert.equal(finding.suppression.methodName, finding.methodName);
  }
  assert.equal((readFileSync(new URL('../../backend/quality/spotbugs-exclude.xml', import.meta.url), 'utf8').match(/<Match>/g) ?? []).length, 69);
});

test('Backend #113 admin operator quality projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const partition = tracked.findings.filter(({ sourcePath }) => /^backend\/src\/main\/java\/com\/easysubway\/(?:admin|operator|quality)\//.test(sourcePath));
  assert.equal(partition.length, 46);
  assert.equal(new Set(partition.map(({ sourcePath }) => sourcePath)).size, 13);
  assert.equal(digest(`${JSON.stringify(partition.map(({ identity }) => identity))}\n`), 'e48b3b055c3f7c64e1d9f18ea463866136999d77f0ee7a953dc9fae0e15be7b0');
  const fixed = partition.filter(({ disposition }) => disposition === 'FIXED');
  const ct = partition.filter(({ bugPattern, disposition }) => bugPattern === 'CT_CONSTRUCTOR_THROW' && disposition === 'FALSE_POSITIVE_EXACT_SUPPRESSION');
  const di = partition.filter(({ bugPattern, disposition }) => bugPattern === 'EI_EXPOSE_REP2' && disposition === 'FALSE_POSITIVE_EXACT_SUPPRESSION');
  assert.equal(fixed.length, 43);
  assert.equal(ct.length, 2);
  assert.equal(di.length, 1);
  for (const finding of partition) {
    assert.equal(finding.ownerIssueUrl, 'https://github.com/AquilaXk/easysubway-backend/issues/113');
    assert.equal(finding.ownerIssueTitle, '[Build][Backend][P1] admin·operator·quality SpotBugs remediation');
    assert.equal(finding.ownerIssueState, 'OPEN');
    assert.equal(finding.expiresAt, '2026-11-08');
  }
  for (const finding of fixed) {
    assert.equal(finding.reason, 'Backend #113 removed this exact admin/operator/quality finding with a focused source-level remediation.');
    assert.equal(finding.removalCondition, 'Reopen Backend #113 if this exact finding or a source-equivalent replacement reappears.');
    assert.equal(finding.reviewTrigger, 'Review this terminal decision when the exact source, admin/operator/quality contract, analyzer, or Backend #113 evidence changes.');
    assert.equal(finding.suppression, null);
  }
  for (const finding of di) {
    assert.equal(finding.reason, 'Backend #113 verified that ErrorEventAsyncWriter intentionally retains the injected repository identity for async persistence; it is behavior infrastructure, not caller-owned value data.');
    assert.equal(finding.removalCondition, 'Remove this exact suppression when the writer no longer retains this repository or the dependency becomes a copyable value contract.');
    assert.equal(finding.reviewTrigger, 'Review this decision on repository identity/mutability, async wiring, source/member, analyzer, or Backend #113 owner-state change.');
    assert.equal(finding.suppression.reason, 'Backend #113 exact ErrorEventRepository injected-collaborator identity contract.');
  }
  for (const finding of ct) {
    assert.equal(finding.reason, 'Backend #113 reviewed these constructors as intentional fail-fast JDBC dialect initialization; no partially initialized repository escapes.');
    assert.equal(finding.removalCondition, 'Remove this exact suppression when the constructors no longer perform the reviewed dialect initialization.');
    assert.equal(finding.reviewTrigger, 'Review this decision on constructor source/signature, JDBC startup wiring, exception timing, analyzer, or Backend #113 owner-state change.');
    assert.equal(finding.suppression.reason, 'Backend #113 exact fail-fast AdminMetricDaily JDBC dialect initialization.');
  }
  assert.deepEqual([...ct, ...di].map(({ suppression }) => [suppression.bugPattern, suppression.className, suppression.methodName, suppression.params, suppression.returns]), [
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.admin.metric.adapter.out.persistence.JdbcAdminMetricDailyRepository', '<init>', 'javax.sql.DataSource', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.admin.metric.adapter.out.persistence.JdbcAdminMetricDailyRepository', '<init>', 'org.springframework.jdbc.core.JdbcTemplate', 'void'],
    ['EI_EXPOSE_REP2', 'com.easysubway.admin.errors.application.service.ErrorEventAsyncWriter', '<init>', 'com.easysubway.admin.errors.application.port.out.ErrorEventRepository', 'void'],
  ]);
  for (const finding of [...ct, ...di]) {
    assert.deepEqual(Object.keys(finding.suppression), ['kind', 'bugPattern', 'className', 'methodName', 'params', 'returns', 'reason']);
    assert.equal(finding.suppression.kind, 'EXCLUDE_FILTER_EXACT_METHOD');
  }
});

test('Backend #116 remaining non-realtime projection is exact', () => {
  const tracked = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const admittedPaths = new Set([
    'backend/src/main/java/com/easysubway/ads/adapter/in/web/AdminAdsPageController.java',
    'backend/src/main/java/com/easysubway/ads/adapter/out/persistence/JdbcAdRepository.java',
    'backend/src/main/java/com/easysubway/favorite/adapter/out/persistence/JdbcFavoriteRouteRepository.java',
    'backend/src/main/java/com/easysubway/favorite/application/service/FavoriteFacilityService.java',
    'backend/src/main/java/com/easysubway/favorite/application/service/FavoriteRouteService.java',
    'backend/src/main/java/com/easysubway/favorite/application/service/FavoriteStationService.java',
    'backend/src/main/java/com/easysubway/field/adapter/out/persistence/JdbcFieldVerificationSessionRepository.java',
    'backend/src/main/java/com/easysubway/field/domain/FieldVerificationSession.java',
    'backend/src/main/java/com/easysubway/health/application/service/HealthCheckService.java',
    'backend/src/main/java/com/easysubway/notice/adapter/out/persistence/JdbcServiceNoticeRepository.java',
    'backend/src/main/java/com/easysubway/notification/adapter/out/persistence/JdbcNotificationPreferenceRepository.java',
    'backend/src/main/java/com/easysubway/notification/adapter/out/persistence/JdbcPushNotificationOutboxRepository.java',
    'backend/src/main/java/com/easysubway/notification/application/port/in/ResendPushNotificationsCommand.java',
    'backend/src/main/java/com/easysubway/train/adapter/in/web/TrainSearchContractController.java',
    'backend/src/main/java/com/easysubway/train/adapter/in/web/TrainSearchRateLimitFilter.java',
    'backend/src/main/java/com/easysubway/train/adapter/out/persistence/JdbcTrainSearchCache.java',
    'backend/src/main/java/com/easysubway/transit/adapter/out/persistence/JdbcTransitMasterOverrideRepository.java',
    'backend/src/main/java/com/easysubway/transit/domain/SimplifiedStationLayout.java',
    'backend/src/main/java/com/easysubway/transit/domain/StationWithLines.java',
    'backend/src/main/java/com/easysubway/transit/domain/TransitRegionSummary.java',
    'backend/src/main/java/com/easysubway/user/application/service/UserDataDeletionService.java',
  ]);
  const partition = tracked.findings.filter(({ sourcePath }) => admittedPaths.has(sourcePath));
  assert.equal(partition.length, 34);
  assert.equal(new Set(partition.map(({ sourcePath }) => sourcePath)).size, 21);
  assert.equal(digest(`${JSON.stringify(partition.map(({ identity }) => identity))}\n`), 'cfd7f7082e06c832b058da317a61116baf50d1c9c52a6c33f9c06c8ca5fdf045');
  assert.equal(partition.filter(({ disposition }) => disposition === 'FIXED').length, 10);
  assert.equal(partition.filter(({ disposition }) => disposition === 'FALSE_POSITIVE_EXACT_SUPPRESSION').length, 24);
  assert.deepEqual(reconcileLedger(tracked, tracked.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED')), { ledgerTotal: 195, reported: 16, fixRequired: 16, fixed: 111, falsePositiveExactSuppression: 66, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
  const transitFix = partition.find(({ identity }) => identity === '95314f1f0a737e7d376b637c207324ddc186225d02584b6b3e3a03d274994f30');
  assert.deepEqual([transitFix.ownerIssueUrl, transitFix.ownerIssueTitle, transitFix.disposition, transitFix.suppression], [
    'https://github.com/AquilaXk/easysubway-backend/issues/151',
    '[Fix][Backend][Transit][P1] PostgreSQL 마스터 override 동시 최초 저장 원자화',
    'FIXED',
    null,
  ]);
  const backend116 = partition.filter(({ ownerIssueUrl }) => ownerIssueUrl === 'https://github.com/AquilaXk/easysubway-backend/issues/116');
  assert.equal(backend116.length, 33);
  for (const finding of backend116) {
    assert.equal(finding.ownerIssueUrl, 'https://github.com/AquilaXk/easysubway-backend/issues/116');
    assert.equal(finding.ownerIssueTitle, '[Build][Backend][P1] remaining non-realtime domain SpotBugs remediation');
    assert.equal(finding.ownerIssueState, 'OPEN');
    assert.equal(finding.expiresAt, '2026-11-08');
  }
  const fixed = backend116.filter(({ disposition }) => disposition === 'FIXED');
  for (const finding of fixed) {
    assert.deepEqual(
      [finding.reason, finding.removalCondition, finding.reviewTrigger, finding.suppression],
      [
        'Backend #116 removed this exact non-realtime-domain finding with a focused source-level remediation.',
        'Reopen Backend #116 if this exact finding or a source-equivalent replacement reappears.',
        'Review this terminal decision when the exact source, non-realtime-domain contract, analyzer, or Backend #116 evidence changes.',
        null,
      ],
    );
  }
  const suppressed = backend116.filter(({ disposition }) => disposition === 'FALSE_POSITIVE_EXACT_SUPPRESSION');
  const jdbc = suppressed.filter(({ bugPattern, className }) => bugPattern === 'CT_CONSTRUCTOR_THROW' && !className.startsWith('com.easysubway.train.'));
  const controller = suppressed.filter(({ className }) => className === 'com.easysubway.train.adapter.in.web.TrainSearchContractController');
  const rateFilter = suppressed.filter(({ className }) => className === 'com.easysubway.train.adapter.in.web.TrainSearchRateLimitFilter');
  const collaborators = suppressed.filter(({ bugPattern }) => bugPattern === 'EI_EXPOSE_REP2');
  assert.deepEqual([jdbc.length, controller.length, rateFilter.length, collaborators.length], [11, 1, 1, 11]);
  const categoryMetadata = [
    [jdbc, ['Backend #116 reviewed these exact repository constructors as intentional fail-fast JDBC dialect initialization; no partially initialized repository escapes.', 'Remove this exact suppression when the constructors no longer perform the reviewed JDBC dialect initialization.', 'Review this decision on constructor source/signature, JDBC startup wiring, exception timing, analyzer, or Backend #116 owner-state change.', 'Backend #116 exact fail-fast JDBC dialect initialization.']],
    [controller, ['Backend #116 reviewed TrainSearchContractController construction as intentional Spring Clock-provider resolution; no partially initialized controller escapes.', 'Remove this exact suppression when construction no longer resolves the reviewed Clock provider.', 'Review this decision on constructor source/signature, Clock-provider wiring, exception timing, analyzer, or Backend #116 owner-state change.', 'Backend #116 exact TrainSearchContractController Clock-provider construction.']],
    [rateFilter, ['Backend #116 reviewed TrainSearchRateLimitFilter construction as intentional fail-fast rate-limit and trusted-proxy validation; no partially initialized filter escapes.', 'Remove this exact suppression when construction no longer performs the reviewed rate-limit or trusted-proxy validation.', 'Review this decision on constructor source/signature, rate/trusted-proxy configuration, exception timing, analyzer, or Backend #116 owner-state change.', 'Backend #116 exact TrainSearchRateLimitFilter fail-fast configuration.']],
    [collaborators, ['Backend #116 verified that this exact constructor intentionally retains injected behavior infrastructure; the dependency is not caller-owned value data and must preserve identity.', 'Remove this exact suppression when the constructor no longer retains the reviewed dependency or it becomes a copyable value contract.', 'Review this decision on collaborator identity/mutability, source/member, analyzer, or Backend #116 owner-state change.', 'Backend #116 exact injected behavior collaborator identity contract.']],
  ];
  for (const [findings, [reason, removalCondition, reviewTrigger, suppressionReason]] of categoryMetadata) {
    for (const finding of findings) {
      assert.deepEqual([finding.reason, finding.removalCondition, finding.reviewTrigger, finding.suppression.reason], [reason, removalCondition, reviewTrigger, suppressionReason]);
      assert.deepEqual(Object.keys(finding.suppression), ['kind', 'bugPattern', 'className', 'methodName', 'params', 'returns', 'reason']);
      assert.equal(finding.suppression.kind, 'EXCLUDE_FILTER_EXACT_METHOD');
    }
  }
  const exactMatches = [];
  for (const { suppression } of suppressed) {
    const entry = [suppression.bugPattern, suppression.className, suppression.methodName, suppression.params, suppression.returns];
    if (!exactMatches.some((current) => JSON.stringify(current) === JSON.stringify(entry))) exactMatches.push(entry);
  }
  assert.deepEqual(exactMatches, [
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.ads.adapter.out.persistence.JdbcAdRepository', '<init>', 'javax.sql.DataSource,int', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.ads.adapter.out.persistence.JdbcAdRepository', '<init>', 'org.springframework.jdbc.core.JdbcTemplate,int', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteRouteRepository', '<init>', 'javax.sql.DataSource,com.fasterxml.jackson.databind.ObjectMapper', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteRouteRepository', '<init>', 'org.springframework.jdbc.core.JdbcTemplate', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteRouteRepository', '<init>', 'org.springframework.jdbc.core.JdbcTemplate,com.fasterxml.jackson.databind.ObjectMapper', 'void'],
    ['EI_EXPOSE_REP2', 'com.easysubway.favorite.application.service.FavoriteFacilityService', '<init>', 'com.easysubway.favorite.application.port.out.LoadFavoriteFacilityPort,com.easysubway.favorite.application.port.out.SaveFavoriteFacilityPort,com.easysubway.favorite.application.port.out.DeleteFavoriteFacilityPort,com.easysubway.transit.application.port.out.LoadTransitMasterPort,java.time.Clock', 'void'],
    ['EI_EXPOSE_REP2', 'com.easysubway.favorite.application.service.FavoriteRouteService', '<init>', 'com.easysubway.favorite.application.port.out.LoadFavoriteRoutePort,com.easysubway.favorite.application.port.out.SaveFavoriteRoutePort,com.easysubway.favorite.application.port.out.DeleteFavoriteRoutePort,com.easysubway.route.application.port.out.LoadRouteSearchPort,java.time.Clock', 'void'],
    ['EI_EXPOSE_REP2', 'com.easysubway.favorite.application.service.FavoriteStationService', '<init>', 'com.easysubway.favorite.application.port.out.LoadFavoriteStationPort,com.easysubway.favorite.application.port.out.SaveFavoriteStationPort,com.easysubway.favorite.application.port.out.DeleteFavoriteStationPort,com.easysubway.transit.application.port.out.LoadTransitMasterPort,java.time.Clock', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.field.adapter.out.persistence.JdbcFieldVerificationSessionRepository', '<init>', 'javax.sql.DataSource', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.field.adapter.out.persistence.JdbcFieldVerificationSessionRepository', '<init>', 'org.springframework.jdbc.core.JdbcTemplate', 'void'],
    ['EI_EXPOSE_REP2', 'com.easysubway.health.application.service.HealthCheckService', '<init>', 'javax.sql.DataSource,com.easysubway.transit.application.port.out.LoadTransitMasterPort', 'void'],
    ['EI_EXPOSE_REP2', 'com.easysubway.notice.adapter.out.persistence.JdbcServiceNoticeRepository', '<init>', 'org.springframework.jdbc.core.JdbcTemplate', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.notification.adapter.out.persistence.JdbcNotificationPreferenceRepository', '<init>', 'javax.sql.DataSource', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.notification.adapter.out.persistence.JdbcNotificationPreferenceRepository', '<init>', 'org.springframework.jdbc.core.JdbcTemplate', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.notification.adapter.out.persistence.JdbcPushNotificationOutboxRepository', '<init>', 'javax.sql.DataSource', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.notification.adapter.out.persistence.JdbcPushNotificationOutboxRepository', '<init>', 'org.springframework.jdbc.core.JdbcTemplate', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.train.adapter.in.web.TrainSearchContractController', '<init>', 'com.easysubway.train.application.TrainSearchService,com.fasterxml.jackson.databind.ObjectMapper,org.springframework.beans.factory.ObjectProvider', 'void'],
    ['CT_CONSTRUCTOR_THROW', 'com.easysubway.train.adapter.in.web.TrainSearchRateLimitFilter', '<init>', 'com.fasterxml.jackson.databind.ObjectMapper,int,int,int,int,java.lang.String,org.springframework.beans.factory.ObjectProvider', 'void'],
    ['EI_EXPOSE_REP2', 'com.easysubway.user.application.service.UserDataDeletionService', '<init>', 'com.easysubway.user.application.port.out.DeleteUserFavoriteStationPort,com.easysubway.user.application.port.out.DeleteUserFavoriteFacilityPort,com.easysubway.user.application.port.out.DeleteUserFavoriteRoutePort,com.easysubway.user.application.port.out.AnonymizeUserRouteFeedbackPort,com.easysubway.user.application.port.out.DeleteUserNotificationPreferencePort,com.easysubway.user.application.port.out.DeleteUserPushNotificationPort,com.easysubway.user.application.port.out.DeleteUserMobilityProfilePort,com.easysubway.user.application.port.out.AnonymizeUserFacilityReportPort', 'void'],
  ]);
  assert.equal((readFileSync(new URL('../../backend/quality/spotbugs-exclude.xml', import.meta.url), 'utf8').match(/<Match>/g) ?? []).length, 69);
});

test('remediation policy admits a synthetic fixed terminal absence', () => {
  const remediation = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  remediation.issue = { url: 'https://github.com/AquilaXk/easysubway-backend/issues/4', title: '[Build][Backend][P1] current SpotBugs findings 정리·enforcement 전환' };
  remediation.origin.foundationSha = '3a15efb833b37d5ce051e9591161311dd7952c79';
  remediation.allowedDispositions = ['FIX_REQUIRED', 'FIXED', 'FALSE_POSITIVE_EXACT_SUPPRESSION', 'ACCEPTED_BOUNDED_RISK', 'GENERATED_OR_NON_OWNED_EXCLUSION'];
  remediation.transition = { phase: 'REMEDIATION_IN_PROGRESS', foundationOwnerIssueUrl: 'https://github.com/AquilaXk/easysubway-backend/issues/35', finalOwnerIssueUrl: 'https://github.com/AquilaXk/easysubway-backend/issues/4', foundationFindingCount: 195, foundationFindingIdentitiesSha256: '405bdc428a32ac1c642ff02900e6f5de2bb45a12362ae4a7477f01dcff6e5dd0', finalRequirements: ['ignoreFailures=false', 'FIX_REQUIRED count 0', 'every remaining finding has an exact terminal disposition'] };
  for (const finding of remediation.findings) {
    if (finding.disposition !== 'FIX_REQUIRED') {
      finding.disposition = 'FIX_REQUIRED';
      finding.ownerIssueUrl = 'https://github.com/AquilaXk/easysubway-backend/issues/4';
      finding.ownerIssueTitle = '[Build][Backend][P1] current SpotBugs findings 정리·enforcement 전환';
      finding.reason = 'Exact current SpotBugs finding requires source-level review and remediation in Backend #4.';
      finding.removalCondition = 'Remove this entry when the finding disappears or Backend #4 records a root-approved exact terminal disposition.';
      finding.reviewTrigger = 'Review on any finding identity, source byte, analyzer toolchain, classpath, report schema, or owner-state change.';
      finding.expiresAt = '2026-11-07';
      finding.suppression = null;
    }
  }
  remediation.findings[0].disposition = 'FIXED';
  remediation.findings[0].ownerIssueUrl = 'https://github.com/AquilaXk/easysubway-backend/issues/103';
  remediation.findings[0].ownerIssueTitle = '[Build][Backend][P1] SpotBugs decrease-only remediation ledger foundation';
  remediation.findings[0].reason = 'Child remediation verified this finding no longer appears in the current report.';
  remediation.findings[0].removalCondition = 'Reopen this child remediation if the exact finding reappears.';
  remediation.findings[0].reviewTrigger = 'Review this terminal decision when the exact source, report, or child issue evidence changes.';
  assert.doesNotThrow(() => validatePolicy(remediation, { today: '2026-08-10' }));
});

test('terminal dispositions require child-owned non-generic review metadata', () => {
  const terminal = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const required = terminal.findings.find(({ disposition }) => disposition === 'FIX_REQUIRED');
  assert.ok(required);
  required.disposition = 'FIXED';
  assert.throws(() => validatePolicy(terminal, { today: '2026-08-10' }), /terminal metadata/);
});

test('reconciliation is decrease-only and derives the exact lifecycle summary', () => {
  const base = { identity: 'a', bugPattern: 'P', category: 'C', priority: 1, rank: 1, className: 'example.A', methodName: 'run', methodSignature: '()V', sourcePath: 'backend/src/main/java/example/A.java', startLine: 1, endLine: 1, sourceSha256: 'a'.repeat(64), analyzerInstanceHash: 'a'.repeat(32) };
  const ledger = { findings: [{ ...base, disposition: 'FIX_REQUIRED' }, { ...base, identity: 'b', disposition: 'FIXED' }] };
  assert.deepEqual(reconcileLedger(ledger, [structuredClone(base)]), { ledgerTotal: 2, reported: 1, fixRequired: 1, fixed: 1, falsePositiveExactSuppression: 0, acceptedBoundedRisk: 0, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
  assert.throws(() => reconcileLedger(ledger, []), /required finding is missing/);
  assert.throws(() => reconcileLedger(ledger, [structuredClone(base), { ...base, identity: 'b' }]), /terminal finding remains reported/);
  assert.throws(() => reconcileLedger(ledger, [{ ...base, identity: 'unknown' }]), /unreviewed/);
  assert.throws(() => reconcileLedger(ledger, [{ ...base, className: 'example.Stale' }]), /stale/);
  assert.throws(() => reconcileLedger(ledger, [structuredClone(base), structuredClone(base)]), /duplicate/);
});

test('prior terminal findings cannot reopen into FIX_REQUIRED', () => {
  const first = { identity: 'a', disposition: 'FIX_REQUIRED' }, second = { identity: 'b', disposition: 'FIX_REQUIRED' };
  const current = { findings: [first, second] };
  assert.equal(validatePriorLedger(current, { findings: [first, second] }), true);
  assert.throws(() => validatePriorLedger(current, { findings: [{ ...first, disposition: 'FIXED' }, second] }), /cannot reopen/);
  assert.throws(() => validatePriorLedger(current, { findings: [first] }), /identity ledger/);
  assert.throws(() => validatePriorLedger(current, { findings: [second, first] }), /identity ledger/);
});

test('prior policy resolver preserves exact show bytes and fails closed', () => {
  const sourceSha = 'a'.repeat(40), priorSha = 'b'.repeat(40), current = { findings: [{ identity: 'a', disposition: 'FIX_REQUIRED' }] }, prior = { findings: [{ identity: 'a', disposition: 'FIX_REQUIRED' }] }, raw = `{\n  "findings": [\n    ${JSON.stringify(prior.findings[0])}\n  ]\n}\n`;
  const calls = []; const exec = (args) => { calls.push(args); if (args[0] === 'show') return raw; return args[1] === 'HEAD' ? `${sourceSha}\n` : `${priorSha}\n`; };
  assert.equal(resolvePriorPolicy({ root: '/repo', sourceSha, current, exec }), digest(raw));
  assert.deepEqual(calls, [['rev-parse', 'HEAD'], ['rev-parse', `${sourceSha}^1`], ['show', `${priorSha}:backend/quality/spotbugs-suppression-policy.json`]]);
  assert.throws(() => resolvePriorPolicy({ root: '/repo', sourceSha, current, exec: (args) => args[0] === 'show' ? `${raw}\n` : exec(args) }), /canonical/);
  assert.throws(() => resolvePriorPolicy({ root: '/repo', sourceSha, current, exec: (args) => args[0] === 'show' ? '{' : exec(args) }), /JSON/);
  const invalidRaw = `{\n  "findings": [\n    ${JSON.stringify({ identity: 'a', disposition: 'INVALID' })}\n  ]\n}\n`;
  assert.throws(() => resolvePriorPolicy({ root: '/repo', sourceSha, current, exec: (args) => args[0] === 'show' ? invalidRaw : exec(args) }), /disposition/);
  assert.throws(() => resolvePriorPolicy({ root: '/repo', sourceSha, current, exec: (args) => args[1] === 'HEAD' ? 'c'.repeat(40) : exec(args) }), /checked out/);
  assert.throws(() => resolvePriorPolicy({ root: '/repo', sourceSha, current, exec: (args) => args[1] === `${sourceSha}^1` ? 'not-a-sha' : exec(args) }), /first parent/);
  assert.throws(() => resolvePriorPolicy({ root: '/repo', sourceSha, current, exec: (args) => args[1] === `${sourceSha}^1` ? (() => { throw new Error('shallow'); })() : exec(args) }), /first parent/);
});

test('suppressed findings bind to current captured source, class, and member evidence', () => {
  const finding = { sourcePath, sourceSha256: digest('a\nb\nc\n'), className: 'com.example.Example', methodName: '<init>', methodSignature: '()V', suppression: {} };
  const sourceEntries = [{ repositoryPath: sourcePath, sha256: digest('a\nb\nc\n') }], classEntries = [{ repositoryPath: 'backend/build/classes/java/main/com/example/Example.class' }];
  const bindings = { classDirs: ['/classes'], auxClassPaths: [], javapPath: '/jdk/bin/javap', javaInstallationPath: '/jdk', memberInspector: () => new Map([['<init>\u0000()V', 1]]) };
  assert.equal(validateSuppressedBindings({ findings: [finding], sourceEntries, classEntries, ...bindings }), true);
  assert.throws(() => validateSuppressedBindings({ findings: [{ ...finding, sourceSha256: 'b'.repeat(64) }], sourceEntries, classEntries, ...bindings }), /source/);
  assert.throws(() => validateSuppressedBindings({ findings: [finding], sourceEntries, classEntries: [], ...bindings }), /class/);
  assert.throws(() => validateSuppressedBindings({ findings: [finding], sourceEntries, classEntries, ...bindings, memberInspector: () => new Map() }), /missing|ambiguous/);
  assert.throws(() => validateSuppressedBindings({ findings: [finding], sourceEntries, classEntries, ...bindings, memberInspector: () => new Map([['<init>\u0000()V', 2]]) }), /missing|ambiguous/);
});

test('Phase 2 member binding is exact, once per class, and fails closed', () => {
  const findings = [
    { className: 'com.example.Example', methodName: '<init>', methodSignature: '()V' },
    { className: 'com.example.Example', methodName: 'run', methodSignature: '()V' },
    { className: 'com.example.Future', methodName: null, methodSignature: null }
  ];
  let calls = 0;
  const inspector = ({ className, classDirs, auxClassPaths }) => { calls += 1; assert.equal(className, 'com.example.Example'); assert.deepEqual(classDirs, ['/classes']); assert.deepEqual(auxClassPaths, ['/aux']); return new Map([['<init>\u0000()V', 1], ['run\u0000()V', 1]]); };
  assert.equal(validateMemberBindings({ findings, classDirs: ['/classes'], auxClassPaths: ['/aux'], memberInspector: inspector }), true);
  assert.equal(calls, 1);
  assert.throws(() => validateMemberBindings({ findings: [findings[0]], classDirs: ['/classes'], auxClassPaths: [], memberInspector: () => new Map() }), /missing|ambiguous/);
  assert.throws(() => validateMemberBindings({ findings: [findings[0]], classDirs: ['/classes'], auxClassPaths: [], memberInspector: () => new Map([['<init>\u0000()V', 2]]) }), /missing|ambiguous/);
  assert.throws(() => validateMemberBindings({ findings: [findings[0]], classDirs: ['/classes'], auxClassPaths: [], memberInspector: () => { throw new Error('javap unavailable'); } }), /javap unavailable/);
});

test('closed phase-one policy has empty findings and rejects reviewed or expired states', () => {
  assert.equal(validatePolicy(policy(), { today: '2026-11-07' }), true);
  assert.throws(() => validatePolicy(policy(), { today: '2026-02-30' }), /clock|invalid/);
  const invalid = policy(); invalid.transition.phase = 'FOUNDATION_REVIEWED_FINDINGS';
  assert.throws(() => validatePolicy(invalid), /phase/);
});

test('Gradle evidence is authoritative and internal paths are rehashed', () => {
  const dir = mkdtempSync(join(tmpdir(), 'spotbugs-evidence-'));
  try {
    writeFileSync(join(dir, 'plugin.jar'), 'plugin'); writeFileSync(join(dir, 'engine.jar'), 'engine'); writeFileSync(join(dir, 'aux.jar'), 'aux'); writeFileSync(join(dir, 'detector.jar'), 'detector'); mkdirSync(join(dir, 'bin')); writeFileSync(join(dir, 'bin/java'), 'java'); mkdirSync(join(dir, 'backend/src/main/java/com/example'), { recursive: true }); mkdirSync(join(dir, 'backend/build/classes/java/main/com/example'), { recursive: true }); writeFileSync(join(dir, sourcePath), 'a\nb\nc\n'); writeFileSync(join(dir, 'backend/build/classes/java/main/com/example/Example.class'), 'class'); writeFileSync(join(dir, 'backend/build/classes/java/main/com/example/Example$Nested.class'), 'nested');
    assert.equal(validateEvidence(evidence(dir), policy(), dir), true);
    const nestedClass = evidence(dir); nestedClass.task.classes.push({ path: join(dir, 'backend/build/classes/java/main/com/example/Example$Nested.class'), repositoryPath: 'backend/build/classes/java/main/com/example/Example$Nested.class', sha256: digest('nested') });
    assert.equal(validateEvidence(nestedClass, policy(), dir), true);
    const traversal = evidence(dir); traversal.task.classes[0].repositoryPath = '../outside.class';
    assert.throws(() => validateEvidence(traversal, policy(), dir), /repository-relative/);
    const absolute = evidence(dir); absolute.task.classes[0].repositoryPath = '/tmp/outside.class';
    assert.throws(() => validateEvidence(absolute, policy(), dir), /repository-relative/);
    const mismatchedPath = evidence(dir); mismatchedPath.task.classes[0].repositoryPath = 'backend/build/classes/java/main/com/example/Other.class';
    assert.throws(() => validateEvidence(mismatchedPath, policy(), dir), /escapes repository/);
    const wrongVendor = evidence(dir); wrongVendor.java.vendorMatchesRequestedSpec = false;
    assert.throws(() => validateEvidence(wrongVendor, policy(), dir), /Adoptium/);
    const wrongTaskType = evidence(dir); wrongTaskType.task.runtimeTypeAssignableToDeclared = false;
    assert.throws(() => validateEvidence(wrongTaskType, policy(), dir), /task evidence mismatch/);
    const missingDetector = evidence(dir); rmSync(join(dir, 'detector.jar'));
    assert.throws(() => validateEvidence(missingDetector, policy(), dir), /external plugins is missing/);
    writeFileSync(join(dir, 'detector.jar'), 'detector');
    const missingEngine = evidence(dir); missingEngine.engine.classpath = [];
    assert.throws(() => validateEvidence(missingEngine, policy(), dir), /engine classpath inventory is missing/);
    const conflictingDetector = evidence(dir); conflictingDetector.task.pluginJarFiles.push({ ...conflictingDetector.task.pluginJarFiles[0], component: 'x:other-detector:1', artifact: 'other-detector.jar' });
    assert.throws(() => validateEvidence(conflictingDetector, policy(), dir), /duplicate SpotBugs external plugins/);
    const sharedEngineAndAuxiliary = evidence(dir); sharedEngineAndAuxiliary.task.auxClassPaths = [{ ...sharedEngineAndAuxiliary.engine.classpath[0] }];
    assert.equal(validateEvidence(sharedEngineAndAuxiliary, policy(), dir), true);
    mkdirSync(join(dir, 'engine-role'), { recursive: true }); mkdirSync(join(dir, 'auxiliary-role'), { recursive: true }); writeFileSync(join(dir, 'engine-role/shared.jar'), 'engine-role'); writeFileSync(join(dir, 'auxiliary-role/shared.jar'), 'auxiliary-role');
    const sameFilenameDifferentRoleIdentity = evidence(dir); sameFilenameDifferentRoleIdentity.engine.classpath = [{ component: 'x:engine:1', artifact: 'shared.jar', path: join(dir, 'engine-role/shared.jar'), sha256: digest('engine-role') }]; sameFilenameDifferentRoleIdentity.task.auxClassPaths = [{ component: 'x:auxiliary:1', artifact: 'shared.jar', path: join(dir, 'auxiliary-role/shared.jar'), sha256: digest('auxiliary-role') }];
    assert.equal(validateEvidence(sameFilenameDifferentRoleIdentity, policy(), dir), true);
    writeFileSync(join(dir, 'plugin.jar'), 'tampered');
    assert.throws(() => validateEvidence(evidence(dir), policy(), dir), /stale/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('XML accepts direct children only, nullable line locations, and makes phase one canonically red', () => {
  const dir = mkdtempSync(join(tmpdir(), 'spotbugs-report-'));
  try {
    const file = join(dir, sourcePath); mkdirSync(join(file, '..'), { recursive: true }); writeFileSync(file, 'a\nb\nc\n');
    const clazz = join(dir, 'backend/build/classes/java/main/com/example/Example.class'); mkdirSync(join(clazz, '..'), { recursive: true }); writeFileSync(clazz, 'class');
    const sourceEntry = { path: file, repositoryPath: sourcePath, sha256: digest(readFileSync(file)) };
    const classEntry = { path: clazz, repositoryPath: 'backend/build/classes/java/main/com/example/Example.class', sha256: digest('class') };
    const report = validateReport({ policy: policy(), xml: xml(), html: '<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "https://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd"><html><body>human evidence</body></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] });
    assert.equal(report.outcome, 'FAIL'); assert.equal(report.summary.unclassified, 1); assert.equal(report.findings[0].methodName, '<init>'); assert.equal(report.findings[0].analyzerInstanceHash, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
    const distinctAnalyzerInstances = xml().replace('<Errors errors="0" missingClasses="0"/><FindBugsSummary total_bugs="1"/>', '<BugInstance type="EI_EXPOSE_REP" category="MALICIOUS_CODE" priority="2" rank="18" instanceHash="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" instanceOccurrenceNum="1" instanceOccurrenceMax="1"><Class classname="com.example.Example"/><Method classname="com.example.Example" name="&lt;init&gt;" signature="()V"/><SourceLine classname="com.example.Example" sourcepath="com/example/Example.java" start="2" end="2"/></BugInstance><Errors errors="0" missingClasses="0"/><FindBugsSummary total_bugs="2"/>');
    assert.equal(validateReport({ policy: policy(), xml: distinctAnalyzerInstances, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }).summary.reported, 2);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace(' instanceHash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"', ''), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /instanceHash/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('instanceHash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"', 'instanceHash=""'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /instanceHash/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /instanceHash/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('instanceOccurrenceNum="0"', 'instanceOccurrenceNum="01"'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /instanceOccurrenceNum/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace(' instanceOccurrenceMax="0"', ''), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /instanceOccurrenceMax/);
    const hashCollisionWithChangedSelectedFields = xml().replace('<Errors errors="0" missingClasses="0"/><FindBugsSummary total_bugs="1"/>', '<BugInstance type="OTHER" category="OTHER" priority="1" rank="1" instanceHash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" instanceOccurrenceNum="99" instanceOccurrenceMax="100"><Class classname="com.example.Example"/><Method classname="com.example.Example" name="other" signature="()V"/><SourceLine classname="com.example.Example" sourcepath="com/example/Example.java" start="1" end="1" primary="true"/><SourceLine classname="com.example.Example" sourcepath="com/example/Example.java" start="2" end="2" role="SECONDARY"/></BugInstance><Errors errors="0" missingClasses="0"/><FindBugsSummary total_bugs="2"/>');
    assert.throws(() => validateReport({ policy: policy(), xml: hashCollisionWithChangedSelectedFields, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /duplicate analyzer instance hash/);
    const cdata = xml().replace('<SourceLine classname="com.example.Example" sourcepath="com/example/Example.java" start="2" end="2"/>', '<SourceLine classname="com.example.Example" sourcepath="com/example/Example.java" start="2" end="2"/><Details><![CDATA[official details <!DOCTYPE opaque><!ENTITY opaque> <p><code>path\\value &unknown;</code></p><BugInstance><Class/></BugInstance></BugCollection>]]></Details>');
    const cdataReport = validateReport({ policy: policy(), xml: cdata, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] });
    assert.equal(cdataReport.summary.reported, 1); assert.equal(cdataReport.findings.length, 1);
    const quotedGreaterThan = xml().replace('rank="18"', 'rank="18" note="a > b"');
    assert.equal(validateReport({ policy: policy(), xml: quotedGreaterThan, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }).summary.reported, 1);
    const commentGreaterThan = xml().replace('<!-- current SpotBugs report -->', '<!-- current > SpotBugs report -->');
    assert.equal(validateReport({ policy: policy(), xml: commentGreaterThan, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }).summary.reported, 1);
    for (const [label, malformed] of [
      ['unclosed CDATA', cdata.replace(']]></Details>', '</Details>')],
      ['CDATA before root', cdata.replace('<BugCollection>', '<![CDATA[outside]]><BugCollection>')],
      ['CDATA after root', cdata.replace(/<\/BugCollection>$/, '</BugCollection><![CDATA[outside]]>')],
      ['ordinary text CDATA terminator', xml().replace('</BugCollection>', ']]></BugCollection>')],
      ['unclosed quoted attribute', xml().replace('rank="18"', 'rank="unterminated>')]
    ]) assert.throws(() => validateReport({ policy: policy(), xml: malformed, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /XML/, label);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('</BugCollection>', '&unknown;</BugCollection>'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /entity/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('<BugCollection>', '<!DOCTYPE root><BugCollection>'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /XML/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('<BugCollection>', '<!ENTITY x "y"><BugCollection>'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /XML/);
    const nested = xml().replace('<Class classname="com.example.Example"/>', '<Detail><Class classname="com.example.Example"/></Detail>');
    assert.throws(() => validateReport({ policy: policy(), xml: nested, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /direct Class/);
    const oneSided = xml().replace(' signature="()V"', '');
    assert.throws(() => validateReport({ policy: policy(), xml: oneSided, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /Method/);
    const methodPrimary = xml().replace('<Method classname="com.example.Example" name="&lt;init&gt;" signature="()V"/>', '<Method classname="com.example.Example" name="secondary" signature="()V" role="SECONDARY"/><Method classname="com.example.Example" name="&lt;init&gt;" signature="()V" primary="true"/>');
    assert.equal(validateReport({ policy: policy(), xml: methodPrimary, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }).findings[0].methodName, '<init>');
    const sourcePrimary = xml().replace('<SourceLine classname="com.example.Example" sourcepath="com/example/Example.java" start="2" end="2"/>', '<SourceLine classname="com.example.Example" sourcepath="com/example/Other.java" start="1" end="1" role="SECONDARY"/><SourceLine classname="com.example.Example" sourcepath="com/example/Example.java" start="2" end="2" primary="true"/>');
    assert.equal(validateReport({ policy: policy(), xml: sourcePrimary, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }).findings[0].sourcePath, sourcePath);
    const soleWithoutPrimary = xml().replace('<Class classname="com.example.Example"/>', '<Class classname="com.example.Example" role="PRIMARY"/>');
    assert.equal(validateReport({ policy: policy(), xml: soleWithoutPrimary, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }).summary.reported, 1);
    for (const [label, invalid] of [
      ['no direct Class', xml().replace('<Class classname="com.example.Example"/>', '')],
      ['multiple Class without primary', xml().replace('<Class classname="com.example.Example"/>', '<Class classname="com.example.Example"/><Class classname="com.example.Example" role="SECONDARY"/>')],
      ['multiple Method primary', xml().replace('<Method classname="com.example.Example" name="&lt;init&gt;" signature="()V"/>', '<Method classname="com.example.Example" name="one" signature="()V" primary="true"/><Method classname="com.example.Example" name="two" signature="()V" primary="true"/>')],
      ['classname mismatch', xml().replace('<SourceLine classname="com.example.Example"', '<SourceLine classname="com.example.Other"')]
    ]) assert.throws(() => validateReport({ policy: policy(), xml: invalid, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /direct|primary|classname/, label);
    const nestedLookalike = xml().replace('</BugInstance>', '<Details><Method classname="com.example.Other" name="ignored" signature="()V"/><SourceLine classname="com.example.Other" sourcepath="com/example/Other.java"/></Details></BugInstance>');
    assert.equal(validateReport({ policy: policy(), xml: nestedLookalike, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }).findings[0].methodName, '<init>');
    assert.throws(() => validateReport({ policy: policy(), xml: xml(), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [] }), /classes inventory/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('errors="0"', 'errors="1"'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /Errors/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('start="2" end="2"', 'start="4" end="4"'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /outside/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('<Errors', '<MissingClass/><Errors'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /Errors/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('<Errors errors="0" missingClasses="0"/>', '<Errors errors="0" missingClasses="0"><MissingClass/></Errors>'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /Errors/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('exclude filter is exactly the one policy-owned Class matcher', () => {
  assert.equal(validateExcludeFilter(policy(), '<?xml version="1.0"?><FindBugsFilter><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match></FindBugsFilter>'), true);
  const trackedPolicy = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const tracked = readFileSync(new URL('../../backend/quality/spotbugs-exclude.xml', import.meta.url), 'utf8');
  assert.equal(validateExcludeFilter(trackedPolicy, tracked), true);
  assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match><Class name="com.other.Unapproved"/></FindBugsFilter>'), /Match inventory/);
  assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match><Or><Class name="com.other.Unapproved"/></Or></FindBugsFilter>'), /Match inventory/);
  assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Package name="com.easysubway"/></Match></FindBugsFilter>'), /exact/);
  for (const child of ['Bug', 'Class', 'Method', 'Package']) assert.throws(() => validateExcludeFilter(policy(), `<FindBugsFilter><Match><Class name="com.easysubway.EasySubwayBackendApplication"><${child} name="com.easysubway"/></Class></Match></FindBugsFilter>`), /exact/);
  assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Class name="com.easysubway.*"/></Match></FindBugsFilter>'), /exact/);
  assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Class name="&unknown;"/></Match></FindBugsFilter>'), /entity|malformed/);
    assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><!-- broken -- comment --><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match></FindBugsFilter>'), /comment/);
    assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match></FindBugsFilter>garbage'), /unconsumed/);
    assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter/><FindBugsFilter/>'), /malformed/);
});

test('terminal suppression filters require one ordered exact method Match', () => {
  const terminal = JSON.parse(readFileSync(new URL('../../backend/quality/spotbugs-suppression-policy.json', import.meta.url), 'utf8'));
  const finding = terminal.findings.find(({ suppression }) => suppression !== null);
  const filter = readFileSync(new URL('../../backend/quality/spotbugs-exclude.xml', import.meta.url), 'utf8');
  assert.equal(validatePolicy(terminal, { today: '2026-08-10' }), true);
  assert.equal(validateExcludeFilter(terminal, filter), true);
  assert.deepEqual(reconcileLedger(terminal, terminal.findings.filter(({ disposition }) => disposition === 'FIX_REQUIRED')), { ledgerTotal: 195, reported: 16, fixRequired: 16, fixed: 111, falsePositiveExactSuppression: 66, acceptedBoundedRisk: 2, generatedOrNonOwnedExclusion: 0, unclassified: 0, missing: 0, duplicate: 0, stale: 0 });
  for (const mutate of [
    (value) => { value.suppression.params = 'java.lang.String,java.util.List'; },
    (value) => { value.suppression.returns = 'java.lang.Void'; }
  ]) { const invalid = structuredClone(terminal); mutate(invalid.findings.find(({ identity }) => identity === finding.identity)); assert.throws(() => validatePolicy(invalid, { today: '2026-08-10' }), /descriptor mismatch/); }
  const selectedMethod = `<Method name="${finding.suppression.methodName === '<init>' ? '&lt;init&gt;' : finding.suppression.methodName}" params="${finding.suppression.params}" returns="${finding.suppression.returns}"/>`;
  const selectedMatch = `<Match><Bug pattern="${finding.suppression.bugPattern}"/><Class name="${finding.suppression.className}"/>${selectedMethod}</Match>`;
  for (const [index, invalid] of [
    filter.replace(finding.suppression.bugPattern, `${finding.suppression.bugPattern},OTHER`),
    filter.replace(finding.suppression.bugPattern, `~${finding.suppression.bugPattern}`),
    filter.replace(finding.suppression.className, `${finding.suppression.className}*`),
    filter.replace(/\t<Match>\n\t\t<Bug[\s\S]*?\n\t<\/Match>\n/, ''),
    filter.replace('</FindBugsFilter>', `${selectedMatch}</FindBugsFilter>`),
    filter.replace('<Match>\n\t\t<Class name="com.easysubway.EasySubwayBackendApplication"/>\n\t</Match>', selectedMatch),
    filter.replace(selectedMethod, ''),
    filter.replace(selectedMethod, selectedMethod.replace(/ name="[^"]+"/, '')),
    filter.replace(selectedMethod, selectedMethod.replace(/ params="[^"]+"/, '')),
    filter.replace(selectedMethod, selectedMethod.replace(/ returns="[^"]+"/, '')),
    filter.replace(`returns="${finding.suppression.returns}"`, 'returns="java.lang.Void"')
  ].entries()) assert.throws(
    () => validateExcludeFilter(terminal, invalid),
    index >= 6 && index <= 9 ? /exclude filter terminal Match/ : /XML|exact|Match/,
    `invalid filter mutation ${index}`,
  );
});

test('workflow preserves #87 and uploads exactly four Phase-1 files before final enforcement', () => {
  const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
  assert.equal(validateWorkflow(workflow), true);
  assert.throws(() => validateWorkflow(workflow.replace('          fetch-depth: 2\n', '          fetch-depth: 1\n')), /prior-policy checkout history/);
  assert.match(workflow, /name: Run SpotBugs main analysis\n        id: spotbugs_main\n        working-directory: backend\n        env:\n          EASYSUBWAY_CONTRACTS_BUNDLE: \$\{\{ runner\.temp \}\}\/backend-contracts\.json\n        run: \.\/gradlew spotbugsMain --no-daemon/);
  assert.match(workflow, /name: Capture SpotBugs main evidence\n        id: spotbugs_inputs\n        shell: bash\n        working-directory: backend\n        env:\n          EASYSUBWAY_CONTRACTS_BUNDLE: \$\{\{ runner\.temp \}\}\/backend-contracts\.json/);
  assert.match(workflow, /\.\/gradlew writeSpotbugsMainEvidence --no-daemon/);
  assert.match(workflow, /cp build\/spotbugs\/spotbugsMain-evidence\.json/);
});

test('Gradle binds SpotBugs 6.2.2 public launcher and keeps engine, auxiliary, and detector-plugin artifact ownership separate', () => {
  const build = readFileSync(new URL('../../backend/build.gradle', import.meta.url), 'utf8');
  assert.match(build, /\blauncher = javaToolchains\.launcherFor/);
  assert.match(build, /task\.launcher\.get\(\)\.metadata/);
  assert.match(build, /spotbugs\.toolVersion\.get\(\)/);
  assert.match(build, /artifactMap\(configurations\.spotbugs, 'SpotBugs engine'\)/);
  assert.match(build, /artifactMap\(configurations\.spotbugsSlf4j, 'SpotBugs SLF4J provider'\)/);
  assert.match(build, /mergeArtifactMaps\('SpotBugs task engine'/);
  assert.match(build, /existing != null && existing != identity/);
  assert.match(build, /artifactMap\(configurations\.compileClasspath, 'SpotBugs auxiliary classpath'\)/);
  assert.match(build, /artifactMap\(configurations\.spotbugsPlugins, 'SpotBugs external plugins'\)/);
  assert.match(build, /task\.spotbugsClasspath\.files, engineArtifacts/);
  assert.match(build, /task\.auxClassPaths\.files, auxiliaryArtifacts/);
  assert.match(build, /task\.pluginJarFiles\.files, pluginArtifacts/);
  assert.match(build, /exactArtifactFiles\(task\.spotbugsClasspath\.files, engineArtifacts/);
  assert.match(build, /exactArtifactFiles\(task\.auxClassPaths\.files, auxiliaryArtifacts/);
  assert.match(build, /exactArtifactFiles\(task\.pluginJarFiles\.files, pluginArtifacts/);
  assert.doesNotMatch(build, /assertDisjoint|input graphs overlap/);
  assert.match(build, /def declaredTaskType = com\.github\.spotbugs\.snom\.SpotBugsTask/);
  assert.match(build, /declaredTaskType\.isAssignableFrom\(task\.class\)/);
  assert.doesNotMatch(build, /SpotBugsTask\$/);
  assert.match(build, /def taskClasses = task\.classes/);
  assert.match(build, /taskClasses\.asFileTree\.files/);
  assert.doesNotMatch(build, /task\.javaLauncher/);
});

test('sanitized analyzer projection preserves exact Java proof and source identities', () => {
  const dir = mkdtempSync(join(tmpdir(), 'spotbugs-projection-'));
  try {
    writeFileSync(join(dir, 'plugin.jar'), 'plugin'); writeFileSync(join(dir, 'engine.jar'), 'engine'); writeFileSync(join(dir, 'aux.jar'), 'aux'); writeFileSync(join(dir, 'detector.jar'), 'detector'); mkdirSync(join(dir, 'bin')); writeFileSync(join(dir, 'bin/java'), 'java'); mkdirSync(join(dir, 'backend/src/main/java/com/example'), { recursive: true }); mkdirSync(join(dir, 'backend/build/classes/java/main/com/example'), { recursive: true }); writeFileSync(join(dir, sourcePath), 'a\nb\nc\n'); writeFileSync(join(dir, 'backend/build/classes/java/main/com/example/Example.class'), 'class');
    const current = evidence(dir); assert.equal(validateEvidence(current, policy(), dir), true);
    const projected = safeProject(current);
    assert.deepEqual(Object.keys(projected.java), ['requestedVendor', 'vendorMatchesRequestedSpec', 'vendor', 'languageVersion', 'runtimeVersion', 'jvmVersion', 'launcherSha256']);
    assert.deepEqual(Object.keys(projected.task), ['name', 'path', 'declaredType', 'runtimeType', 'runtimeTypeAssignableToDeclared', 'ignoreFailures', 'sourceDirs', 'classDirs', 'sources', 'classes', 'auxClassPaths', 'pluginJarFiles', 'excludeFilter', 'xmlOutput', 'htmlOutput']);
    assert.deepEqual(projected.task.sources, [{ path: sourcePath, sha256: digest('a\nb\nc\n') }]);
    const missingJavaProof = evidence(dir); delete missingJavaProof.java.requestedVendor;
    assert.throws(() => validateEvidence(missingJavaProof, policy(), dir), /Java evidence key order/);
    const missingSources = evidence(dir); delete missingSources.task.sources;
    assert.throws(() => validateEvidence(missingSources, policy(), dir), /task evidence key order/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('PR-head provenance binds a distinct synthetic checkout head and rejects missing, malformed, or tampered values', () => {
  const dir = mkdtempSync(join(tmpdir(), 'spotbugs-pr-head-'));
  const write = (relativePath, value) => { const target = join(dir, relativePath); mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, value); };
  const gate = new URL('./backend-spotbugs-gate.mjs', import.meta.url).pathname;
  const run = (args) => spawnSync(process.execPath, [gate, ...args], { cwd: dir, encoding: 'utf8' });
  try {
    const buildScript = readFileSync(new URL('../../backend/build.gradle', import.meta.url));
    const fixturePolicy = policy(); fixturePolicy.toolchain.spotbugsGradlePlugin.buildScriptSha256 = digest(buildScript); const fixturePolicyBytes = `${JSON.stringify(fixturePolicy, null, 2)}\n`;
    write('backend/build.gradle', buildScript);
    write('backend/quality/spotbugs-suppression-policy.json', fixturePolicyBytes);
    write('backend/quality/spotbugs-exclude.xml', '<?xml version="1.0"?><FindBugsFilter><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match></FindBugsFilter>');
    write('backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', readFileSync(new URL('../../backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', import.meta.url)));
    write(sourcePath, 'a\nb\nc\n'); write('backend/build/classes/java/main/com/example/Example.class', 'class'); write('backend/build/classes/java/main/com/example/Example$Nested.class', 'nested'); write('plugin.jar', 'plugin'); write('engine.jar', 'engine'); write('aux.jar', 'aux'); write('detector.jar', 'detector'); write('java-home/bin/java', 'java'); const currentEvidence = evidence(dir); currentEvidence.task.classes.push({ path: join(dir, 'backend/build/classes/java/main/com/example/Example$Nested.class'), repositoryPath: 'backend/build/classes/java/main/com/example/Example$Nested.class', sha256: digest('nested') }); currentEvidence.java.installationPath = join(dir, 'java-home'); currentEvidence.java.launcherPath = join(dir, 'java-home/bin/java'); currentEvidence.java.launcherSha256 = digest('java'); const rawXml = xml().replace('<BugCollection>', `<BugCollection><Project projectName="${currentEvidence.task.classes[1].path}"/>`), rawHtml = `<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "https://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd"><html><body>${currentEvidence.engine.classpath[0].path}</body></html>`; assert.doesNotMatch(sanitizeReports({ rawXml, rawHtml, evidence: currentEvidence, root: dir }).xml, /\/tmp\//); for (const suffix of ['-extra', '/child', '.extra']) assert.throws(() => sanitizeReports({ rawXml, rawHtml: `<html>${currentEvidence.engine.classpath[0].path}${suffix}</html>`, evidence: currentEvidence, root: dir }), /partial token/); assert.throws(() => sanitizeReports({ rawXml, rawHtml: `<html>x${currentEvidence.engine.classpath[0].path}</html>`, evidence: currentEvidence, root: dir }), /partial token/); assert.throws(() => sanitizeReports({ rawXml, rawHtml: '<html>/home/unknown</html>', evidence: currentEvidence, root: dir }), /unmapped host path/); const sameIdentity = structuredClone(currentEvidence); sameIdentity.task.auxClassPaths = [{ ...sameIdentity.engine.classpath[0] }]; assert.equal(sanitizeReports({ rawXml, rawHtml, evidence: sameIdentity, root: dir }).html.includes('evidence-path:['), false); const crossRole = structuredClone(currentEvidence); crossRole.plugin.implementationPath = crossRole.engine.classpath[0].path; crossRole.plugin.implementationSha256 = digest('engine'); assert.equal(validateEvidence(crossRole, policy(), dir), true); assert.match(sanitizeReports({ rawXml, rawHtml, evidence: crossRole, root: dir }).html, new RegExp(`evidence-path:\\[dependency:x:y:1/engine\\.jar\\|gradle-plugin:com\\.github\\.spotbugs/${digest('engine')}\\]`)); write('raw.xml', rawXml); write('raw.html', rawHtml); write('evidence.json', JSON.stringify(currentEvidence));
    execFileSync('git', ['init', '-q'], { cwd: dir }); execFileSync('git', ['config', 'user.email', 'test@example.invalid'], { cwd: dir }); execFileSync('git', ['config', 'user.name', 'SpotBugs fixture'], { cwd: dir }); execFileSync('git', ['add', '.'], { cwd: dir }); execFileSync('git', ['commit', '-qm', 'prior fixture'], { cwd: dir }); execFileSync('git', ['commit', '--allow-empty', '-qm', 'current fixture'], { cwd: dir });
    const sourceSha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: dir, encoding: 'utf8' }).trim(), prHeadSha = 'b'.repeat(40), output = join(dir, 'result.json'), summary = join(dir, 'summary.md');
    const options = ['--repo-root', dir, '--policy', join(dir, 'backend/quality/spotbugs-suppression-policy.json'), '--raw-xml', join(dir, 'raw.xml'), '--raw-html', join(dir, 'raw.html'), '--xml', join(dir, 'staged.xml'), '--html', join(dir, 'staged.html'), '--evidence', join(dir, 'evidence.json'), '--source-sha', sourceSha, '--pull-request-head-sha', prHeadSha, '--output', output, '--summary', summary];
    const validated = run(['validate', ...options]); assert.equal(validated.status, 0, validated.stderr);
    const result = JSON.parse(readFileSync(output, 'utf8')); assert.deepEqual(Object.keys(result), ['schemaVersion', 'artifactKind', 'sourceSha', 'pullRequestHeadSha', 'analyzer', 'inputs', 'reports', 'findings', 'summary', 'outcome']); assert.equal(result.sourceSha, sourceSha); assert.equal(result.pullRequestHeadSha, prHeadSha); assert.deepEqual(Object.keys(result.inputs), ['sourceDigest', 'classDigest', 'classpathDigest', 'pluginClasspathDigest', 'pluginImplementationDigest', 'engineClasspathDigest', 'javaLauncherDigest', 'policyDigest', 'priorPolicyDigest', 'excludeFilterDigest']); assert.equal(result.inputs.priorPolicyDigest, digest(fixturePolicyBytes)); assert.deepEqual(Object.keys(result.reports), ['transform', 'xml', 'html']); assert.equal(result.reports.transform.id, 'spotbugs-report-sanitizer-v1'); assert.doesNotMatch(readFileSync(join(dir, 'staged.xml'), 'utf8'), /\/tmp\//); assert.doesNotMatch(readFileSync(join(dir, 'staged.html'), 'utf8'), /\/tmp\//);
    const final = run(['validate-final', ...options]); assert.equal(final.status, 1); assert.match(final.stderr, /DISCOVERY_REMOTE_RED/);
    writeFileSync(join(dir, 'staged.xml'), '<BugCollection/>'); const stagedTamper = run(['validate-final', ...options]); assert.equal(stagedTamper.status, 1); assert.match(stagedTamper.stderr, /sanitized report tamper/); assert.equal(run(['validate', ...options]).status, 0);
    writeFileSync(output, '{}\n'); const tampered = run(['validate-final', ...options]); assert.equal(tampered.status, 1); assert.match(tampered.stderr, /artifact tamper/);
    const noneOutput = join(dir, 'none-result.json'), noneSummary = join(dir, 'none-summary.md'), noneOptions = [...options.slice(0, options.indexOf('--pull-request-head-sha') + 1), 'none', '--output', noneOutput, '--summary', noneSummary];
    const none = run(['validate', ...noneOptions]); assert.equal(none.status, 0, none.stderr); assert.equal(JSON.parse(readFileSync(noneOutput, 'utf8')).pullRequestHeadSha, null);
    const missing = run(['validate', ...options.filter((value) => value !== '--pull-request-head-sha' && value !== prHeadSha)]); assert.equal(missing.status, 1); assert.match(missing.stderr, /pull-request-head-sha/);
    const malformed = run(['validate', ...options.map((value) => value === prHeadSha ? 'invalid' : value)]); assert.equal(malformed.status, 1); assert.match(malformed.stderr, /pull-request-head-sha/);
    const missingRawXml = run(['validate', ...options.filter((value) => value !== '--raw-xml' && value !== join(dir, 'raw.xml'))]); assert.equal(missingRawXml.status, 1); assert.match(missingRawXml.stderr, /raw-xml/);
    const overlappingRawAndStaged = run(['validate', ...options.map((value) => value === join(dir, 'staged.xml') ? join(dir, 'raw.xml') : value)]); assert.equal(overlappingRawAndStaged.status, 1); assert.match(overlappingRawAndStaged.stderr, /distinct/);
    const sameSourceAndPrHead = run(['validate', ...options.map((value) => value === prHeadSha ? sourceSha : value)]); assert.equal(sameSourceAndPrHead.status, 1); assert.match(sameSourceAndPrHead.stderr, /head SHA must differ/);
    assert.equal(pullRequestHeadSha('none'), null); assert.equal(pullRequestHeadSha(prHeadSha), prHeadSha); assert.throws(() => pullRequestHeadSha('invalid'), /pull-request-head-sha/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
