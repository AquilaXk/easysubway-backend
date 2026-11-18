import assert from 'node:assert/strict';
import test from 'node:test';
import { SCORE_DROP_TOLERANCE_POINTS, compareResults } from './journey-mutation-compare.mjs';
import { DISPOSITION, OVERLAY, RUN, previousRun, result } from './journey-mutation-test-fixtures.mjs';

const OTHER = 'com.easysubway.journey.application.JourneyAlternatives';
// OVERLAY 점수는 killed/(killed+survived)다: 9/10 = 90%, 8/10 = 80%.
const overlay = (killed, survived) => ({ [OVERLAY]: { killed, survived }, [DISPOSITION]: { killed: 4 } });
const baseline = (specs, count = 3) => Array.from({ length: count }, (_, index) => previousRun(index + 1, specs));
const rowOf = (comparison, className) => comparison.classes.find((row) => row.className === className);

test('the tolerance is a named value smaller than one lost kill of a typical class', () => {
  assert.equal(SCORE_DROP_TOLERANCE_POINTS, 0.5);
});

test('a class whose score fell below the baseline median is a regression with its figures in the message', () => {
  const comparison = compareResults({ current: result(overlay(8, 2)), previous: baseline(overlay(9, 1)) });
  const row = rowOf(comparison, OVERLAY);
  assert.equal(row.status, 'compared');
  assert.equal(row.baselineScore, 90);
  assert.equal(row.score, 80);
  assert.equal(row.delta, -10);
  assert.equal(row.regressed, true);
  assert.deepEqual(comparison.regressions.map(({ className }) => className), [OVERLAY]);
  assert.match(comparison.regressions[0].message, /JourneyFacilityBlockOverlay.*90(\.0)?%.*80(\.0)?%.*-10/);
  assert.equal(rowOf(comparison, DISPOSITION).regressed, false);
});

test('an unchanged or improved score and a drop within the tolerance are not regressions', () => {
  assert.equal(compareResults({ current: result(overlay(9, 1)), previous: baseline(overlay(9, 1)) }).regressions.length, 0);
  assert.equal(compareResults({ current: result(overlay(10, 0)), previous: baseline(overlay(9, 1)) }).regressions.length, 0);
  // 200개 중 1개 차이 = 0.5%p. 허용값과 같으면 경고하지 않는다(경계 포함).
  const large = (killed, survived) => ({ [OVERLAY]: { killed, survived } });
  assert.equal(compareResults({ current: result(large(99, 1)), previous: baseline(large(199, 1)) }).regressions.length, 0);
  assert.equal(compareResults({ current: result(large(98, 2)), previous: baseline(large(199, 1)) }).regressions.length, 1);
});

test('the baseline is the median of the previous runs, so one outlier run in either direction does not move it', () => {
  const low = [previousRun(1, overlay(9, 1)), previousRun(2, overlay(9, 1)), previousRun(3, overlay(1, 9))];
  const lowOutlier = compareResults({ current: result(overlay(8, 2)), previous: low });
  assert.equal(rowOf(lowOutlier, OVERLAY).baselineScore, 90);
  assert.equal(lowOutlier.regressions.length, 1, 'a low outlier must not hide the drop');
  const high = [previousRun(1, overlay(9, 1)), previousRun(2, overlay(9, 1)), previousRun(3, overlay(10, 0))];
  const highOutlier = compareResults({ current: result(overlay(9, 1)), previous: high });
  assert.equal(rowOf(highOutlier, OVERLAY).baselineScore, 90);
  assert.equal(highOutlier.regressions.length, 0, 'a high outlier must not raise a false alarm');
});

test('one previous run is enough to compare because PIT is deterministic for unchanged code', () => {
  const comparison = compareResults({ current: result(overlay(8, 2)), previous: baseline(overlay(9, 1), 1) });
  assert.equal(comparison.baselineRuns, 1);
  assert.equal(comparison.regressions.length, 1);
});

test('classes are compared one by one, so a scope change is reported but never a regression', () => {
  const current = result({ ...overlay(9, 1), [OTHER]: { killed: 1, survived: 9 } });
  const comparison = compareResults({ current, previous: baseline(overlay(9, 1)) });
  assert.equal(rowOf(comparison, OTHER).status, 'added');
  assert.equal(comparison.regressions.length, 0);
  const shrunk = compareResults({ current: result({ [OVERLAY]: { killed: 9, survived: 1 } }), previous: baseline(overlay(9, 1)) });
  assert.equal(rowOf(shrunk, DISPOSITION).status, 'removed');
  assert.equal(shrunk.regressions.length, 0);
});

test('runs measured with another PIT version, JUnit plugin version or measurement setup are excluded because their mutants differ', () => {
  const others = {
    'PIT version': { ...RUN, pitestVersion: '1.31.0' },
    'JUnit plugin version': { ...RUN, junit5PluginVersion: '1.2.4' },
    'measurement setup': { ...RUN, configHash: 'd'.repeat(64) },
  };
  for (const [name, other] of Object.entries(others)) {
    const previous = [...baseline(overlay(9, 1), 2), previousRun(7, overlay(1, 9), { run: other })];
    const comparison = compareResults({ current: result(overlay(9, 1)), previous });
    assert.equal(comparison.baselineRuns, 2, name);
    assert.match(comparison.notes.join('\n'), /1개는 PIT·JUnit 플러그인 버전 또는 측정 설정.*달라 기준에서 제외/, name);
    const allOther = compareResults({ current: result(overlay(1, 9)), previous: [previousRun(7, overlay(9, 1), { run: other })] });
    assert.equal(allOther.baselineRuns, 0, name);
    assert.deepEqual(allOther.classes.map(({ status }) => status), ['method-changed', 'method-changed'], name);
    assert.equal(allOther.regressions.length, 0, name);
  }
});

// 점수 허용(0.5%p) 안에 머무는 큰 클래스: killed 399 + survived 1 = 99.75%, killed 398 + survived 2 = 99.5%.
const large = (killed, survived) => ({ [OVERLAY]: { killed, survived } });

test('a class that gains a surviving mutant is a regression even when its score stays inside the tolerance', () => {
  const comparison = compareResults({ current: result(large(398, 2)), previous: baseline(large(399, 1)) });
  const row = rowOf(comparison, OVERLAY);
  assert.equal(row.delta, -0.25);
  assert.equal(row.baselineSurvived, 1);
  assert.equal(row.survived, 2);
  assert.equal(row.regressed, true);
  assert.match(row.message, /JourneyFacilityBlockOverlay: 생존 mutant 기준 중앙값 1건 → 현재 2건/);
  assert.doesNotMatch(row.message, /점수 기준 중앙값/, 'the score did not drop beyond the tolerance');
  assert.deepEqual(comparison.regressions.map(({ className }) => className), [OVERLAY]);
});

test('an equal or smaller survivor count is silent, and the baseline survivor count is the median of the previous runs', () => {
  assert.equal(compareResults({ current: result(large(399, 1)), previous: baseline(large(399, 1)) }).regressions.length, 0);
  assert.equal(compareResults({ current: result(large(399, 1)), previous: baseline(large(398, 2)) }).regressions.length, 0);
  const previous = [previousRun(1, large(399, 1)), previousRun(2, large(399, 1)), previousRun(3, large(390, 10))];
  const outlier = compareResults({ current: result(large(399, 1)), previous });
  assert.equal(rowOf(outlier, OVERLAY).baselineSurvived, 1);
  assert.equal(outlier.regressions.length, 0, 'a high outlier must not hide or raise the baseline');
  const low = [previousRun(1, large(399, 1)), previousRun(2, large(399, 1)), previousRun(3, large(400, 0))];
  assert.equal(compareResults({ current: result(large(398, 2)), previous: low }).regressions.length, 1, 'a low outlier must not hide the increase');
});

test('uncovered mutants are not survivors: only the survived count drives the survivor warning', () => {
  const noCoverage = { [OVERLAY]: { killed: 399, survived: 1, noCoverage: 0 } };
  const moreUncovered = { [OVERLAY]: { killed: 399, survived: 1, noCoverage: 1 } };
  const comparison = compareResults({ current: result(moreUncovered), previous: baseline(noCoverage) });
  assert.equal(rowOf(comparison, OVERLAY).regressed, false);
});

test('a class that drops in score and gains survivors reports both reasons in one message', () => {
  const comparison = compareResults({ current: result(overlay(8, 2)), previous: baseline(overlay(9, 1)) });
  const { message } = rowOf(comparison, OVERLAY);
  assert.match(message, /점수 기준 중앙값 90\.0% → 현재 80\.0%/);
  assert.match(message, /생존 mutant 기준 중앙값 1건 → 현재 2건/);
});

test('comparing needs at least one previous result', () => {
  assert.throws(() => compareResults({ current: result(), previous: [] }), /at least one previous result/);
});

test('the baseline contexts are kept in the order given so the summary can link them', () => {
  const comparison = compareResults({ current: result(), previous: baseline(overlay(9, 1)) });
  assert.deepEqual(comparison.baselineContexts.map(({ runId }) => runId), ['901', '902', '903']);
});
