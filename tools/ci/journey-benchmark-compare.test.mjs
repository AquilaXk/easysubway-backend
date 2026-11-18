import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ALLOCATION_REGRESSION_RATIO,
  MIN_BASELINE_RUNS_FOR_WARNING,
  WALL_CLOCK_REGRESSION_RATIO,
  compareResults,
} from './journey-benchmark-compare.mjs';
import { context, measurement, previousRun, result } from './journey-benchmark-test-fixtures.mjs';

/** 기준 run 개수가 경고 보류선 이상이 되도록 같은 값의 직전 run을 만든다. */
const baselineOf = (m, count = MIN_BASELINE_RUNS_FOR_WARNING) => Array.from({ length: count }, (_, index) => previousRun(index + 1, m));
const only = (comparison, id = 'metro-grid-v1') => comparison.workloads.find((workload) => workload.id === id);

test('thresholds and the minimum baseline size are named constants', () => {
  assert.equal(WALL_CLOCK_REGRESSION_RATIO, 1.3);
  assert.equal(ALLOCATION_REGRESSION_RATIO, 1.3);
  assert.equal(MIN_BASELINE_RUNS_FOR_WARNING, 3);
});

test('a metric fires exactly at the threshold and not below it', () => {
  const previous = baselineOf(measurement({ p99: 10_000 }));
  assert.deepEqual(compareResults({ current: result(measurement({ p99: 12_900 })), previous }).regressions, []);
  const at = compareResults({ current: result(measurement({ p99: 13_000 })), previous });
  assert.equal(at.regressions.length, 1);
  assert.match(at.regressions[0].message, /metro-grid-v1 DEPART_AT p99/);
  assert.match(at.regressions[0].message, /1\.30/);
});

test('wall-clock comparison is normalized by the calibration work so a slower runner alone is not a regression', () => {
  const previous = baselineOf(measurement({ calibration: 100_000_000, p50: 1_000, p99: 4_000, compile: 50_000_000 }));
  const slowerRunner = result(measurement({ calibration: 200_000_000, p50: 2_000, p99: 8_000, compile: 100_000_000 }));
  assert.deepEqual(compareResults({ current: slowerRunner, previous }).regressions, []);
  const slowerEngine = result(measurement({ calibration: 100_000_000, p50: 2_000, p99: 8_000, compile: 100_000_000 }));
  const labels = compareResults({ current: slowerEngine, previous }).regressions.map(({ message }) => message);
  assert.equal(labels.length, 3);
  assert.ok(labels.some((message) => message.includes('p50')));
  assert.ok(labels.some((message) => message.includes('컴파일 시간')));
});

test('allocation is compared raw and flagged separately from latency', () => {
  const previous = baselineOf(measurement({ query: 10_000 }));
  const grown = compareResults({ current: result(measurement({ query: 13_000 })), previous });
  assert.equal(grown.regressions.length, 1);
  assert.match(grown.regressions[0].message, /질의당 할당/);
  assert.deepEqual(compareResults({ current: result(measurement({ query: 5_000 })), previous }).regressions, []);
});

test('the baseline is the median of the previous runs so one noisy run neither hides nor fakes a regression', () => {
  const noisyBaseline = [10_000, 10_000, 30_000].map((p99, index) => previousRun(index + 1, measurement({ p99 })));
  const regressed = compareResults({ current: result(measurement({ p99: 14_000 })), previous: noisyBaseline });
  assert.equal(regressed.regressions.length, 1, 'a 30 us outlier must not raise the baseline');
  assert.equal(only(regressed).baselineRuns, 3);
  const outlierOnlyLast = [30_000, 10_000, 10_000].map((p99, index) => previousRun(index + 1, measurement({ p99 })));
  assert.deepEqual(compareResults({ current: result(measurement({ p99: 10_500 })), previous: outlierOnlyLast }).regressions, []);
  const evenCount = [10_000, 20_000, 10_000, 20_000].map((p99, index) => previousRun(index + 1, measurement({ p99 })));
  assert.deepEqual(compareResults({ current: result(measurement({ p99: 19_000 })), previous: evenCount }).regressions, [], 'median of 4 is 15 us');
  assert.equal(compareResults({ current: result(measurement({ p99: 20_000 })), previous: evenCount }).regressions.length, 1);
});

test('with fewer baseline runs than the minimum the table shows the excess but no warning is raised', () => {
  const previous = baselineOf(measurement({ p99: 10_000 }), MIN_BASELINE_RUNS_FOR_WARNING - 1);
  const comparison = compareResults({ current: result(measurement({ p99: 20_000 })), previous });
  assert.deepEqual(comparison.regressions, []);
  assert.equal(only(comparison).baselineRuns, MIN_BASELINE_RUNS_FOR_WARNING - 1);
  assert.equal(only(comparison).sufficient, false);
  const row = only(comparison).rows.find((candidate) => candidate.metric.label === 'p99');
  assert.equal(row.exceeded, true);
  assert.equal(row.regressed, false);
});

test('a workload whose bundle or query set changed is excluded from comparison instead of reported as a regression', () => {
  const previous = baselineOf(measurement());
  const changed = compareResults({ current: result(measurement({ bundle: 'd'.repeat(64), p99: 400_000 })), previous });
  assert.deepEqual(changed.regressions, []);
  assert.equal(only(changed).status, 'corpus-changed');
  const otherQueries = compareResults({ current: result(measurement({ queries: 'f'.repeat(64), p99: 400_000 })), previous });
  assert.equal(only(otherQueries).status, 'corpus-changed', 'a changed query set alone also makes the runs incomparable');
  assert.deepEqual(otherQueries.regressions, []);
  const current = result();
  current.measurement.workloads['district-grid-v1'] = current.measurement.workloads['metro-grid-v1'];
  const added = compareResults({ current, previous });
  assert.deepEqual(added.workloads.map(({ id, status }) => [id, status]), [['metro-grid-v1', 'compared'], ['district-grid-v1', 'added']]);
  const removed = compareResults({ current: previous[0], previous: [current] });
  assert.deepEqual(removed.workloads.map(({ id, status }) => [id, status]), [['metro-grid-v1', 'compared'], ['district-grid-v1', 'removed']]);
});

test('only previous runs with the same corpus form a workload baseline', () => {
  const previous = [previousRun(1), previousRun(2, measurement({ bundle: 'd'.repeat(64), p99: 90_000 })), previousRun(3), previousRun(4)];
  const comparison = compareResults({ current: result(measurement({ p99: 4_100 })), previous });
  assert.equal(only(comparison).status, 'compared');
  assert.equal(only(comparison).baselineRuns, 3);
  assert.deepEqual(comparison.regressions, []);
});

test('a changed measurement method makes the runs incomparable and says which method fields differ', () => {
  const cases = [
    ['warmupRounds', { warmupRounds: 3 }],
    ['measuredRounds', { measuredRounds: 7 }],
    ['java', { java: 'Eclipse Adoptium 21.0.12' }],
    ['profileLimits', { profileLimits: { maxEstimatedWork: 1, maxLabelsPerState: 64, maxDestinationProfileLabels: 64, maxProfileBreakpoints: 64 } }],
  ];
  for (const [field, method] of cases) {
    const previous = baselineOf(measurement({ method }));
    const comparison = compareResults({ current: result(measurement({ p99: 400_000 })), previous });
    assert.deepEqual(comparison.regressions, [], field);
    assert.equal(only(comparison).status, 'method-changed', field);
    assert.match(comparison.notes.join('\n'), new RegExp(field), field);
  }
  const mixed = [previousRun(1), previousRun(2, measurement({ method: { java: 'Other 21' } })), previousRun(3), previousRun(4)];
  const partial = compareResults({ current: result(), previous: mixed });
  assert.equal(only(partial).baselineRuns, 3, 'the run with another method is left out of the baseline');
  assert.match(partial.notes.join('\n'), /java/);
});

test('arch and processors are runner properties and only produce a runner note', () => {
  const previous = baselineOf(measurement({ method: { arch: 'aarch64', processors: 2 } }));
  const comparison = compareResults({ current: result(), previous });
  assert.equal(only(comparison).status, 'compared');
});

test('runner profile changes are noted without suppressing the comparison', () => {
  const previous = baselineOf(measurement());
  const otherCpu = { runner: { ...context().runner, cpuModel: 'Intel Xeon', cpuCount: 2 } };
  const comparison = compareResults({ current: result(measurement(), otherCpu), previous });
  assert.equal(only(comparison).status, 'compared');
  assert.match(comparison.notes.join('\n'), /Intel Xeon/);
});

test('comparison needs at least one previous run', () => {
  assert.throws(() => compareResults({ current: result(), previous: [] }), /at least one previous/);
});
