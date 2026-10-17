import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { ARTIFACT_PREFIX, BASELINE_CANDIDATE_LIMIT, BASELINE_RUN_LIMIT, fetchPreviousResults } from './journey-benchmark-previous.mjs';
import { result } from './journey-benchmark-test-fixtures.mjs';

const withDirectory = (run) => {
  const directory = mkdtempSync(join(tmpdir(), 'journey-benchmark-previous-'));
  try { return run(directory); } finally { rmSync(directory, { recursive: true, force: true }); }
};
const ok = (stdout = '') => ({ status: 0, stdout, stderr: '' });
const failed = (stderr) => ({ status: 1, stdout: '', stderr });
const artifact = (expired = false) => ok(JSON.stringify([{ name: `${ARTIFACT_PREFIX}abc`, expired }]));

/** gh 호출을 인자로 가르는 가짜. 실행별 동작은 behaviours[runId]가 정한다. */
function fakeGh(runIds, behaviours, calls = []) {
  return (args) => {
    calls.push(args);
    if (args[0] === 'run' && args[1] === 'list') return ok(`${runIds.join('\n')}\n`);
    const runId = args.find((arg) => /^\d+$/.test(arg)) ?? /runs\/(\d+)\//.exec(args[1] ?? '')?.[1];
    const behaviour = behaviours[runId] ?? {};
    if (args[0] === 'api') return behaviour.artifacts ?? artifact();
    return behaviour.download ? behaviour.download(args) : ok();
  };
}
const writeResult = (directory, runId, content = JSON.stringify(result())) => {
  const folder = join(directory, runId);
  mkdirSync(folder, { recursive: true });
  writeFileSync(join(folder, 'result.json'), content);
};
const downloads = (directory, content) => (args) => { writeResult(directory, args[2], content); return ok(); };
const fetchWith = (directory, gh, overrides = {}) => fetchPreviousResults({ gh, repo: 'AquilaXk/easysubway-backend', workflow: 'Journey Engine Benchmark Trend', directory, ...overrides });
const kinds = (outcome) => outcome.skipped.map(({ runId, kind }) => `${runId}:${kind}`);

test('the baseline size and the candidate pool are named constants', () => {
  assert.equal(BASELINE_RUN_LIMIT, 5);
  assert.ok(BASELINE_CANDIDATE_LIMIT > BASELINE_RUN_LIMIT);
});

test('it lists the successful main runs of this workflow and downloads the newest ones up to the baseline size', () => withDirectory((directory) => {
  const calls = [];
  const ids = ['16', '15', '14', '13', '12', '11', '10'];
  const behaviours = Object.fromEntries(ids.map((id) => [id, { download: downloads(directory) }]));
  const outcome = fetchWith(directory, fakeGh(ids, behaviours, calls));
  assert.deepEqual(outcome.results, ids.slice(0, BASELINE_RUN_LIMIT).map((id) => join(directory, id, 'result.json')));
  assert.deepEqual(outcome.skipped, []);
  const list = calls[0];
  for (const expected of ['--repo', 'AquilaXk/easysubway-backend', '--workflow', 'Journey Engine Benchmark Trend', '--branch', 'main', '--status', 'success', '--limit', String(BASELINE_CANDIDATE_LIMIT)]) assert.ok(list.includes(expected), expected);
  assert.equal(calls.filter((args) => args[0] === 'run' && args[1] === 'download').length, BASELINE_RUN_LIMIT, 'it stops once the baseline is full');
}));

test('failures are told apart by cause and later candidates fill the baseline', () => withDirectory((directory) => {
  writeFileSync(join(directory, 'valid-elsewhere.json'), JSON.stringify(result()));
  const ids = ['20', '19', '18', '17', '16', '15', '14', '13', '12'];
  const good = { download: downloads(directory) };
  const behaviours = {
    20: { artifacts: artifact(true) },
    19: { artifacts: ok('[]') },
    18: { artifacts: failed('HTTP 502') },
    17: { download: () => failed('connection reset') },
    16: { download: (args) => { writeResult(directory, args[2], '{'); return ok(); } },
    15: { download: (args) => { mkdirSync(join(directory, args[2]), { recursive: true }); return ok(); } },
    14: { download: (args) => { mkdirSync(join(directory, args[2]), { recursive: true }); symlinkSync(join(directory, 'valid-elsewhere.json'), join(directory, args[2], 'result.json')); return ok(); } },
    13: good, 12: good,
  };
  const outcome = fetchWith(directory, fakeGh(ids, behaviours));
  assert.deepEqual(kinds(outcome), ['20:expired', '19:missing-artifact', '18:error', '17:error', '16:unreadable', '15:unreadable', '14:unreadable']);
  assert.deepEqual(outcome.results, [join(directory, '13', 'result.json'), join(directory, '12', 'result.json')]);
  assert.match(outcome.skipped.find(({ runId }) => runId === '18').message, /HTTP 502/);
  assert.equal(outcome.skipped.find(({ runId }) => runId === '20').message.includes('보존 기간'), true);
}));

test('without any usable run the outcome carries a reason instead of an empty baseline', () => withDirectory((directory) => {
  const none = fetchWith(directory, () => ok('\n'));
  assert.deepEqual(none.results, []);
  assert.match(none.reason, /직전 성공 run이 없습니다/);
  assert.equal(none.warning, false);

  const listFailure = fetchWith(directory, () => failed('HTTP 403 rate limit'));
  assert.match(listFailure.reason, /run 목록을 조회하지 못했습니다.*HTTP 403/);
  assert.equal(listFailure.warning, true);

  const allExpired = fetchWith(directory, fakeGh(['5', '4'], { 5: { artifacts: artifact(true) }, 4: { artifacts: artifact(true) } }));
  assert.deepEqual(allExpired.results, []);
  assert.match(allExpired.reason, /사용할 수 있는 직전 결과가 없습니다/);
}));

test('only numeric run ids from the listing are used', () => withDirectory((directory) => {
  const calls = [];
  const outcome = fetchWith(directory, fakeGh(['12', 'abc', '../x', ''], { 12: { download: downloads(directory) } }, calls));
  assert.deepEqual(outcome.results, [join(directory, '12', 'result.json')]);
  assert.ok(!calls.flat().some((arg) => arg.includes('..')));
}));
