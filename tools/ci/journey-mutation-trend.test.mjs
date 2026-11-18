import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { SHA } from './journey-benchmark-test-fixtures.mjs';
import { REPORT_FILE_NAME, RUN_FILE_NAME } from './journey-mutation-trend.mjs';
import { DISPOSITION, OVERLAY, RUN, mutantsFor, mutationsXml, previousRun, result } from './journey-mutation-test-fixtures.mjs';
import { cliFor, fakeGhEnvironment, manifestIn as manifest, withDirectory as inTemporaryDirectory, writeJson } from './journey-trend-cli-test-support.mjs';

const cli = cliFor(new URL('./journey-mutation-trend.mjs', import.meta.url).pathname);
const withDirectory = (run) => inTemporaryDirectory('journey-mutation-trend-', run);
const specs = (killed, survived) => ({ [OVERLAY]: { killed, survived }, [DISPOSITION]: { killed: 4 } });
const WORKFLOW = 'Journey Mutation Trend';

/** Gradle pitest 태스크가 남기는 보고서 디렉터리: mutations.xml과 실행 설명 JSON. */
function writeReport(directory, { xml = mutationsXml(mutantsFor(OVERLAY, { killed: 8, survived: 2 })), run = RUN } = {}) {
  const report = join(directory, 'pitest');
  mkdirSync(report, { recursive: true });
  writeFileSync(join(report, REPORT_FILE_NAME), xml);
  if (run !== null) writeFileSync(join(report, RUN_FILE_NAME), JSON.stringify(run));
  return report;
}

test('summarize turns the PIT report directory into one result with the run context and the Gradle run description', () => withDirectory((directory) => {
  const output = join(directory, 'result.json');
  const summarized = cli(['summarize', '--report-dir', writeReport(directory), '--output', output]);
  assert.equal(summarized.status, 0, summarized.stderr);
  const stored = JSON.parse(readFileSync(output, 'utf8'));
  assert.equal(stored.context.commitSha, SHA);
  assert.equal(stored.context.runUrl, 'https://github.com/AquilaXk/easysubway-backend/actions/runs/101');
  assert.deepEqual(stored.run, RUN);
  assert.equal(stored.totals.score, 80);
  assert.equal(stored.survivors.length, 2);
}));

test('summarize fails when the measurement cannot be trusted: no report, an incomplete report or no run description', () => withDirectory((directory) => {
  const output = join(directory, 'result.json');
  const failures = {
    'missing directory': join(directory, 'absent'),
    'truncated xml': writeReport(directory, { xml: mutationsXml(mutantsFor(OVERLAY, { killed: 1 })).replace('</mutations>\n', '') }),
  };
  for (const [name, reportDir] of Object.entries(failures)) assert.notEqual(cli(['summarize', '--report-dir', reportDir, '--output', output]).status, 0, name);
  const noRun = writeReport(join(directory, 'norun'), { run: null });
  assert.notEqual(cli(['summarize', '--report-dir', noRun, '--output', output]).status, 0, 'no run description');
  assert.notEqual(cli(['summarize', '--report-dir', writeReport(join(directory, 'zero'), { xml: mutationsXml([]) }), '--output', output]).status, 0, 'no mutations');
}));

test('compare warns about a score drop without failing and writes the summary', () => withDirectory((directory) => {
  const current = writeJson(directory, 'current.json', result(specs(8, 2)));
  const previous = [1, 2, 3].map((index) => writeJson(directory, `p${index}.json`, previousRun(index, specs(9, 1))));
  const summary = join(directory, 'summary.md');
  const compared = cli(['compare', '--current', current, '--previous-manifest', manifest(directory, previous), '--summary', summary]);
  assert.equal(compared.status, 0, compared.stderr);
  assert.match(compared.stdout, /^::warning title=Journey 뮤테이션 추세::com\.easysubway\.route\.application\.service\.JourneyFacilityBlockOverlay: 뮤테이션 점수 기준 중앙값 90\.0%/m);
  const text = readFileSync(summary, 'utf8');
  assert.match(text, /-10\.0%p/);
  assert.match(text, /직전 성공 run 3개 중앙값/);
}));

test('a first run, or a manifest without usable runs, records the reason and raises no warning', () => withDirectory((directory) => {
  const current = writeJson(directory, 'current.json', result());
  const summary = join(directory, 'summary.md');
  const first = cli(['compare', '--current', current, '--previous-manifest', manifest(directory, [], { reason: '직전 성공 run이 없습니다(첫 실행).' }), '--summary', summary]);
  assert.equal(first.status, 0, first.stderr);
  assert.doesNotMatch(first.stdout, /::warning/);
  assert.match(readFileSync(summary, 'utf8'), /비교 대상 없음 — 직전 성공 run이 없습니다/);
}));

test('a damaged previous result is skipped with a warning and the remaining runs still give the comparison', () => withDirectory((directory) => {
  const good = (index) => writeJson(directory, `good${index}.json`, previousRun(index, specs(9, 1)));
  const broken = {
    'missing context': (value) => { delete value.context; },
    'missing run': (value) => { delete value.run; },
    'tampered score': (value) => { value.totals.score = 12.34; },
  };
  const current = writeJson(directory, 'current.json', result(specs(8, 2)));
  for (const [name, change] of Object.entries(broken)) {
    const damaged = structuredClone(previousRun(9, specs(9, 1))); change(damaged);
    const damagedPath = writeJson(directory, `bad-${name.replaceAll(' ', '-')}.json`, damaged);
    const outcome = cli(['compare', '--current', current, '--previous-manifest', manifest(directory, [good(1), good(2), good(3), damagedPath]), '--summary', join(directory, `summary-${name.replaceAll(' ', '-')}.md`)]);
    assert.equal(outcome.status, 0, `${name}: ${outcome.stderr}`);
    assert.match(outcome.stdout, /^::warning title=Journey 뮤테이션 추세::run [^:]+: 직전 결과를 읽지 못했습니다/m, name);
    assert.match(outcome.stdout, /뮤테이션 점수 기준 중앙값/, `${name}: the good runs still produce the comparison`);
  }
  const summary = join(directory, 'current-invalid.md');
  assert.notEqual(cli(['compare', '--current', writeJson(directory, 'bad-current.json', '{'), '--previous-manifest', manifest(directory, []), '--summary', summary]).status, 0, 'the current result must be valid');
}));

test('compare rejects a missing or malformed manifest and unknown commands', () => withDirectory((directory) => {
  const current = writeJson(directory, 'current.json', result());
  const summary = join(directory, 'summary.md');
  assert.notEqual(cli(['compare', '--current', current, '--summary', summary]).status, 0);
  assert.notEqual(cli(['compare', '--current', current, '--previous-manifest', writeJson(directory, 'm.json', '{}'), '--summary', summary]).status, 0);
  assert.notEqual(cli(['unknown']).status, 0);
}));

test('fetch-previous looks for the mutation artifacts, validates them as mutation results and annotates failures by cause', () => withDirectory((directory) => {
  const seed = writeJson(directory, 'seed.json', previousRun(1));
  const env = fakeGhEnvironment(directory, { runIds: ['31', '30', '29'], resultPath: seed, artifactName: 'journey-mutation-x' });
  const output = join(directory, 'previous', 'manifest.json');
  const args = ['fetch-previous', '--repo', 'AquilaXk/easysubway-backend', '--workflow', WORKFLOW, '--dir', join(directory, 'previous'), '--output', output];
  const fetched = cli(args, env);
  assert.equal(fetched.status, 0, fetched.stderr);
  assert.equal(JSON.parse(readFileSync(output, 'utf8')).results.length, 3);
  assert.doesNotMatch(fetched.stdout, /::warning/);

  const expired = cli(args, { ...env, FAKE_ARTIFACTS: '[{"name":"journey-mutation-x","expired":true}]' });
  assert.equal(expired.status, 0, expired.stderr);
  assert.match(expired.stdout, /^::notice title=Journey 뮤테이션 추세::run 31/m);

  const missing = cli(args, { ...env, FAKE_ARTIFACTS: '[]' });
  assert.match(missing.stdout, /^::warning title=Journey 뮤테이션 추세::run 31.*journey-mutation-\*/m);

  mkdirSync(join(directory, 'shaped'));
  const listFailure = cli(args, { ...env, FAKE_LIST_FAILURE: 'HTTP 403 rate limit' });
  assert.equal(listFailure.status, 0, listFailure.stderr);
  assert.match(listFailure.stdout, /^::warning title=Journey 뮤테이션 추세::run 목록을 조회하지 못했습니다.*HTTP 403/m);

  const benchmarkShaped = fakeGhEnvironment(join(directory, 'shaped'), { runIds: ['31'], resultPath: writeJson(directory, 'not-mutation.json', '{"schemaVersion":1}'), artifactName: 'journey-mutation-x' });
  const rejected = cli(args, benchmarkShaped);
  assert.match(rejected.stdout, /^::warning title=Journey 뮤테이션 추세::run 31/m);
  assert.deepEqual(JSON.parse(readFileSync(output, 'utf8')).results, []);
}));
