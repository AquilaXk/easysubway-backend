import assert from 'node:assert/strict';
import test from 'node:test';
import { aggregateRepeats, buildResult, parseResult, readContext } from './journey-benchmark-result.mjs';
import { SHA, context, measurement, result } from './journey-benchmark-test-fixtures.mjs';

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
});

test('aggregation fails closed on an even repeat count, no repeats, malformed measurements, and drift in deterministic fields', () => {
  assert.throws(() => aggregateRepeats([]), /at least one/);
  assert.throws(() => aggregateRepeats([measurement(), measurement()]), /odd number/);
  assert.throws(() => aggregateRepeats([measurement(), measurement(), measurement({ bundle: 'd'.repeat(64) })]), /changed between repeats/);
  assert.throws(() => aggregateRepeats([measurement(), measurement(), measurement({ work: 8 })]), /changed between repeats/);
  assert.throws(() => aggregateRepeats([measurement(), measurement(), measurement({ method: { java: 'Other 21' } })]), /changed between repeats/);
  const missingField = measurement();
  delete missingField.workloads['metro-grid-v1'].modes.DEPART_AT.p99Nanos;
  assert.throws(() => aggregateRepeats([missingField]), /p99Nanos/);
  assert.throws(() => aggregateRepeats([measurement({ p50: 0 })]), /p50Nanos/);
  assert.throws(() => aggregateRepeats([{ ...measurement(), workloads: {} }]), /workloads/);
  assert.throws(() => aggregateRepeats([{ ...measurement(), schemaVersion: 2 }]), /schemaVersion/);
  assert.throws(() => aggregateRepeats([measurement({ method: { java: 'bad`java' } })]), /method\.java/);
  assert.throws(() => aggregateRepeats([measurement({ method: { warmupRounds: 0 } })]), /warmupRounds/);
});

test('the result records commit, runner, repeat count and the measurement without the policy replay detail', () => {
  const built = result(aggregateRepeats([measurement()]));
  assert.equal(built.schemaVersion, 1);
  assert.equal(built.repeats, 3);
  assert.equal(built.context.commitSha, SHA);
  assert.equal(built.measurement.workloads['metro-grid-v1'].candidatePolicyReplay, undefined);
  assert.throws(() => buildResult({ measurement: measurement(), repeats: 3, context: context({ commitSha: 'abc' }) }), /commitSha/);
  assert.throws(() => buildResult({ measurement: measurement(), repeats: 2, context: context() }), /repeats/);
});

const ENV = {
  GITHUB_SHA: SHA, GITHUB_REF_NAME: 'main', GITHUB_EVENT_NAME: 'schedule', GITHUB_RUN_ID: '101', GITHUB_RUN_ATTEMPT: '1',
  GITHUB_SERVER_URL: 'https://github.com', GITHUB_REPOSITORY: 'AquilaXk/easysubway-backend', RUNNER_NAME: 'GitHub Actions 7', RUNNER_OS: 'Linux', RUNNER_ARCH: 'X64',
  ImageOS: 'ubuntu24', ImageVersion: '20260101.1',
};
const runtime = { cpuList: [{ model: 'AMD EPYC 7763' }, { model: 'AMD EPYC 7763' }], memoryBytes: 8_000_000_000, now: new Date('2026-10-17T00:00:00Z') };

test('context reads the run and runner from the environment, with image metadata optional and recorded as null when absent', () => {
  const full = readContext(ENV, runtime);
  assert.equal(full.runUrl, 'https://github.com/AquilaXk/easysubway-backend/actions/runs/101');
  assert.equal(full.runner.image, 'ubuntu24');
  assert.equal(full.runner.cpuCount, 2);
  const { ImageOS: _os, ImageVersion: _version, ...withoutImage } = ENV;
  const bare = readContext(withoutImage, runtime);
  assert.equal(bare.runner.image, null);
  assert.equal(bare.runner.imageVersion, null);
  assert.equal(readContext({ ...ENV, ImageOS: '', ImageVersion: '' }, runtime).runner.image, null);
  assert.doesNotThrow(() => parseResult(JSON.stringify(buildResult({ measurement: measurement(), repeats: 3, context: bare })), 'bare'));
  const { GITHUB_SHA: _sha, ...withoutSha } = ENV;
  assert.throws(() => readContext(withoutSha, runtime), /GITHUB_SHA/);
  assert.throws(() => readContext(ENV, { ...runtime, cpuList: [] }), /cpu model/);
});

test('descriptive runner text is reduced to the safe character set at the source so the current result still validates', () => {
  const odd = readContext({ ...ENV, RUNNER_NAME: 'runner `x` | [l](http://e)' }, { ...runtime, cpuList: [{ model: 'Intel(R) Xeon(R) CPU @ 2.60GHz ®' }] });
  assert.match(odd.runner.cpuModel, /^Intel\(R\) Xeon\(R\) CPU @ 2\.60GHz _$/);
  assert.doesNotMatch(odd.runner.name, /[`|\[\]]/);
  assert.doesNotThrow(() => parseResult(JSON.stringify(buildResult({ measurement: measurement(), repeats: 3, context: odd })), 'odd'));
});

const mutate = (change) => { const value = structuredClone(result()); change(value); return JSON.stringify(value); };
const BROKEN = {
  'context removed': (value) => { delete value.context; },
  'runner removed': (value) => { delete value.context.runner; },
  'commit sha malformed': (value) => { value.context.commitSha = 'abc'; },
  'run id not numeric': (value) => { value.context.runId = '1; rm'; },
  'run attempt missing': (value) => { delete value.context.runAttempt; },
  'run url scheme': (value) => { value.context.runUrl = 'javascript:alert(1)'; },
  'run url markdown injection': (value) => { value.context.runUrl = 'https://github.com/a/b/actions/runs/1) [x](http://evil'; },
  'run url other path': (value) => { value.context.runUrl = 'https://github.com/a/b/issues/1'; },
  'measuredAt malformed': (value) => { value.context.measuredAt = 'yesterday'; },
  'ref with newline': (value) => { value.context.ref = 'main\n# injected'; },
  'cpu count a string': (value) => { value.context.runner.cpuCount = '4'; },
  'cpu model markup': (value) => { value.context.runner.cpuModel = 'x`|<script>'; },
  'image undefined': (value) => { delete value.context.runner.image; },
  'image number': (value) => { value.context.runner.imageVersion = 20260101; },
  'workload id markup': (value) => { value.measurement.workloads['a|b'] = value.measurement.workloads['metro-grid-v1']; },
  'query set id markup': (value) => { value.measurement.workloads['metro-grid-v1'].querySet.id = 'x`y'; },
  'method removed': (value) => { delete value.measurement.method; },
  'java not a string': (value) => { value.measurement.method.java = 21; },
  'profile limit not positive': (value) => { value.measurement.method.profileLimits.maxEstimatedWork = 0; },
  'repeats even': (value) => { value.repeats = 2; },
  'schema version': (value) => { value.schemaVersion = 2; },
};
for (const [name, change] of Object.entries(BROKEN)) {
  test(`a malformed result is rejected at parse time: ${name}`, () => {
    assert.throws(() => parseResult(mutate(change), 'previous result'), /journey benchmark trend: /);
  });
}

test('parsing rejects non-JSON text and keeps a valid result unchanged', () => {
  assert.throws(() => parseResult('{', 'previous result'), /not valid JSON/);
  const text = JSON.stringify(result());
  assert.deepEqual(parseResult(text, 'x'), JSON.parse(text));
});
