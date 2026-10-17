import assert from 'node:assert/strict';
import test from 'node:test';
import { context } from './journey-benchmark-test-fixtures.mjs';
import { compareResults } from './journey-mutation-compare.mjs';
import { MUTATION_TITLE, SURVIVOR_LIST_LIMIT, renderSummary } from './journey-mutation-summary.mjs';
import { DISPOSITION, OVERLAY, RUN, previousRun, result } from './journey-mutation-test-fixtures.mjs';

const baseline = (specs) => [1, 2, 3].map((index) => previousRun(index, specs));
const specs = (killed, survived) => ({ [OVERLAY]: { killed, survived }, [DISPOSITION]: { killed: 4 } });

test('the summary shows commit, tool versions, scope, totals, baseline runs, every class and the verdict', () => {
  const current = result(specs(8, 2));
  const text = renderSummary({ current, comparison: compareResults({ current, previous: baseline(specs(9, 1)) }), skipped: [] });
  for (const expected of ['aaaaaaa', 'PIT 1.30.0', 'JUnit 플러그인 1.2.3', '스레드 2', '4분 14초', `${OVERLAY}*`, '85.7%', '직전 성공 run 3개 중앙값', 'actions/runs/901', 'actions/runs/903',
    OVERLAY, DISPOSITION, '90.0%', '80.0%', '-10.0%p', '| 경고 |', '점수 하락 1건', '0.5%p']) {
    assert.ok(text.includes(expected), expected);
  }
});

test('without a baseline the summary gives the reason and the current values only', () => {
  const text = renderSummary({ current: result(), comparison: null, noPreviousReason: '직전 성공 run이 없습니다(첫 실행).', skipped: [] });
  assert.ok(text.includes('직전 성공 run이 없습니다'));
  assert.ok(text.includes(OVERLAY));
  assert.ok(!text.includes('| 경고 |') && !text.includes('점수 하락'));
});

test('the survivors are listed with class, method, line, mutator and description, and a long list is cut with the rest named', () => {
  const current = result({ [OVERLAY]: { killed: 1, survived: SURVIVOR_LIST_LIMIT + 3 } });
  const text = renderSummary({ current, comparison: null, noPreviousReason: 'x', skipped: [] });
  assert.match(text, new RegExp(`잡지 못한 mutant \\(${SURVIVOR_LIST_LIMIT + 3}건\\)`));
  assert.ok(text.includes('ConditionalsBoundaryMutator'));
  assert.ok(text.includes('changed conditional boundary'));
  assert.equal(text.split('\n').filter((line) => line.startsWith('| ') && line.includes('ConditionalsBoundaryMutator')).length, SURVIVOR_LIST_LIMIT);
  assert.match(text, /외 3건은 artifact의 result\.json/);
  const none = renderSummary({ current: result({ [OVERLAY]: { killed: 3 } }), comparison: null, noPreviousReason: 'x', skipped: [] });
  assert.match(none, /잡지 못한 mutant 없음/);
});

test('classes that were added, removed or measured with another tool version are named with their reason', () => {
  const current = result({ [OVERLAY]: { killed: 9, survived: 1 }, 'com.easysubway.journey.application.JourneyAlternatives': { killed: 1, survived: 1 } });
  const added = renderSummary({ current, comparison: compareResults({ current, previous: baseline(specs(9, 1)) }), skipped: [] });
  assert.match(added, /JourneyAlternatives \| .* \| 추가 \|/);
  assert.match(added, new RegExp(`${DISPOSITION} \\| - \\| - \\| - \\| - \\| - \\| 제외 \\|`));
  const other = compareResults({ current, previous: [previousRun(7, specs(9, 1), { run: { ...RUN, pitestVersion: '1.31.0' } })] });
  const text = renderSummary({ current, comparison: other, skipped: [], noPreviousReason: 'x' });
  assert.match(text, /PIT 버전 또는 JUnit 플러그인 버전이 현재.*달라 기준에서 제외/);
  assert.match(text, /\| 보류 \|/);
});

test('skipped previous runs are listed with their reason', () => {
  const text = renderSummary({ current: result(), comparison: null, noPreviousReason: 'x', skipped: [{ runId: '77', kind: 'expired', message: '보존 기간이 지나 artifact가 만료되었습니다.' }] });
  assert.match(text, /기준에서 제외된 run 77 \(expired\): 보존 기간이 지나/);
});

test('text that did not come from a trusted constant is escaped so it cannot add Markdown structure', () => {
  const hostile = 'x`y` | [l](http://evil) <b>z</b>\n# head';
  // 형식 검증을 통과할 수 없는 값이므로 결과를 만든 뒤 직접 바꿔 렌더러의 이스케이프만 따로 확인한다.
  const current = structuredClone(result(specs(8, 2)));
  current.survivors[0].description = hostile;
  current.survivors[0].method = hostile;
  current.context.ref = hostile;
  const text = renderSummary({ current, comparison: null, noPreviousReason: hostile, skipped: [{ runId: '1', kind: 'error', message: hostile }] });
  assert.ok(!/\[l\]\(http/.test(text), 'link syntax must be escaped');
  assert.ok(!text.includes('<b>'));
  assert.ok(!text.includes('\n# head'));
  assert.ok(text.includes('\\|'));
});

test('a scope pattern is shown as one code span that cannot be closed early or continue on another line', () => {
  const current = structuredClone(result());
  current.run.targetClasses = ['a.B`c*\n# head'];
  const line = renderSummary({ current, comparison: null, noPreviousReason: 'x', skipped: [] }).split('\n').find((candidate) => candidate.startsWith('- 범위:'));
  assert.equal(line, "- 범위: `a.B'c* # head`");
});

test('the annotation title names the mutation trend and is shared by the warning lines', () => {
  assert.equal(MUTATION_TITLE, 'Journey 뮤테이션 추세');
});

test('the run link uses the validated commit and run id of the context', () => {
  const text = renderSummary({ current: result(specs(8, 2), { contextOverrides: { ...context(), runId: '555', runUrl: 'https://github.com/AquilaXk/easysubway-backend/actions/runs/555' } }), comparison: null, noPreviousReason: 'x', skipped: [] });
  assert.ok(text.includes('run 555](https://github.com/AquilaXk/easysubway-backend/actions/runs/555)'));
});
