import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { FACILITY_MODES, FACILITY_MODE_LABELS, LOAD_PLAN, VIOLATIONS } from './journey-load-contract.mjs';
import { CLEAN_PHASE_P95_LIMIT_MS, LOAD_SUSTAIN_MIN_PERCENT, SWAP_WINDOW_MIN_PERCENT, annotations, judgeRun, renderSummary } from './journey-load-verdict.mjs';

const RPS = LOAD_PLAN.requestsPerSecond;
const NO_VIOLATIONS = Object.fromEntries(Object.values(VIOLATIONS).map((violation) => [violation, 0]));
/** 엄격 창 11개: 첫 팩 창 1개와 교체 10번. 창마다 0.75초(150건)이고 마지막 교체의 창은 단계가 끝난 뒤라 0건이다. */
const HEALTHY_WINDOW_REQUESTS = [150, 150, 150, 150, 150, 150, 150, 150, 150, 150, 0];
const LATENCY = { p50: 4, p95: 9, p99: 15, max: 40 };

const phaseResult = (name, overrides = {}) => {
  const { durationSeconds } = LOAD_PLAN.phases.find((phase) => phase.name === name);
  const requests = RPS * durationSeconds;
  const base = { requests, droppedIterations: 0, lateIterations: 0, status5xx: 0, status429: 0, bundleA: requests, bundleB: 0, explicitFailures: 0, disclosedUnobserved: 0, violations: { ...NO_VIOLATIONS }, latencyMs: { ...LATENCY } };
  const third = Math.floor(requests / 3);
  const byPhase = {
    swap: { bundleA: requests / 2, bundleB: requests / 2, windowRequests: [...HEALTHY_WINDOW_REQUESTS] },
    outage: { bundleA: third, explicitFailures: third, disclosedUnobserved: third, status5xx: third },
  };
  return { ...base, ...byPhase[name], ...overrides };
};
const healthyRun = (changes = {}) => ({
  schemaVersion: 2,
  facilityMode: FACILITY_MODES.REQUIRED,
  controls: { calls: LOAD_PLAN.controls.swap.flips + 2, failures: 0 },
  phases: Object.fromEntries(LOAD_PLAN.phases.map(({ name }) => [name, phaseResult(name)])),
  ...changes,
});
const withPhase = (name, overrides) => healthyRun({ phases: { ...healthyRun().phases, [name]: phaseResult(name, overrides) } });
const failureCodes = (run) => judgeRun(run, LOAD_PLAN).failures.map(({ code }) => code);
/** 운영 기본 구성의 장애 단계: 모든 응답이 시설 상태 미적용을 밝힌 정상 응답이고 명시 실패는 없다. */
const defaultModeRun = (outage = {}) => healthyRun({
  facilityMode: FACILITY_MODES.DEFAULT,
  phases: { ...healthyRun().phases, outage: phaseResult('outage', { explicitFailures: 0, status5xx: 0, disclosedUnobserved: 1300, ...outage }) },
});

test('a healthy run passes with per-phase throughput and no findings', () => {
  const verdict = judgeRun(healthyRun(), LOAD_PLAN);
  assert.equal(verdict.status, 'PASS');
  assert.deepEqual(verdict.failures, []);
  assert.deepEqual(verdict.warnings, []);
  assert.equal(verdict.phases.steady.throughputRps, RPS);
  assert.equal(verdict.phases.swap.requests, RPS * 20);
});

test('the production default passes when every outage answer discloses the missing facility status and none fails', () => {
  const verdict = judgeRun(defaultModeRun(), LOAD_PLAN);
  assert.equal(verdict.status, 'PASS');
  assert.equal(verdict.facilityMode, FACILITY_MODES.DEFAULT);
});

test('the production default fails on any outage failure and when no disclosed answer was observed', () => {
  assert.deepEqual(failureCodes(defaultModeRun({ status5xx: 1, explicitFailures: 1 })), ['SERVER_ERROR']);
  assert.deepEqual(failureCodes(defaultModeRun({ disclosedUnobserved: 0 })), ['OUTAGE_NOT_OBSERVED']);
});

test('the required configuration still needs both the explicit failure and the disclosed answer', () => {
  assert.deepEqual(failureCodes(withPhase('outage', { explicitFailures: 0, status5xx: 0 })), ['OUTAGE_NOT_OBSERVED']);
});

test('a distilled summary with an unknown facility mode is rejected', () => {
  assert.throws(() => judgeRun({ ...healthyRun(), facilityMode: 'sideways' }, LOAD_PLAN), /facility mode/);
});

test('any contract violation fails the run and names the phase and kind', () => {
  for (const violation of Object.values(VIOLATIONS)) {
    const run = withPhase('swap', { violations: { ...NO_VIOLATIONS, [violation]: 1 } });
    const { failures } = judgeRun(run, LOAD_PLAN);
    assert.equal(failures.length, 1, violation);
    assert.equal(failures[0].code, 'CONTRACT_VIOLATION');
    assert.match(failures[0].message, new RegExp(`swap.*${violation}`));
  }
});

test('server errors fail every phase, except the outage phase where only the explicit outage answer is a 5xx', () => {
  for (const name of ['steady', 'swap', 'recovered']) assert.deepEqual(failureCodes(withPhase(name, { status5xx: 1 })), ['SERVER_ERROR'], name);
  assert.deepEqual(failureCodes(withPhase('outage', { status5xx: 7, explicitFailures: 7 })), []);
  assert.deepEqual(failureCodes(withPhase('outage', { status5xx: 8, explicitFailures: 7 })), ['SERVER_ERROR']);
});

test('rate limiting fails the run because the harness must never be throttled', () => {
  assert.deepEqual(failureCodes(withPhase('steady', { status429: 1 })), ['RATE_LIMITED']);
});

test('a phase that did not run fails', () => {
  assert.deepEqual(failureCodes(withPhase('recovered', { requests: 0, bundleA: 0 })), ['PHASE_NOT_RUN']);
  assert.deepEqual(failureCodes(withPhase('swap', { lateIterations: 1 })), ['LOAD_NOT_SUSTAINED'], 'an iteration that started after its window was not judged, so the load was not sustained');
});

test('the tolerance is 99% of each phase plan, for arrived requests and for dropped iterations alike', () => {
  assert.equal(LOAD_SUSTAIN_MIN_PERCENT, 99);
  assert.deepEqual(failureCodes(withPhase('steady', { requests: 3960, bundleA: 3960, droppedIterations: 40 })), []);
  assert.deepEqual(failureCodes(withPhase('steady', { requests: 3959, bundleA: 3959 })), ['LOAD_SHORTFALL']);
  assert.deepEqual(failureCodes(withPhase('steady', { droppedIterations: 41 })), ['LOAD_NOT_SUSTAINED']);
  assert.deepEqual(failureCodes(withPhase('outage', { droppedIterations: 20 })), []);
  assert.deepEqual(failureCodes(withPhase('outage', { droppedIterations: 21 })), ['LOAD_NOT_SUSTAINED']);
});

test('the hosted-runner figures of the first measurement pass, with the dropped iterations reported per phase', () => {
  const hosted = healthyRun({ phases: { ...healthyRun().phases,
    steady: phaseResult('steady', { requests: 3989, bundleA: 3989, droppedIterations: 11 }),
    swap: phaseResult('swap', { requests: 3995, bundleA: 1995, bundleB: 2000, droppedIterations: 5 }),
    outage: phaseResult('outage', { requests: 1997, bundleA: 665, explicitFailures: 665, disclosedUnobserved: 665, status5xx: 665, droppedIterations: 3 }) } });
  const verdict = judgeRun(hosted, LOAD_PLAN);
  assert.deepEqual(verdict.failures, []);
  assert.match(renderSummary(verdict, null), /\| steady \| 3989 \| 11 \(≤40\) \|/);
});

test('one phase cannot absorb the tolerance of the others: a run-wide total within 1% still fails when a single phase exceeds its share', () => {
  const run = withPhase('outage', { droppedIterations: 100 });
  assert.deepEqual(failureCodes(run), ['LOAD_NOT_SUSTAINED']);
  assert.match(judgeRun(run, LOAD_PLAN).failures[0].message, /outage/);
});

test('a shortfall concentrated in the strict swap windows fails even when the phase total stays inside the tolerance', () => {
  const concentrated = [...HEALTHY_WINDOW_REQUESTS];
  concentrated[4] = 0;
  const requests = 3980;
  const run = withPhase('swap', { requests, bundleA: 1990, bundleB: 1990, windowRequests: concentrated, droppedIterations: 20 });
  assert.deepEqual(failureCodes(run), ['SWAP_WINDOW_STARVED']);
  assert.match(judgeRun(run, LOAD_PLAN).failures[0].message, /창 5/);
});

test('each strict swap window needs 90% of its 150 planned requests: 135 passes and 134 fails', () => {
  assert.equal(SWAP_WINDOW_MIN_PERCENT, 90);
  const windows = (value) => HEALTHY_WINDOW_REQUESTS.map((count, index) => (index === 3 ? value : count));
  assert.deepEqual(failureCodes(withPhase('swap', { windowRequests: windows(135) })), []);
  assert.deepEqual(failureCodes(withPhase('swap', { windowRequests: windows(134) })), ['SWAP_WINDOW_STARVED']);
});

test('a tolerated shortfall never excuses a violation in the iterations that did arrive', () => {
  const run = withPhase('swap', { requests: 3960, bundleA: 1980, bundleB: 1980, droppedIterations: 40, violations: { ...NO_VIOLATIONS, WRONG_BUNDLE: 1 } });
  assert.deepEqual(failureCodes(run), ['CONTRACT_VIOLATION']);
});

test('control failures and a wrong control call count fail the run', () => {
  assert.deepEqual(failureCodes(healthyRun({ controls: { calls: LOAD_PLAN.controls.swap.flips + 2, failures: 1 } })), ['CONTROL_FAILED']);
  assert.deepEqual(failureCodes(healthyRun({ controls: { calls: 3, failures: 0 } })), ['CONTROL_FAILED']);
});

test('a swap run that never served both bundles proves nothing and fails', () => {
  assert.deepEqual(failureCodes(withPhase('swap', { bundleA: RPS * 20, bundleB: 0 })), ['SWAP_NOT_OBSERVED']);
  assert.deepEqual(failureCodes(withPhase('swap', { bundleA: 0, bundleB: RPS * 20 })), ['SWAP_NOT_OBSERVED']);
});

test('an outage run that never produced the explicit failure or the disclosed answer proves nothing and fails', () => {
  assert.deepEqual(failureCodes(withPhase('outage', { explicitFailures: 0, status5xx: 0 })), ['OUTAGE_NOT_OBSERVED']);
  assert.deepEqual(failureCodes(withPhase('outage', { disclosedUnobserved: 0 })), ['OUTAGE_NOT_OBSERVED']);
});

test('a clean phase over the p95 limit only warns; the swap and outage phases are informational', () => {
  const slow = { ...LATENCY, p95: CLEAN_PHASE_P95_LIMIT_MS + 1 };
  for (const name of ['steady', 'recovered']) {
    const verdict = judgeRun(withPhase(name, { latencyMs: slow }), LOAD_PLAN);
    assert.equal(verdict.status, 'PASS');
    assert.deepEqual(verdict.warnings.map(({ code }) => code), ['LATENCY_LIMIT']);
    assert.match(verdict.warnings[0].message, new RegExp(name));
  }
  for (const name of ['swap', 'outage']) assert.deepEqual(judgeRun(withPhase(name, { latencyMs: slow }), LOAD_PLAN).warnings, []);
  assert.deepEqual(judgeRun(withPhase('steady', { latencyMs: { ...LATENCY, p95: CLEAN_PHASE_P95_LIMIT_MS } }), LOAD_PLAN).warnings, [], 'the limit itself is allowed');
});

test('findings keep a failing run failing even when latency also warns', () => {
  const run = withPhase('steady', { status5xx: 1, latencyMs: { ...LATENCY, p95: CLEAN_PHASE_P95_LIMIT_MS + 1 } });
  const verdict = judgeRun(run, LOAD_PLAN);
  assert.equal(verdict.status, 'FAIL');
  assert.equal(verdict.warnings.length, 1);
});

test('the summary table renders every phase and each finding', () => {
  const verdict = judgeRun(withPhase('swap', { violations: { ...NO_VIOLATIONS, MIXED_RESPONSE: 2 } }), LOAD_PLAN);
  const markdown = renderSummary(verdict, { commitSha: 'a'.repeat(40), runUrl: 'https://github.com/AquilaXk/easysubway-backend/actions/runs/1', rps: RPS });
  assert.match(markdown, /^## 경로 검색 부하·장애 시나리오/m);
  assert.ok(markdown.includes(FACILITY_MODE_LABELS[FACILITY_MODES.REQUIRED]), 'the heading names the configuration');
  for (const { name } of LOAD_PLAN.phases) assert.match(markdown, new RegExp(`\\| ${name} \\|`));
  assert.match(markdown, /FAIL/);
  assert.match(markdown, /MIXED_RESPONSE/);
});

test('annotations are one line each: errors for failures, warnings for latency', () => {
  const run = withPhase('steady', { status5xx: 1, latencyMs: { ...LATENCY, p95: CLEAN_PHASE_P95_LIMIT_MS + 1 } });
  const lines = annotations(judgeRun(run, LOAD_PLAN)).trimEnd().split('\n');
  assert.equal(lines.length, 2);
  assert.match(lines[0], /^::error title=경로 검색 부하·장애 시나리오::\[required\] /);
  assert.match(lines[1], /^::warning title=경로 검색 부하·장애 시나리오::/);
});

const withDirectory = (run) => {
  const directory = mkdtempSync(join(tmpdir(), 'journey-load-verdict-'));
  try { return run(directory); } finally { rmSync(directory, { recursive: true, force: true }); }
};
const tool = new URL('./journey-load-verdict.mjs', import.meta.url).pathname;
const cli = (run, directory) => {
  const input = join(directory, 'distilled.json');
  writeFileSync(input, JSON.stringify(run));
  const result = spawnSync(process.execPath, [tool, '--distilled', input, '--output', join(directory, 'verdict.json'), '--summary', join(directory, 'summary.md')], { encoding: 'utf8', env: { PATH: process.env.PATH, GITHUB_SHA: 'a'.repeat(40), GITHUB_SERVER_URL: 'https://github.com', GITHUB_REPOSITORY: 'AquilaXk/easysubway-backend', GITHUB_RUN_ID: '9' } });
  return { result, verdict: JSON.parse(readFileSync(join(directory, 'verdict.json'), 'utf8')), summary: readFileSync(join(directory, 'summary.md'), 'utf8') };
};

test('the CLI exits 0 for a passing run with a latency warning and writes the verdict and summary', () => withDirectory((directory) => {
  const slow = withPhase('steady', { latencyMs: { ...LATENCY, p95: CLEAN_PHASE_P95_LIMIT_MS + 1 } });
  const { result, verdict, summary } = cli(slow, directory);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /^::warning /);
  assert.equal(verdict.status, 'PASS');
  assert.match(summary, /LATENCY_LIMIT/);
}));

test('the CLI exits 1 for a contract violation and prints the error annotation', () => withDirectory((directory) => {
  const { result, verdict } = cli(withPhase('outage', { violations: { ...NO_VIOLATIONS, MASKED_OUTAGE: 3 } }), directory);
  assert.equal(result.status, 1);
  assert.match(result.stdout, /^::error /);
  assert.equal(verdict.status, 'FAIL');
}));

test('the CLI rejects a distilled summary of the wrong shape instead of passing it', () => withDirectory((directory) => {
  const input = join(directory, 'distilled.json');
  writeFileSync(input, JSON.stringify({ schemaVersion: 2 }));
  const result = spawnSync(process.execPath, [tool, '--distilled', input, '--output', join(directory, 'v.json'), '--summary', join(directory, 's.md')], { encoding: 'utf8' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /distilled summary/);
}));
