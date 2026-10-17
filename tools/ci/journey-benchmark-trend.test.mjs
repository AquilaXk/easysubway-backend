import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  ALLOCATION_REGRESSION_RATIO,
  WALL_CLOCK_REGRESSION_RATIO,
  aggregateRepeats,
  buildResult,
  compareResults,
  renderSummary,
} from './journey-benchmark-trend.mjs';

const tool = new URL('./journey-benchmark-trend.mjs', import.meta.url).pathname;
const SHA = 'a'.repeat(40);
const BUNDLE = 'b'.repeat(64);
const QUERIES = 'c'.repeat(64);

const withDirectory = (run) => {
  const directory = mkdtempSync(join(tmpdir(), 'journey-benchmark-trend-'));
  try { return run(directory); } finally { rmSync(directory, { recursive: true, force: true }); }
};

// 게이트(JourneyEnginePerformanceGateTest.measure)가 쓰는 JSON 모양의 최소 사본.
const measurement = ({ calibration = 100_000_000, p50 = 1_000, p99 = 4_000, query = 10_000, compile = 50_000_000, compileBytes = 20_000_000, bundle = BUNDLE, work = 7 } = {}) => ({
  schemaVersion: 1,
  method: { warmupRounds: 2, measuredRounds: 5, java: 'Eclipse Adoptium 21.0.11', arch: 'amd64', processors: 4 },
  calibrationNanos: calibration,
  workloads: {
    'metro-grid-v1': {
      bundle: { sha256: bundle, stations: 192, trips: 7298, stopTimes: 110384, transferRules: 128 },
      querySet: { id: 'metro-grid-queries-v1', count: 160, sha256: QUERIES },
      compile: { nanos: compile, allocatedBytes: compileBytes, retainedBytes: 3_000_000 },
      modes: { DEPART_AT: { queries: 160, p50Nanos: p50, p99Nanos: p99, samplesPerRound: 160, allocatedBytesPerQuery: query, work: { expandedRoutes: work } } },
      candidatePolicyReplay: { source: 'platform@sha', modes: { ARRIVE_BY: { queries: 12, failClosed: 0 } } },
    },
  },
});
const context = (overrides = {}) => ({
  commitSha: SHA, ref: 'main', event: 'push', runId: '101', runAttempt: '1', runUrl: 'https://github.com/AquilaXk/easysubway-backend/actions/runs/101',
  measuredAt: '2026-10-17T00:00:00.000Z',
  runner: { name: 'GitHub Actions 7', os: 'Linux', arch: 'X64', image: 'ubuntu24', imageVersion: '20260101.1', cpuModel: 'AMD EPYC 7763', cpuCount: 4, totalMemoryBytes: 16_777_216_000 },
  ...overrides,
});
const result = (m, overrides) => buildResult({ measurement: m, repeats: 3, context: context(overrides) });

test('aggregation takes the per-field median of an odd number of repeats and keeps the deterministic fields', () => {
  const merged = aggregateRepeats([
    measurement({ calibration: 100, p50: 900, p99: 9_000, query: 10_100, compile: 60, compileBytes: 20_000_300 }),
    measurement({ calibration: 300, p50: 1_100, p99: 4_000, query: 10_000, compile: 50, compileBytes: 20_000_100 }),
    measurement({ calibration: 200, p50: 1_000, p99: 5_000, query: 10_200, compile: 40, compileBytes: 20_000_200 }),
  ]);
  const workload = merged.workloads['metro-grid-v1'];
  assert.equal(merged.calibrationNanos, 200);
  assert.equal(workload.modes.DEPART_AT.p50Nanos, 1_000);
  assert.equal(workload.modes.DEPART_AT.p99Nanos, 5_000);
  assert.equal(workload.modes.DEPART_AT.allocatedBytesPerQuery, 10_100);
  assert.equal(workload.compile.nanos, 50);
  assert.equal(workload.compile.allocatedBytes, 20_000_200);
  assert.deepEqual(workload.modes.DEPART_AT.work, { expandedRoutes: 7 });
  assert.equal(workload.bundle.sha256, BUNDLE);
  assert.equal(workload.querySet.sha256, QUERIES);
});

test('aggregation fails closed on an even repeat count, no repeats, malformed measurements, and drift in deterministic fields', () => {
  assert.throws(() => aggregateRepeats([]), /at least one/);
  assert.throws(() => aggregateRepeats([measurement(), measurement()]), /odd number/);
  assert.throws(() => aggregateRepeats([measurement(), measurement(), measurement({ bundle: 'd'.repeat(64) })]), /changed between repeats/);
  assert.throws(() => aggregateRepeats([measurement(), measurement(), measurement({ work: 8 })]), /changed between repeats/);
  const missingField = measurement();
  delete missingField.workloads['metro-grid-v1'].modes.DEPART_AT.p99Nanos;
  assert.throws(() => aggregateRepeats([missingField]), /p99Nanos/);
  const zero = measurement({ p50: 0 });
  assert.throws(() => aggregateRepeats([zero]), /p50Nanos/);
  const noWorkloads = { ...measurement(), workloads: {} };
  assert.throws(() => aggregateRepeats([noWorkloads]), /workloads/);
  assert.throws(() => aggregateRepeats([{ ...measurement(), schemaVersion: 2 }]), /schemaVersion/);
});

test('the result records commit, runner, repeat count and the measurement without the policy replay detail', () => {
  const built = result(aggregateRepeats([measurement()]));
  assert.equal(built.schemaVersion, 1);
  assert.equal(built.repeats, 3);
  assert.equal(built.context.commitSha, SHA);
  assert.equal(built.context.runner.cpuModel, 'AMD EPYC 7763');
  assert.equal(built.measurement.workloads['metro-grid-v1'].candidatePolicyReplay, undefined);
  assert.throws(() => buildResult({ measurement: measurement(), repeats: 3, context: context({ commitSha: 'abc' }) }), /commitSha/);
  assert.throws(() => buildResult({ measurement: measurement(), repeats: 2, context: context() }), /repeats/);
});

test('regression thresholds are the named constants and fire at the threshold, not below it', () => {
  assert.equal(WALL_CLOCK_REGRESSION_RATIO, 1.3);
  assert.equal(ALLOCATION_REGRESSION_RATIO, 1.3);
  const previous = result(measurement({ p99: 10_000 }));
  const below = compareResults({ current: result(measurement({ p99: 12_900 })), previous });
  assert.deepEqual(below.regressions, []);
  const at = compareResults({ current: result(measurement({ p99: 13_000 })), previous });
  assert.equal(at.regressions.length, 1);
  assert.match(at.regressions[0].message, /metro-grid-v1 DEPART_AT p99/);
  assert.match(at.regressions[0].message, /1\.30/);
});

test('wall-clock comparison is normalized by the calibration work so a slower runner alone is not a regression', () => {
  const previous = result(measurement({ calibration: 100_000_000, p50: 1_000, p99: 4_000, compile: 50_000_000 }));
  const slowerRunner = result(measurement({ calibration: 200_000_000, p50: 2_000, p99: 8_000, compile: 100_000_000 }));
  assert.deepEqual(compareResults({ current: slowerRunner, previous }).regressions, []);
  const slowerEngine = result(measurement({ calibration: 100_000_000, p50: 2_000, p99: 8_000, compile: 100_000_000 }));
  const labels = compareResults({ current: slowerEngine, previous }).regressions.map(({ message }) => message);
  assert.equal(labels.length, 3);
  assert.ok(labels.some((message) => message.includes('p50')));
  assert.ok(labels.some((message) => message.includes('컴파일 시간')));
});

test('allocation is compared raw and flagged separately from latency', () => {
  const previous = result(measurement({ query: 10_000 }));
  const grown = compareResults({ current: result(measurement({ query: 13_000 })), previous });
  assert.equal(grown.regressions.length, 1);
  assert.match(grown.regressions[0].message, /질의당 할당/);
  assert.deepEqual(compareResults({ current: result(measurement({ query: 5_000 })), previous }).regressions, []);
});

test('a workload whose bundle or query set changed is excluded from comparison instead of reported as a regression', () => {
  const previous = result(measurement());
  const changed = compareResults({ current: result(measurement({ bundle: 'd'.repeat(64), p99: 400_000 })), previous });
  assert.deepEqual(changed.regressions, []);
  assert.equal(changed.workloads[0].status, 'corpus-changed');
  const current = result(measurement());
  current.measurement.workloads['district-grid-v1'] = current.measurement.workloads['metro-grid-v1'];
  const added = compareResults({ current, previous });
  assert.deepEqual(added.workloads.map(({ id, status }) => [id, status]), [['metro-grid-v1', 'compared'], ['district-grid-v1', 'added']]);
  const removed = compareResults({ current: previous, previous: current });
  assert.deepEqual(removed.workloads.map(({ id, status }) => [id, status]), [['metro-grid-v1', 'compared'], ['district-grid-v1', 'removed']]);
});

test('runner profile changes are noted without suppressing the comparison', () => {
  const previous = result(measurement());
  const otherCpu = context({ runner: { ...context().runner, cpuModel: 'Intel Xeon', cpuCount: 2 } });
  const comparison = compareResults({ current: result(measurement(), otherCpu), previous });
  assert.equal(comparison.workloads[0].status, 'compared');
  assert.match(comparison.notes.join('\n'), /Intel Xeon/);
});

test('the summary shows commit, runner, thresholds, every compared metric and the reason when there is no previous run', () => {
  const previous = result(measurement({ p99: 10_000 }), { commitSha: 'e'.repeat(40), runId: '99', runUrl: 'https://github.com/AquilaXk/easysubway-backend/actions/runs/99' });
  const current = result(measurement({ p99: 14_000 }));
  const compared = renderSummary({ current, comparison: compareResults({ current, previous }) });
  for (const expected of ['aaaaaaa', 'AMD EPYC 7763', '1.30', 'metro-grid-v1', 'DEPART_AT', 'p99', '+40.0%', '| 경고 |', '임계 초과 1건', 'eeeeeee', 'actions/runs/99']) {
    assert.ok(compared.includes(expected), expected);
  }
  const first = renderSummary({ current, comparison: null, noPreviousReason: '직전 성공 run이 없다' });
  assert.ok(first.includes('직전 성공 run이 없다'));
  assert.ok(first.includes('metro-grid-v1'));
  assert.ok(!first.includes('| 경고 |') && !first.includes('임계 초과'));
});

test('the CLI aggregates repeats, compares with the previous artifact, warns without failing, and fails closed on bad input', () => withDirectory((directory) => {
  const write = (name, value) => { const path = join(directory, name); writeFileSync(path, JSON.stringify(value)); return path; };
  const env = {
    PATH: process.env.PATH, GITHUB_SHA: SHA, GITHUB_REF_NAME: 'main', GITHUB_EVENT_NAME: 'schedule', GITHUB_RUN_ID: '101', GITHUB_RUN_ATTEMPT: '1',
    GITHUB_SERVER_URL: 'https://github.com', GITHUB_REPOSITORY: 'AquilaXk/easysubway-backend', RUNNER_NAME: 'GitHub Actions 7', RUNNER_OS: 'Linux', RUNNER_ARCH: 'X64',
    ImageOS: 'ubuntu24', ImageVersion: '20260101.1',
  };
  const run = (args) => spawnSync(process.execPath, [tool, ...args], { env, encoding: 'utf8' });
  const repeats = [measurement({ p99: 14_000 }), measurement({ p99: 14_100 }), measurement({ p99: 13_900 })].flatMap((value, index) => ['--measured', write(`m${index}.json`, value)]);
  const currentPath = join(directory, 'result.json');
  const aggregated = run(['aggregate', ...repeats, '--output', currentPath]);
  assert.equal(aggregated.status, 0, aggregated.stderr);
  const stored = JSON.parse(readFileSync(currentPath, 'utf8'));
  assert.equal(stored.repeats, 3);
  assert.equal(stored.context.commitSha, SHA);
  assert.equal(stored.context.runUrl, 'https://github.com/AquilaXk/easysubway-backend/actions/runs/101');
  assert.equal(stored.measurement.workloads['metro-grid-v1'].modes.DEPART_AT.p99Nanos, 14_000);

  const previousPath = write('previous.json', result(measurement({ p99: 10_000 }), { commitSha: 'e'.repeat(40), runId: '99' }));
  const summaryPath = join(directory, 'summary.md');
  const compared = run(['compare', '--current', currentPath, '--previous', previousPath, '--summary', summaryPath]);
  assert.equal(compared.status, 0, compared.stderr);
  assert.match(compared.stdout, /^::warning title=Journey 엔진 벤치마크 추세::metro-grid-v1 DEPART_AT p99/m);
  assert.match(readFileSync(summaryPath, 'utf8'), /\+40\.0%/);

  const firstRun = run(['compare', '--current', currentPath, '--no-previous', '직전 성공 run이 없다', '--summary', summaryPath]);
  assert.equal(firstRun.status, 0, firstRun.stderr);
  assert.doesNotMatch(firstRun.stdout, /::warning/);
  assert.match(readFileSync(summaryPath, 'utf8'), /직전 성공 run이 없다/);

  assert.notEqual(run(['compare', '--current', currentPath, '--summary', summaryPath]).status, 0);
  assert.notEqual(run(['compare', '--current', currentPath, '--previous', previousPath, '--no-previous', 'x', '--summary', summaryPath]).status, 0);
  const malformed = join(directory, 'malformed.json');
  writeFileSync(malformed, '{');
  // 직전 결과가 깨져 있어도 현재 측정은 유효하다. 막으면 다음 비교도 영원히 막히므로 이유를 경고로 남기고 현재 결과만 기록한다.
  const unreadable = run(['compare', '--current', currentPath, '--previous', malformed, '--summary', summaryPath]);
  assert.equal(unreadable.status, 0, unreadable.stderr);
  assert.match(unreadable.stdout, /^::warning title=Journey 엔진 벤치마크 추세::직전 결과를 읽지 못했습니다/m);
  assert.match(readFileSync(summaryPath, 'utf8'), /비교 대상 없음 — 직전 결과를 읽지 못했습니다/);
  assert.notEqual(run(['compare', '--current', malformed, '--no-previous', 'x', '--summary', summaryPath]).status, 0);
  assert.notEqual(run(['aggregate', '--measured', malformed, '--output', join(directory, 'x.json')]).status, 0);
  assert.notEqual(run(['unknown']).status, 0);
}));
