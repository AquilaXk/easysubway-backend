import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { BASELINE_CANDIDATE_LIMIT, BASELINE_RUN_LIMIT } from './journey-benchmark-previous.mjs';
import { stepNames, step, steps, stepUses, withoutComments } from './workflow-structure.mjs';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const composite = read('../../.github/actions/backend-setup/action.yml');
const COMPOSITE_PATH = './.github/actions/backend-setup';
const SETUP_STEPS_PER_WORKFLOW = { ci: 3, 'journey-benchmark-trend': 1, 'journey-load': 1, 'journey-mutation-trend': 1 };
const SETUP_STEP = 'Set up backend build environment';
const TREND_WORKFLOWS = ['journey-benchmark-trend', 'journey-load', 'journey-mutation-trend'];
const BACKEND_WORKFLOWS = ['ci', ...TREND_WORKFLOWS];
const workflowOf = (name) => read(`../../.github/workflows/${name}.yml`);
// 저장소 설정의 artifact 최대 보존 기간. 더 길게 요청하면 실행마다 "Retention days cannot be greater than ..." 경고가 난다.
const REPO_MAX_RETENTION_DAYS = 14;
// 매일 1회 실행(main push 실행은 더 촘촘하므로 최악 기준)에서 후보 run 하나가 차지하는 일수.
const DAYS_PER_NIGHTLY_RUN = 1;

test('the composite holds the shared setup steps in order, every external action pinned to a commit sha', () => {
  assert.deepEqual(stepNames(composite.replace(/^ {4}- name:/gm, '      - name:')), ['Set up Node.js', 'Set up ORAS', 'Set up Java', 'Stage pinned contracts', 'Stage pinned Journey contracts']);
  assert.match(composite, /^runs:\n {2}using: composite\n/m);
  const references = [...composite.matchAll(/^\s+uses: ([^\s]+)$/gm)].map(([, reference]) => reference);
  assert.equal(references.length, 3);
  for (const reference of references) assert.match(reference, /@[0-9a-f]{40}$/, `${reference} must be pinned to a commit sha`);
  assert.match(composite, /java-version: "21\.0\.11"/);
});

test('the composite takes the Gradle cache and Node.js setup as explicit inputs with the caching defaults', () => {
  assert.match(composite, /^inputs:\n {2}setup-node:\n(?: {4}[^\n]*\n)*? {4}default: "true"\n/m);
  assert.match(composite, /^ {2}gradle-cache:\n(?: {4}[^\n]*\n)*? {4}default: "true"\n/m);
  assert.match(composite, /cache: \$\{\{ inputs\.gradle-cache == 'true' && 'gradle' \|\| '' \}\}/);
  assert.match(composite, /if: \$\{\{ inputs\.setup-node == 'true' \}\}/);
});

test('every backend workflow reaches setup through the composite and keeps no inline copy of its steps', () => {
  for (const name of BACKEND_WORKFLOWS) {
    const workflow = workflowOf(name);
    const code = withoutComments(workflow);
    const setups = steps(workflow).filter((candidate) => candidate.name === SETUP_STEP);
    assert.equal(setups.length, SETUP_STEPS_PER_WORKFLOW[name], `${name} setup step count`);
    for (const setup of setups) assert.equal(stepUses(setup.text), COMPOSITE_PATH, `${name} must use exactly the backend-setup composite`);
    assert.doesNotMatch(code, /actions\/setup-java|oras-project\/setup-oras|name: Stage pinned/, `${name} must not repeat the composite steps`);
  }
});

test('mutation trend never caches Gradle, so a nightly run cannot feed the required CI cache', () => {
  const mutation = workflowOf('journey-mutation-trend');
  assert.ok(step(mutation, SETUP_STEP).includes(`uses: ${COMPOSITE_PATH}\n        with:\n          gradle-cache: "false"\n`));
  assert.doesNotMatch(withoutComments(mutation), /actions\/cache|gradle-build-action|setup-gradle|cache: gradle/);
  for (const name of ['journey-benchmark-trend', 'journey-load']) assert.doesNotMatch(step(workflowOf(name), SETUP_STEP), /gradle-cache/, `${name} keeps the default cache`);
  assert.doesNotMatch(workflowOf('ci'), /gradle-cache/);
});

test('the trend workflows request no longer artifact retention than the repository allows', () => {
  for (const name of TREND_WORKFLOWS) {
    const retentions = [...workflowOf(name).matchAll(/^\s+retention-days: (\d+)$/gm)].map(([, days]) => Number(days));
    assert.ok(retentions.length > 0, name);
    for (const days of retentions) assert.ok(days <= REPO_MAX_RETENTION_DAYS, `${name} requests ${days} days`);
  }
});

test('the previous-run comparison window fits the retention even for the least frequent trigger', () => {
  const windowDays = BASELINE_CANDIDATE_LIMIT * DAYS_PER_NIGHTLY_RUN;
  assert.ok(windowDays <= REPO_MAX_RETENTION_DAYS, `${BASELINE_CANDIDATE_LIMIT} candidate runs span ${windowDays} nightly days`);
  assert.ok(BASELINE_RUN_LIMIT <= BASELINE_CANDIDATE_LIMIT);
});
