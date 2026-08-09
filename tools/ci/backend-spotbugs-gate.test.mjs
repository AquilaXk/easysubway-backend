import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import { safeProject, validateEvidence, validateExcludeFilter, validatePolicy, validateReport, validateWorkflow } from './backend-spotbugs-gate.mjs';

const digest = (value) => createHash('sha256').update(value).digest('hex');
const sourcePath = 'backend/src/main/java/com/example/Example.java';
const policy = () => ({
  schemaVersion: 1, artifactKind: 'backend-spotbugs-policy-v1', gateId: 'backend-spotbugs-main',
  issue: { url: 'https://github.com/AquilaXk/easysubway-backend/issues/35', title: '[CI][Backend][P1] SpotBugs report·finding-policy required gate' },
  origin: { repository: 'AquilaXk/easysubway-backend', foundationSha: '5334c98ee146a117338789c261d439aa2153d0b4' },
  toolchain: {
    gradleVersion: null,
    spotbugsGradlePlugin: { id: 'com.github.spotbugs', requestedVersion: '6.2.2', buildScriptSha256: 'a'.repeat(64), implementationClass: null, implementationJarSha256: null },
    spotbugsEngine: { toolVersion: null, classpath: null }, javaLauncher: { vendorSpec: 'ADOPTIUM', languageVersion: 21 }, task: 'spotbugsMain'
  },
  analysis: { sourceSet: 'main', sourceRoot: 'backend/src/main/java', classOutputRoot: 'backend/build/classes/java/main', excludeFilter: 'backend/quality/spotbugs-exclude.xml', gradleIgnoreFailures: true },
  spotbugsTest: { disposition: 'NOT_REQUIRED_CURRENT', reason: 'test classes are not shipped runtime code', reviewTriggers: ['test output becomes packaged/runtime', 'custom source set mixes test and production outputs', 'bootJar or image admits test classes', 'Gradle source-set/classpath semantics change'] },
  exclusions: [{ className: 'com.easysubway.EasySubwayBackendApplication', sourcePath: 'backend/src/main/java/com/easysubway/EasySubwayBackendApplication.java', sourceSha256: 'd0e6c8a5ab74a8c10fead9443573e9acb5b4c240e71f85246744a18b1601aa53', reason: 'bootstrap-only Spring Boot/scheduling entrypoint; product business logic 없음', ownerIssueUrl: 'https://github.com/AquilaXk/easysubway-backend/issues/4', ownerIssueTitle: '[Build][Backend][P1] current SpotBugs findings 정리·enforcement 전환', ownerIssueState: 'OPEN', removalCondition: 'Backend #4 reviews the class against the current report and either removes the filter or records the exact terminal justification.', reviewTriggers: ['source byte change', 'class/member/annotation or responsibility change', 'plugin/JDK/task/source-set change', 'broader class/package Match', 'Backend #4 remediation'] }],
  findingIdentity: { algorithm: 'sha256-canonical-json-v1', fields: ['bugPattern', 'category', 'priority', 'rank', 'className', 'methodName', 'methodSignature', 'sourcePath', 'startLine', 'endLine', 'sourceSha256'] },
  allowedDispositions: ['FIX_REQUIRED', 'FALSE_POSITIVE_EXACT_SUPPRESSION', 'ACCEPTED_BOUNDED_RISK', 'GENERATED_OR_NON_OWNED_EXCLUSION'], findings: [],
  transition: { phase: 'DISCOVERY_REMOTE_RED', foundationOwnerIssueUrl: 'https://github.com/AquilaXk/easysubway-backend/issues/35', finalOwnerIssueUrl: 'https://github.com/AquilaXk/easysubway-backend/issues/4', finalRequirements: ['ignoreFailures=false', 'FIX_REQUIRED count 0', 'every remaining finding has an exact terminal disposition'] }
});
const xml = () => '<?xml version="1.0" encoding="UTF-8"?><!-- current SpotBugs report --><BugCollection><BugInstance type="EI_EXPOSE_REP" category="MALICIOUS_CODE" priority="2" rank="18"><Class classname="com.example.Example"/><Method name="&lt;init&gt;" signature="()V"/><SourceLine sourcepath="com/example/Example.java" start="2" end="2"/></BugInstance><Errors errors="0" missingClasses="0"/><FindBugsSummary total_bugs="1"/></BugCollection>';
const evidence = (dir) => ({
  schemaVersion: 1, gradle: { version: '8.14.3' }, plugin: { id: 'com.github.spotbugs', requestedVersion: '6.2.2', implementationClass: 'example.Plugin', implementationPath: join(dir, 'plugin.jar'), implementationSha256: digest('plugin') }, engine: { toolVersion: '4.9.8', classpath: [{ component: 'x:y:1', artifact: 'engine.jar', path: join(dir, 'engine.jar'), sha256: digest('engine') }] }, java: { requestedVendor: 'ADOPTIUM', vendorMatchesRequestedSpec: true, vendor: 'Eclipse Temurin', languageVersion: 21, runtimeVersion: '21.0.8+9', jvmVersion: '21.0.8+9', installationPath: dir, launcherPath: join(dir, 'bin/java'), launcherSha256: digest('java') }, task: { name: 'spotbugsMain', path: ':spotbugsMain', declaredType: 'com.github.spotbugs.snom.SpotBugsTask', runtimeType: 'com.github.spotbugs.snom.SpotBugsTask_Decorated', runtimeTypeAssignableToDeclared: true, ignoreFailures: true, sourceDirs: [{ path: join(dir, 'backend/src/main/java'), repositoryPath: 'backend/src/main/java' }], classDirs: [{ path: join(dir, 'backend/build/classes/java/main'), repositoryPath: 'backend/build/classes/java/main' }], sources: [{ path: join(dir, sourcePath), repositoryPath: sourcePath, sha256: digest('a\nb\nc\n') }], classes: [{ path: join(dir, 'backend/build/classes/java/main/com/example/Example.class'), repositoryPath: 'backend/build/classes/java/main/com/example/Example.class', sha256: digest('class') }], auxClassPaths: [{ component: 'x:y:1', artifact: 'aux.jar', path: join(dir, 'aux.jar'), sha256: digest('aux') }], pluginJarFiles: [{ component: 'x:detector:1', artifact: 'detector.jar', path: join(dir, 'detector.jar'), sha256: digest('detector') }], excludeFilter: 'backend/quality/spotbugs-exclude.xml', xmlOutput: 'backend/build/reports/spotbugs/spotbugsMain.xml', htmlOutput: 'backend/build/reports/spotbugs/spotbugsMain.html' }
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
    writeFileSync(join(dir, 'plugin.jar'), 'plugin'); writeFileSync(join(dir, 'engine.jar'), 'engine'); writeFileSync(join(dir, 'aux.jar'), 'aux'); writeFileSync(join(dir, 'detector.jar'), 'detector'); mkdirSync(join(dir, 'bin')); writeFileSync(join(dir, 'bin/java'), 'java'); mkdirSync(join(dir, 'backend/src/main/java/com/example'), { recursive: true }); mkdirSync(join(dir, 'backend/build/classes/java/main/com/example'), { recursive: true }); writeFileSync(join(dir, sourcePath), 'a\nb\nc\n'); writeFileSync(join(dir, 'backend/build/classes/java/main/com/example/Example.class'), 'class');
    assert.equal(validateEvidence(evidence(dir), policy(), dir), true);
    const wrongVendor = evidence(dir); wrongVendor.java.vendorMatchesRequestedSpec = false;
    assert.throws(() => validateEvidence(wrongVendor, policy(), dir), /Adoptium/);
    const wrongTaskType = evidence(dir); wrongTaskType.task.runtimeTypeAssignableToDeclared = false;
    assert.throws(() => validateEvidence(wrongTaskType, policy(), dir), /task evidence mismatch/);
    const missingDetector = evidence(dir); rmSync(join(dir, 'detector.jar'));
    assert.throws(() => validateEvidence(missingDetector, policy(), dir), /external plugins is missing/);
    writeFileSync(join(dir, 'detector.jar'), 'detector');
    const duplicateDetector = evidence(dir); duplicateDetector.task.pluginJarFiles.push({ ...duplicateDetector.task.pluginJarFiles[0] });
    assert.throws(() => validateEvidence(duplicateDetector, policy(), dir), /duplicate SpotBugs external plugins/);
    const overlappingGraph = evidence(dir); overlappingGraph.task.pluginJarFiles = [{ ...overlappingGraph.task.pluginJarFiles[0], component: 'x:y:1', artifact: 'engine.jar', path: join(dir, 'engine.jar'), sha256: digest('engine') }];
    assert.throws(() => validateEvidence(overlappingGraph, policy(), dir), /graphs overlap/);
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
    assert.equal(report.outcome, 'FAIL'); assert.equal(report.summary.unclassified, 1); assert.equal(report.findings[0].methodName, '<init>');
    const nested = xml().replace('<Class classname="com.example.Example"/>', '<Detail><Class classname="com.example.Example"/></Detail>');
    assert.throws(() => validateReport({ policy: policy(), xml: nested, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /direct Class/);
    const oneSided = xml().replace(' signature="()V"', '');
    assert.throws(() => validateReport({ policy: policy(), xml: oneSided, html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /Method/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml(), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [] }), /classes inventory/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('errors="0"', 'errors="1"'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /Errors/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('start="2" end="2"', 'start="4" end="4"'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /outside/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('<Errors', '<MissingClass/><Errors'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /Errors/);
    assert.throws(() => validateReport({ policy: policy(), xml: xml().replace('<Errors errors="0" missingClasses="0"/>', '<Errors errors="0" missingClasses="0"><MissingClass/></Errors>'), html: '<html></html>', repositoryRoot: dir, sourceEntries: [sourceEntry], classEntries: [classEntry] }), /Errors/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('exclude filter is exactly the one policy-owned Class matcher', () => {
  assert.equal(validateExcludeFilter(policy(), '<?xml version="1.0"?><FindBugsFilter><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match></FindBugsFilter>'), true);
  const tracked = readFileSync(new URL('../../backend/quality/spotbugs-exclude.xml', import.meta.url), 'utf8');
  assert.equal(validateExcludeFilter(policy(), tracked), true);
  assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Package name="com.easysubway"/></Match></FindBugsFilter>'), /exact/);
  assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Class name="&unknown;"/></Match></FindBugsFilter>'), /entity|malformed/);
    assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><!-- broken -- comment --><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match></FindBugsFilter>'), /comment/);
    assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter><Match><Class name="com.easysubway.EasySubwayBackendApplication"/></Match></FindBugsFilter>garbage'), /unconsumed/);
    assert.throws(() => validateExcludeFilter(policy(), '<FindBugsFilter/><FindBugsFilter/>'), /malformed/);
});

test('workflow preserves #87 and uploads exactly four Phase-1 files before final enforcement', () => {
  const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
  assert.equal(validateWorkflow(workflow), true);
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
  assert.match(build, /assertDisjoint\('SpotBugs engine', engineArtifacts, 'SpotBugs auxiliary classpath', auxiliaryArtifacts\)/);
  assert.match(build, /assertDisjoint\('SpotBugs engine', engineArtifacts, 'SpotBugs external plugins', pluginArtifacts\)/);
  assert.match(build, /assertDisjoint\('SpotBugs auxiliary classpath', auxiliaryArtifacts, 'SpotBugs external plugins', pluginArtifacts\)/);
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
