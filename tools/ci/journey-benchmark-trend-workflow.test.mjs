import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { ARTIFACT_PREFIX, BASELINE_CANDIDATE_LIMIT, BASELINE_RUN_LIMIT } from './journey-benchmark-previous.mjs';
import { validateMeasurement } from './journey-benchmark-result.mjs';
import { stepNames as stepNamesOf, step, withoutComments } from './workflow-structure.mjs';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const workflow = read('../../.github/workflows/journey-benchmark-trend.yml');
const ci = read('../../.github/workflows/ci.yml');
const initScript = read('./gradle/journey-benchmark.init.gradle');
const gateSource = read('../../backend/src/test/java/com/easysubway/route/application/service/JourneyEnginePerformanceGateTest.java');

const ciBackendTest = /^  backend-test:\n[\s\S]*?(?=^  backend:\n)/m.exec(ci)[0];
const stepNames = stepNamesOf(workflow);
const SETUP_STEPS = ['Set up Node.js', 'Set up ORAS', 'Set up Java', 'Stage pinned contracts', 'Stage pinned Journey contracts'];

test('Backend #516 runs on main push, nightly schedule and manual dispatch only', () => {
  const triggers = /^on:\n([\s\S]*?)(?=^[a-z])/m.exec(withoutComments(workflow))[1];
  assert.match(triggers, /^  push:\n    branches:\n      - main\n/m);
  assert.match(triggers, /^  schedule:\n    - cron: "\d{1,2} \d{1,2} \* \* \*"\n/m);
  assert.equal((triggers.match(/cron:/g) || []).length, 1, 'exactly one nightly schedule');
  assert.match(triggers, /^  workflow_dispatch:\n/m);
  assert.deepEqual([...triggers.matchAll(/^  ([a-z_]+):/gm)].map(([, name]) => name), ['push', 'schedule', 'workflow_dispatch']);
});

test('Backend #516 uses a GitHub-hosted runner and never the production self-hosted runner', () => {
  const code = withoutComments(workflow);
  assert.equal((code.match(/^\s+runs-on: ubuntu-latest$/gm) || []).length, 1);
  assert.doesNotMatch(code, /self-hosted|easysubway-production/);
  const timeout = Number(/^    timeout-minutes: (\d+)$/m.exec(code)?.[1]);
  assert.ok(timeout > 0 && timeout <= 30, 'the job needs a bounded timeout');
});

test('Backend #516 is read-only: no secrets, no repository writes, results leave only as an artifact', () => {
  const code = withoutComments(workflow);
  assert.match(code, /^permissions:\n  contents: read\n/m);
  assert.match(code, /^    permissions:\n      contents: read\n      actions: read\n/m);
  assert.doesNotMatch(code, /: write\b/);
  assert.doesNotMatch(code, /\$\{\{\s*secrets\./);
  assert.doesNotMatch(code, /git (?:add|commit|push)|gh (?:pr|issue|api)\b|pull_request/);
  assert.match(code, /persist-credentials: false/);
});

test('Backend #516 is observational: regressions never fail the job, the measurement does', () => {
  assert.doesNotMatch(withoutComments(workflow), /continue-on-error|\|\| true|\|\| :/);
  const compare = step(workflow, 'Compare with the previous successful runs');
  assert.match(compare, /set -euo pipefail/);
  assert.match(compare, /node tools\/ci\/journey-benchmark-trend\.mjs compare --current "\$\{RUNNER_TEMP\}\/journey-benchmark\/result\.json" --previous-manifest "\$\{RUNNER_TEMP\}\/previous-benchmark\/manifest\.json" --summary "\$GITHUB_STEP_SUMMARY"/);
});

test('Backend #516 fetches the previous runs through the tested script, authenticated with the workflow token only', () => {
  const fetch = step(workflow, 'Fetch previous successful runs');
  assert.match(fetch, /GH_TOKEN: \$\{\{ github\.token \}\}/);
  assert.match(fetch, /WORKFLOW_NAME: \$\{\{ github\.workflow \}\}/);
  assert.match(fetch, /node tools\/ci\/journey-benchmark-trend\.mjs fetch-previous --repo "\$\{GITHUB_REPOSITORY\}" --workflow "\$\{WORKFLOW_NAME\}" --dir "\$\{RUNNER_TEMP\}\/previous-benchmark" --output "\$\{RUNNER_TEMP\}\/previous-benchmark\/manifest\.json"/);
  assert.ok(BASELINE_CANDIDATE_LIMIT > BASELINE_RUN_LIMIT, 'skipped runs must be replaceable from further candidates');
});

test('Backend #516 serializes runs without cancelling a measurement in progress', () => {
  assert.match(workflow, /^concurrency:\n  group: journey-engine-benchmark-trend\n  cancel-in-progress: false\n/m);
});

test('Backend #516 steps run in order from setup to measurement, aggregation, upload and comparison', () => {
  assert.deepEqual(stepNames, ['Checkout', ...SETUP_STEPS, 'Measure the engine benchmark', 'Aggregate the benchmark repeats', 'Upload the benchmark result', 'Fetch previous successful runs', 'Compare with the previous successful runs']);
});

test('Backend #516 setup steps are byte-identical to the Backend CI test shard setup so the pins cannot drift', () => {
  const trim = (text) => text.replace(/\n+$/, '').replace(/(?:\n      # [^\n]*)+$/, '').replace(/\n+$/, '');
  for (const name of SETUP_STEPS) assert.equal(trim(step(workflow, name)), trim(step(ciBackendTest, name)), name);
  assert.equal(trim(step(workflow, 'Checkout')), trim(step(ciBackendTest, 'Checkout')));
  for (const [, reference] of workflow.matchAll(/^\s+uses: ([^\s]+)$/gm)) assert.match(reference, /@[0-9a-f]{40}$/, `${reference} must be pinned to a commit sha`);
});

test('Backend #516 measures through the init script task with the repeat count from one named value', () => {
  assert.match(workflow, /^    env:\n      BENCHMARK_REPEATS: "3"\n/m);
  const measure = step(workflow, 'Measure the engine benchmark');
  assert.match(measure, /working-directory: backend/);
  assert.match(measure, /seq 1 "\$\{BENCHMARK_REPEATS\}"/);
  assert.match(measure, /\.\/gradlew --init-script \.\.\/tools\/ci\/gradle\/journey-benchmark\.init\.gradle journeyEngineBenchmark -PjourneyBenchmarkOutput="\$\{RUNNER_TEMP\}\/journey-benchmark\/measured-\$\{repeat\}\.json" --no-daemon/);
  assert.match(measure, /EASYSUBWAY_CONTRACTS_BUNDLE: \$\{\{ runner\.temp \}\}\/backend-contracts\.json/);
  const aggregate = step(workflow, 'Aggregate the benchmark repeats');
  assert.match(aggregate, /node tools\/ci\/journey-benchmark-trend\.mjs aggregate /);
  assert.match(aggregate, /--output "\$\{RUNNER_TEMP\}\/journey-benchmark\/result\.json"/);
});

test('Backend #516 uploads the result with a bounded retention under the name prefix the previous-run lookup searches for', () => {
  const upload = step(workflow, 'Upload the benchmark result');
  assert.match(upload, /uses: actions\/upload-artifact@bbbca2ddaa5d8feaa63e36b76fdaad77386f024f/);
  assert.ok(upload.includes(`name: ${ARTIFACT_PREFIX}\${{ github.sha }}`), 'the artifact name must start with ARTIFACT_PREFIX');
  assert.match(upload, /path: \$\{\{ runner\.temp \}\}\/journey-benchmark\//);
  assert.match(upload, /retention-days: 30/);
  assert.match(upload, /if-no-files-found: error/);
});

test('Backend #516 init script runs the gate measurement entry point with the same JVM options as the gate fork', () => {
  const className = /class (\w+)/.exec(gateSource.slice(gateSource.indexOf('@DisplayName("#460')))[1];
  assert.match(initScript, new RegExp(`mainClass = 'com\\.easysubway\\.route\\.application\\.service\\.${className}'`));
  assert.match(gateSource, /public static void main\(String\[\] args\)/);
  const forkOptions = /new ProcessBuilder\(launcher, ([\s\S]+?),\s*"-cp"/.exec(gateSource)[1].split(', ').map((option) => option.replaceAll('"', ''));
  const initOptions = /jvmArgs\(([^)]+)\)/.exec(initScript)[1].split(', ').map((option) => option.replaceAll("'", ''));
  assert.deepEqual(initOptions, forkOptions);
  assert.doesNotMatch(withoutComments(initScript.replaceAll('//', '#')), /jacoco/i);
});

test('Backend #518 committed gate baseline passes the trend measurement validator, so a measurement method change cannot break the trend job', () => {
  const baseline = JSON.parse(read('../../backend/src/test/resources/journey-benchmark/engine-performance-baseline.json'));
  assert.doesNotThrow(() => validateMeasurement(baseline));
});
