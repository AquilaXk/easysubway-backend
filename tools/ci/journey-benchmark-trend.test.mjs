import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { SHA, measurement, previousRun, result } from './journey-benchmark-test-fixtures.mjs';
import { BASE_ENV, cliFor, fakeGhEnvironment, manifestIn as manifest, withDirectory as inTemporaryDirectory, writeJson } from './journey-trend-cli-test-support.mjs';

const cli = cliFor(new URL('./journey-benchmark-trend.mjs', import.meta.url).pathname);
const withDirectory = (run) => inTemporaryDirectory('journey-benchmark-trend-', run);
const fakeGh = (directory, options) => fakeGhEnvironment(directory, { artifactName: 'journey-engine-benchmark-x', ...options });

test('the CLI aggregates repeats into one result that keeps image metadata null when the runner does not provide it', () => withDirectory((directory) => {
  const repeats = [14_000, 14_100, 13_900].flatMap((p99, index) => ['--measured', writeJson(directory, `m${index}.json`, measurement({ p99 }))]);
  const output = join(directory, 'result.json');
  const aggregated = cli(['aggregate', ...repeats, '--output', output]);
  assert.equal(aggregated.status, 0, aggregated.stderr);
  const stored = JSON.parse(readFileSync(output, 'utf8'));
  assert.equal(stored.repeats, 3);
  assert.equal(stored.context.commitSha, SHA);
  assert.equal(stored.context.runner.image, null);
  assert.equal(stored.context.runUrl, 'https://github.com/AquilaXk/easysubway-backend/actions/runs/101');
  assert.equal(stored.measurement.workloads['metro-grid-v1'].modes.DEPART_AT.p99Nanos, 14_000);
  const withImage = cli(['aggregate', ...repeats, '--output', join(directory, 'with-image.json')], { ...BASE_ENV, ImageOS: 'ubuntu24', ImageVersion: '20260101.1' });
  assert.equal(JSON.parse(readFileSync(join(directory, 'with-image.json'), 'utf8')).context.runner.image, 'ubuntu24');
  assert.notEqual(cli(['aggregate', ...repeats.slice(0, 4), '--output', join(directory, 'two.json')]).status, 0, 'an even repeat count is rejected');
}));

test('compare warns on a regression against the baseline median without failing and writes the summary', () => withDirectory((directory) => {
  const current = writeJson(directory, 'current.json', result(measurement({ p99: 14_000 })));
  const previous = [1, 2, 3].map((index) => writeJson(directory, `p${index}.json`, previousRun(index, measurement({ p99: 10_000 }))));
  const summary = join(directory, 'summary.md');
  const compared = cli(['compare', '--current', current, '--previous-manifest', manifest(directory, previous), '--summary', summary]);
  assert.equal(compared.status, 0, compared.stderr);
  assert.match(compared.stdout, /^::warning title=Journey 엔진 벤치마크 추세::metro-grid-v1 DEPART_AT p99/m);
  const text = readFileSync(summary, 'utf8');
  assert.match(text, /\+40\.0%/);
  assert.match(text, /직전 성공 run 3개 중앙값/);
}));

test('a first run, or a manifest without usable runs, records the reason and raises no regression warning', () => withDirectory((directory) => {
  const current = writeJson(directory, 'current.json', result());
  const summary = join(directory, 'summary.md');
  const first = cli(['compare', '--current', current, '--previous-manifest', manifest(directory, [], { reason: '직전 성공 run이 없습니다(첫 실행).' }), '--summary', summary]);
  assert.equal(first.status, 0, first.stderr);
  assert.doesNotMatch(first.stdout, /::warning/);
  assert.match(readFileSync(summary, 'utf8'), /비교 대상 없음 — 직전 성공 run이 없습니다/);
}));

test('a damaged previous result is skipped with a warning on every path and the current result is still recorded', () => withDirectory((directory) => {
  const good = (index) => writeJson(directory, `good${index}.json`, previousRun(index, measurement({ p99: 10_000 })));
  const broken = {
    'missing runner': (value) => { delete value.context.runner; },
    'missing commit': (value) => { delete value.context.commitSha; },
    'bad run url': (value) => { value.context.runUrl = 'not a url'; },
    'missing context': (value) => { delete value.context; },
    'method removed': (value) => { delete value.measurement.method; },
  };
  const current = writeJson(directory, 'current.json', result(measurement({ p99: 14_000 })));
  for (const [name, change] of Object.entries(broken)) {
    const damaged = structuredClone(previousRun(9)); change(damaged);
    const summary = join(directory, `summary-${name.replaceAll(' ', '-')}.md`);
    const outcome = cli(['compare', '--current', current, '--previous-manifest', manifest(directory, [good(1), good(2), good(3), writeJson(directory, `bad-${name.replaceAll(' ', '-')}.json`, damaged)]), '--summary', summary]);
    assert.equal(outcome.status, 0, `${name}: ${outcome.stderr}`);
    assert.match(outcome.stdout, /^::warning title=Journey 엔진 벤치마크 추세::run [^:]+: 직전 결과를 읽지 못했습니다/m, name);
    assert.match(outcome.stdout, /p99/, `${name}: the three good runs still produce the comparison`);
  }
  const onlyBroken = structuredClone(previousRun(9)); delete onlyBroken.context.runner;
  const summary = join(directory, 'only-broken.md');
  const outcome = cli(['compare', '--current', current, '--previous-manifest', manifest(directory, [writeJson(directory, 'only-bad.json', onlyBroken)]), '--summary', summary]);
  assert.equal(outcome.status, 0, outcome.stderr);
  assert.match(readFileSync(summary, 'utf8'), /비교 대상 없음/);
  assert.equal(cli(['compare', '--current', writeJson(directory, 'bad-current.json', '{'), '--previous-manifest', manifest(directory, []), '--summary', summary]).status !== 0, true, 'the current result must be valid');
}));

test('compare rejects a missing or malformed manifest and unknown commands', () => withDirectory((directory) => {
  const current = writeJson(directory, 'current.json', result());
  const summary = join(directory, 'summary.md');
  assert.notEqual(cli(['compare', '--current', current, '--summary', summary]).status, 0);
  assert.notEqual(cli(['compare', '--current', current, '--previous-manifest', writeJson(directory, 'm.json', '{}'), '--summary', summary]).status, 0);
  assert.notEqual(cli(['unknown']).status, 0);
}));

test('fetch-previous writes a manifest from the gh results and annotates failures by cause', () => withDirectory((directory) => {
  const previousPath = writeJson(directory, 'seed.json', previousRun(1));
  const env = fakeGh(directory, { runIds: ['31', '30', '29'], resultPath: previousPath });
  const output = join(directory, 'previous', 'manifest.json');
  const args = ['fetch-previous', '--repo', 'AquilaXk/easysubway-backend', '--workflow', 'Journey Engine Benchmark Trend', '--dir', join(directory, 'previous'), '--output', output];
  const fetched = cli(args, env);
  assert.equal(fetched.status, 0, fetched.stderr);
  const stored = JSON.parse(readFileSync(output, 'utf8'));
  assert.equal(stored.results.length, 3);
  assert.deepEqual(stored.skipped, []);
  assert.doesNotMatch(fetched.stdout, /::warning/);

  const expired = cli(args, { ...env, FAKE_ARTIFACTS: '[{"name":"journey-engine-benchmark-x","expired":true}]' });
  assert.equal(expired.status, 0, expired.stderr);
  assert.match(expired.stdout, /^::notice title=Journey 엔진 벤치마크 추세::run 31/m);
  assert.doesNotMatch(expired.stdout, /::warning/);
  assert.match(JSON.parse(readFileSync(output, 'utf8')).reason, /사용할 수 있는 직전 결과가 없습니다/);

  const missing = cli(args, { ...env, FAKE_ARTIFACTS: '[]' });
  assert.match(missing.stdout, /^::warning title=Journey 엔진 벤치마크 추세::run 31/m);
}));
