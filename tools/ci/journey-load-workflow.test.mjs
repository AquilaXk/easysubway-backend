import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { K6_OUTPUT_FILES } from './journey-load-contract.mjs';
import { stepNames, step, withoutComments } from './workflow-structure.mjs';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const workflow = read('../../.github/workflows/journey-load.yml');
const ci = read('../../.github/workflows/ci.yml');
const initScript = read('./gradle/journey-load.init.gradle');
const runner = read('./journey-load-run.mjs');
const code = withoutComments(workflow);
const ciBackendTest = /^  backend-test:\n[\s\S]*?(?=^  backend:\n)/m.exec(ci)[0];
const SETUP_STEPS = ['Set up Node.js', 'Set up ORAS', 'Set up Java', 'Stage pinned contracts', 'Stage pinned Journey contracts'];

test('Backend #520 runs on a nightly schedule and manual dispatch only', () => {
  const triggers = /^on:\n([\s\S]*?)(?=^[a-z])/m.exec(code)[1];
  assert.match(triggers, /^  schedule:\n    - cron: "\d{1,2} \d{1,2} \* \* \*"\n/m);
  assert.equal((triggers.match(/cron:/g) || []).length, 1, 'exactly one nightly schedule');
  assert.match(triggers, /^  workflow_dispatch:\n/m);
  assert.deepEqual([...triggers.matchAll(/^  ([a-z_]+):/gm)].map(([, name]) => name), ['schedule', 'workflow_dispatch']);
});

test('Backend #520 uses a GitHub-hosted runner and never the production self-hosted runner', () => {
  assert.equal((code.match(/^\s+runs-on: ubuntu-latest$/gm) || []).length, 1);
  assert.doesNotMatch(code, /self-hosted|easysubway-production/);
  const timeout = Number(/^    timeout-minutes: (\d+)$/m.exec(code)?.[1]);
  assert.ok(timeout > 0 && timeout <= 30, 'the job needs a bounded timeout');
});

test('Backend #520 is read-only: no secrets, no repository writes, and no production address', () => {
  assert.match(code, /^permissions:\n  contents: read\n/m);
  assert.match(code, /^    permissions:\n      contents: read\n/m);
  assert.doesNotMatch(code, /: write\b/);
  assert.doesNotMatch(code, /\$\{\{\s*secrets\./);
  assert.doesNotMatch(code, /git (?:add|commit|push)|gh (?:pr|issue|api)\b|pull_request/);
  assert.doesNotMatch(code, /aquilaxk\.site|--base-url/i, 'the load target is the local server the runner starts');
  assert.match(code, /persist-credentials: false/);
});

test('Backend #520 is observational: a failing run is never hidden and a nightly run never queues behind itself', () => {
  assert.doesNotMatch(code, /continue-on-error|\|\| true|\|\| :/);
  assert.match(workflow, /^concurrency:\n  group: journey-load-scenarios\n  cancel-in-progress: false\n/m);
});

test('Backend #520 steps run in order from setup to k6 installation, the run and the upload', () => {
  assert.deepEqual(stepNames(workflow), ['Checkout', ...SETUP_STEPS, 'Set up k6', 'Run the load scenarios', 'Upload the load result']);
});

test('Backend #520 setup steps are byte-identical to the Backend CI test shard setup so the pins cannot drift', () => {
  const trim = (text) => text.replace(/\n+$/, '').replace(/(?:\n      # [^\n]*)+$/, '').replace(/\n+$/, '');
  for (const name of ['Checkout', ...SETUP_STEPS]) assert.equal(trim(step(workflow, name)), trim(step(ciBackendTest, name)), name);
  for (const [, reference] of workflow.matchAll(/^\s+uses: ([^\s]+)$/gm)) assert.match(reference, /@[0-9a-f]{40}$/, `${reference} must be pinned to a commit sha`);
});

test('Backend #520 installs a pinned k6 through the pinned setup action', () => {
  const install = step(workflow, 'Set up k6');
  assert.match(install, /uses: grafana\/setup-k6-action@[0-9a-f]{40}\n/);
  assert.match(install, /k6-version: "\d+\.\d+\.\d+"/);
});

test('Backend #520 runs the scenarios through the tested runner with the staged contracts bundle', () => {
  const run = step(workflow, 'Run the load scenarios');
  assert.match(run, /node tools\/ci\/journey-load-run\.mjs --output-dir "\$\{RUNNER_TEMP\}\/journey-load"/);
  assert.match(run, /EASYSUBWAY_CONTRACTS_BUNDLE: \$\{\{ runner\.temp \}\}\/backend-contracts\.json/);
  assert.doesNotMatch(run, /--backend-dir|--base-url/);
});

test('Backend #520 uploads the result even when the run failed, with a bounded retention', () => {
  const upload = step(workflow, 'Upload the load result');
  assert.match(upload, /if: \$\{\{ !cancelled\(\) \}\}/);
  assert.match(upload, /uses: actions\/upload-artifact@bbbca2ddaa5d8feaa63e36b76fdaad77386f024f/);
  assert.match(upload, /name: journey-load-\$\{\{ github\.sha \}\}/);
  assert.match(upload, /path: \$\{\{ runner\.temp \}\}\/journey-load\//);
  assert.match(upload, /retention-days: 30/);
  assert.match(upload, /if-no-files-found: error/);
});

test('Backend #520 init script exposes the runtime the runner reads and the runner reads the files k6 writes', () => {
  assert.match(initScript, /project\.tasks\.register\('journeyLoadRuntime'\)/);
  assert.match(initScript, /journeyLoadRuntimeOutput/);
  assert.match(runner, /journeyLoadRuntime/);
  assert.match(runner, /-PjourneyLoadRuntimeOutput=/);
  assert.match(runner, /\.\.\/tools\/ci\/gradle\/journey-load\.init\.gradle/);
  assert.match(runner, /\.\.\.K6_OUTPUT_FILES/);
  assert.deepEqual(Object.values(K6_OUTPUT_FILES), ['k6-distilled.json', 'k6-raw.json']);
});
