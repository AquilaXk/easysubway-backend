import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import test from 'node:test';
import { BASELINE_CANDIDATE_LIMIT, BASELINE_RUN_LIMIT } from './journey-benchmark-previous.mjs';
import { ARTIFACT_PREFIX, REPORT_FILE_NAME, RUN_FILE_NAME } from './journey-mutation-trend.mjs';
import { sameStep, stepNames as stepNamesOf, step, withoutComments } from './workflow-structure.mjs';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const workflow = read('../../.github/workflows/journey-mutation-trend.yml');
const ci = read('../../.github/workflows/ci.yml');
const initScript = read('./gradle/journey-mutation.init.gradle');
const buildScript = read('../../backend/build.gradle');
const lockfile = read('../../backend/gradle.lockfile');
const dependabot = read('../../.github/dependabot.yml');

const ciBackendTest = /^  backend-test:\n[\s\S]*?(?=^  backend:\n)/m.exec(ci)[0];
const SETUP_STEPS = ['Checkout', 'Set up Node.js', 'Set up ORAS', 'Set up Java', 'Stage pinned contracts', 'Stage pinned Journey contracts'];
const REPORT_DIRECTORY = 'backend/build/reports/pitest';
const constant = (name) => new RegExp(`^def ${name} = (.+)$`, 'm').exec(initScript)?.[1];
const quotedList = (name) => new RegExp(`^def ${name} = \\[\\n([\\s\\S]*?)^\\]`, 'm').exec(initScript)[1].match(/'([^']+)'/g).map((item) => item.slice(1, -1));

test('Backend #523 runs on a nightly schedule and manual dispatch only, never per push or pull request', () => {
  const triggers = /^on:\n([\s\S]*?)(?=^[a-z])/m.exec(withoutComments(workflow))[1];
  assert.match(triggers, /^  schedule:\n    - cron: "\d{1,2} \d{1,2} \* \* \*"\n/m);
  assert.equal((triggers.match(/cron:/g) || []).length, 1, 'exactly one nightly schedule');
  assert.match(triggers, /^  workflow_dispatch:\n/m);
  assert.deepEqual([...triggers.matchAll(/^  ([a-z_]+):/gm)].map(([, name]) => name), ['schedule', 'workflow_dispatch']);
});

test('Backend #523 uses a GitHub-hosted runner with a bounded timeout and never the production self-hosted runner', () => {
  const code = withoutComments(workflow);
  assert.equal((code.match(/^\s+runs-on: ubuntu-latest$/gm) || []).length, 1);
  assert.doesNotMatch(code, /self-hosted|easysubway-production/);
  const timeout = Number(/^    timeout-minutes: (\d+)$/m.exec(code)?.[1]);
  assert.ok(timeout > 0 && timeout <= 30, 'the job needs a bounded timeout');
});

test('Backend #523 is read-only: no secrets, no repository writes, results leave only as an artifact', () => {
  const code = withoutComments(workflow);
  assert.match(code, /^permissions:\n  contents: read\n/m);
  assert.match(code, /^    permissions:\n      contents: read\n      actions: read\n/m);
  assert.doesNotMatch(code, /: write\b/);
  assert.doesNotMatch(code, /\$\{\{\s*secrets\./);
  assert.doesNotMatch(code, /git (?:add|commit|push)|gh (?:pr|issue|api)\b|pull_request/);
  assert.match(code, /persist-credentials: false/);
});

test('Backend #523 is observational: a score drop never fails the job, an untrustworthy measurement does', () => {
  assert.doesNotMatch(withoutComments(workflow), /continue-on-error|\|\| true|\|\| :/);
  const compare = step(workflow, 'Compare with the previous successful runs');
  assert.match(compare, /set -euo pipefail/);
  assert.match(compare, /node tools\/ci\/journey-mutation-trend\.mjs compare --current "\$\{RUNNER_TEMP\}\/journey-mutation\/result\.json" --previous-manifest "\$\{RUNNER_TEMP\}\/previous-mutation\/manifest\.json" --summary "\$GITHUB_STEP_SUMMARY"/);
});

test('Backend #523 fetches the previous runs through the tested script, authenticated with the workflow token only', () => {
  const fetch = step(workflow, 'Fetch previous successful runs');
  assert.match(fetch, /GH_TOKEN: \$\{\{ github\.token \}\}/);
  assert.match(fetch, /WORKFLOW_NAME: \$\{\{ github\.workflow \}\}/);
  assert.match(fetch, /node tools\/ci\/journey-mutation-trend\.mjs fetch-previous --repo "\$\{GITHUB_REPOSITORY\}" --workflow "\$\{WORKFLOW_NAME\}" --dir "\$\{RUNNER_TEMP\}\/previous-mutation" --output "\$\{RUNNER_TEMP\}\/previous-mutation\/manifest\.json"/);
  assert.ok(BASELINE_CANDIDATE_LIMIT > BASELINE_RUN_LIMIT, 'skipped runs must be replaceable from further candidates');
});

test('Backend #523 serializes runs without cancelling a measurement in progress', () => {
  assert.match(workflow, /^concurrency:\n  group: journey-mutation-trend\n  cancel-in-progress: false\n/m);
});

test('Backend #523 steps run in order from setup to PIT, summary, upload and comparison', () => {
  assert.deepEqual(stepNamesOf(workflow), [...SETUP_STEPS, 'Run PIT on the routing classes', 'Summarize the PIT report', 'Upload the mutation result', 'Fetch previous successful runs', 'Compare with the previous successful runs']);
});

test('Backend #523 setup steps are byte-identical to the Backend CI test shard setup so the pins cannot drift', () => {
  for (const name of SETUP_STEPS) assert.ok(sameStep(workflow, ciBackendTest, name), name);
  for (const [, reference] of workflow.matchAll(/^\s+uses: ([^\s]+)$/gm)) assert.match(reference, /@[0-9a-f]{40}$/, `${reference} must be pinned to a commit sha`);
});

test('Backend #523 runs the pitest task from the init script only, so test and check never include PIT', () => {
  const run = step(workflow, 'Run PIT on the routing classes');
  assert.match(run, /working-directory: backend/);
  assert.match(run, /EASYSUBWAY_CONTRACTS_BUNDLE: \$\{\{ runner\.temp \}\}\/backend-contracts\.json/);
  assert.match(run, /\.\/gradlew --init-script \.\.\/tools\/ci\/gradle\/journey-mutation\.init\.gradle pitest --no-daemon/);
  for (const [name, source] of [['build.gradle', buildScript], ['gradle.lockfile', lockfile], ['ci.yml', ci], ['dependabot.yml', dependabot]]) {
    assert.doesNotMatch(source, /pitest|info\.solidsoft/i, `${name} must not know PIT: it is applied by the init script only`);
  }
});

test('Backend #523 summarizes the report directory the Gradle task writes, then uploads it under the prefix the previous-run lookup searches for', () => {
  const summarize = step(workflow, 'Summarize the PIT report');
  assert.match(summarize, new RegExp(`node tools/ci/journey-mutation-trend\\.mjs summarize --report-dir ${REPORT_DIRECTORY} --output "\\$\\{RUNNER_TEMP\\}/journey-mutation/result\\.json"`));
  assert.match(summarize, /cp -R backend\/build\/reports\/pitest "\$\{RUNNER_TEMP\}\/journey-mutation\/report"/);
  const upload = step(workflow, 'Upload the mutation result');
  assert.match(upload, /uses: actions\/upload-artifact@bbbca2ddaa5d8feaa63e36b76fdaad77386f024f/);
  assert.ok(upload.includes(`name: ${ARTIFACT_PREFIX}\${{ github.sha }}`), 'the artifact name must start with ARTIFACT_PREFIX');
  assert.match(upload, /path: \$\{\{ runner\.temp \}\}\/journey-mutation\//);
  assert.match(upload, /retention-days: 30/);
  assert.match(upload, /if-no-files-found: error/);
});

test('Backend #523 init script pins the PIT tools, writes the run description the summarizer reads, and leaves the report where the workflow looks', () => {
  assert.match(initScript, /gradlePluginPortal\(\)/);
  assert.doesNotMatch(withoutComments(initScript.replaceAll('//', '#')), /mavenLocal|jcenter|http:/);
  assert.match(initScript, /classpath 'info\.solidsoft\.gradle\.pitest:gradle-pitest-plugin:\d+\.\d+\.\d+'/);
  assert.match(constant('PITEST_VERSION'), /^'\d+\.\d+\.\d+'$/);
  assert.match(constant('JUNIT5_PLUGIN_VERSION'), /^'\d+\.\d+\.\d+'$/);
  assert.match(initScript, /addJUnitPlatformLauncher = false/, 'the locked junit-platform-launcher must stay untouched');
  assert.equal(constant('RUN_DESCRIPTION_FILE'), `'${RUN_FILE_NAME}'`);
  for (const field of ['pitestVersion', 'junit5PluginVersion', 'targetClasses', 'threads', 'elapsedSeconds']) assert.match(initScript, new RegExp(`${field}:`), `run description field ${field}`);
  assert.doesNotMatch(initScript, /reportDir\s*=/, `the default report directory must stay ${REPORT_DIRECTORY}`);
  assert.equal(REPORT_FILE_NAME, 'mutations.xml');
});

test('Backend #523 every default target class pattern still names a production class, so a rename cannot drop a class silently', () => {
  const patterns = quotedList('MUTATION_TARGET_CLASSES');
  assert.ok(patterns.length >= 3 && patterns.length <= 5, 'start with a few small classes');
  for (const pattern of patterns) {
    assert.match(pattern, /^[a-z][a-z0-9.]*\.[A-Z][A-Za-z0-9]*\*$/, pattern);
    const source = `../../backend/src/main/java/${pattern.slice(0, -1).replaceAll('.', '/')}.java`;
    assert.ok(existsSync(new URL(source, import.meta.url)), `${pattern} -> ${source}`);
  }
});

test('Backend #523 excludes the timing tests from the PIT run and keeps a bounded child JVM', () => {
  const excluded = quotedList('MUTATION_EXCLUDED_TEST_CLASSES');
  assert.ok(excluded.includes('*PerformanceGateTest') && excluded.includes('*PerfTest'));
  assert.match(constant('MUTATION_CHILD_JVM_HEAP'), /^'-Xmx\d+[mg]'$/);
});
