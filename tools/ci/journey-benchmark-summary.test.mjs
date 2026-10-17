import assert from 'node:assert/strict';
import test from 'node:test';
import { RECALIBRATION_REPEATS, calibrationRangeNotice, compareResults } from './journey-benchmark-compare.mjs';
import { annotation, renderSummary } from './journey-benchmark-summary.mjs';
import { context, measurement, previousRun, result } from './journey-benchmark-test-fixtures.mjs';

const baseline = (m) => [1, 2, 3].map((index) => previousRun(index, m));

test('the summary shows commit, runner, thresholds, baseline runs, every compared metric and the verdict', () => {
  const current = result(measurement({ p99: 14_000 }));
  const text = renderSummary({ current, comparison: compareResults({ current, previous: baseline(measurement({ p99: 10_000 })) }), skipped: [] });
  for (const expected of ['aaaaaaa', 'AMD EPYC 7763', 'ubuntu24', '1.30', '직전 성공 run 3개 중앙값', 'metro-grid-v1', 'DEPART_AT', 'p99', '+40.0%', '| 경고 |', '임계 초과 1건', 'actions/runs/901', 'actions/runs/903']) {
    assert.ok(text.includes(expected), expected);
  }
});

test('missing image metadata is named as unobserved instead of being omitted silently', () => {
  const bare = result(measurement(), { runner: { ...context().runner, image: null, imageVersion: null } });
  assert.match(renderSummary({ current: bare, comparison: null, noPreviousReason: '직전 성공 run이 없습니다', skipped: [] }), /이미지 미관측/);
});

test('without a baseline the summary gives the reason and the current values only', () => {
  const text = renderSummary({ current: result(), comparison: null, noPreviousReason: '직전 성공 run이 없습니다', skipped: [] });
  assert.ok(text.includes('직전 성공 run이 없습니다'));
  assert.ok(text.includes('metro-grid-v1'));
  assert.ok(!text.includes('| 경고 |') && !text.includes('임계 초과'));
});

test('an insufficient baseline is stated and its excess is not labelled as a warning', () => {
  const current = result(measurement({ p99: 20_000 }));
  const previous = baseline(measurement({ p99: 10_000 })).slice(0, 2);
  const text = renderSummary({ current, comparison: compareResults({ current, previous }), skipped: [] });
  assert.match(text, /기준 표본 부족/);
  assert.match(text, /\| 보류 \|/);
  assert.ok(!text.includes('| 경고 |'));
  assert.ok(text.includes('임계 초과 없음'));
});

test('excluded runs and workloads are listed with their reason', () => {
  const current = result();
  const methodChanged = compareResults({ current, previous: baseline(measurement({ method: { java: 'Other 21' } })) });
  const text = renderSummary({ current, comparison: methodChanged, skipped: [{ runId: '77', kind: 'expired', message: '보존 기간이 지나 artifact가 만료되었습니다.' }] });
  assert.match(text, /측정 방법이 직전과 달라 비교에서 제외/);
  assert.match(text, /run 77/);
  assert.match(text, /보존 기간이 지나/);
  const corpus = compareResults({ current: result(measurement({ bundle: 'd'.repeat(64) })), previous: baseline(measurement()) });
  assert.match(renderSummary({ current: result(measurement({ bundle: 'd'.repeat(64) })), comparison: corpus, skipped: [] }), /번들 또는 질의 집합 sha256이 직전과 달라/);
});

test('text that did not come from a trusted constant is escaped so it cannot add Markdown structure', () => {
  const hostile = 'x`y` | [l](http://evil) <b>z</b>\n# head';
  // 형식 검증을 통과할 수 없는 값이므로 결과를 만든 뒤 직접 바꿔 렌더러의 이스케이프만 따로 확인한다.
  const current = structuredClone(result());
  Object.assign(current.context.runner, { cpuModel: hostile, name: hostile });
  current.context.ref = hostile;
  const text = renderSummary({ current, comparison: null, noPreviousReason: hostile, skipped: [{ runId: '1', kind: 'error', message: hostile }] });
  assert.ok(!/\[l\]\(http/.test(text), 'link syntax must be escaped');
  assert.ok(!text.includes('<b>'));
  assert.ok(!text.includes('\n# head'));
  assert.ok(text.includes('\\|'));
  assert.ok(text.includes('\\`'));
});

test('annotations are single-line and percent-escaped', () => {
  assert.equal(annotation('a%b\nc\rd'), '::warning title=Journey 엔진 벤치마크 추세::a%25b%0Ac%0Dd\n');
  assert.ok(annotation('x'.repeat(2_000)).length < 700, 'long messages are truncated');
});

test('the summary shows the gate baseline calibration range and warns when this runner is outside it', () => {
  const range = { min: 70_000_000, max: 98_000_000 };
  const inside = renderSummary({ current: result(measurement({ calibration: 90_000_000 })), comparison: null, noPreviousReason: '없음', skipped: [], gateCalibration: { range, notice: null } });
  assert.match(inside, /게이트 기준선의 보정 작업 시간 산출 범위: 70\.00 ms ~ 98\.00 ms/);
  assert.ok(!inside.includes('- 경고:'));
  const notice = calibrationRangeNotice(110_000_000, range);
  const outside = renderSummary({ current: result(measurement({ calibration: 110_000_000 })), comparison: null, noPreviousReason: '없음', skipped: [], gateCalibration: { range, notice } });
  const unescaped = outside.replaceAll('\\', '');
  assert.match(unescaped, /- 경고: 보정 작업 시간 110\.0 ms가 게이트 기준선 산출 범위 70~98 ms 밖입니다/);
  assert.match(unescaped, new RegExp(`같은 경고가 ${RECALIBRATION_REPEATS}번 반복되면`));
});

test('the calibration range is inclusive at both ends', () => {
  const range = { min: 70_000_000, max: 98_000_000 };
  assert.equal(calibrationRangeNotice(70_000_000, range), null);
  assert.equal(calibrationRangeNotice(98_000_000, range), null);
  assert.ok(calibrationRangeNotice(69_999_999, range));
  assert.ok(calibrationRangeNotice(98_000_001, range));
});
