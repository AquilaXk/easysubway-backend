import assert from 'node:assert/strict';
import test from 'node:test';
import {
  FACILITY_MODES,
  FACILITY_MODE_LABELS,
  FACILITY_OUTAGE_CODE,
  LOAD_PLAN,
  VIOLATIONS,
  assertLoopbackOrigin,
  buildCorpus,
  distillK6Summary,
  expectedSwapBundle,
  judgeResponse,
  metricName,
  outageExpectation,
  phaseWindowContains,
  requestBody,
  requestIdFor,
  summarizeResponse,
  swapFlipBundle,
} from './journey-load-contract.mjs';

const SHA_A = 'a'.repeat(64);
const SHA_B = 'b'.repeat(64);
const BUNDLES = { a: SHA_A, b: SHA_B };
const FIXTURE = { serviceDate: '2026-10-06', stations: Array.from({ length: 40 }, (_, index) => `station-${index}`) };
const JOURNEY = { journeyId: 'j', plannedDepartureTime: '2026-10-06T00:00:00Z', legs: [{ type: 'RIDE', tripId: 't1' }] };

const success = ({ sha = SHA_A, timetable = SHA_A, facilityStatus = 'APPLIED', journeys = [JOURNEY], requestId = 'r1' } = {}) => JSON.stringify({
  contractVersion: 'JOURNEY_SEARCH_V3', requestId, queryId: requestId, calculatedAt: '2026-10-05T15:00:00Z',
  sourceIdentity: { routeBundleId: `load-bundle-${sha[0]}`, routeBundleSha256: sha, timetableSnapshotId: timetable, accessibilitySnapshotId: '4'.repeat(64), realtimeSnapshotId: null },
  journeys, stairFreeAlternative: { status: 'NONE', facilityStatus },
});
const failure = (code, requestId = 'r1') => JSON.stringify({ contractVersion: 'JOURNEY_ERROR_V1', requestId, code, retryable: false, occurredAt: '2026-10-17T00:00:00Z' });
const entry = (constraint = 'NONE') => ({ index: 0, origin: 'station-1', destination: 'station-2', mobility: 'STEP_FREE', constraint, requestedAt: '2026-10-05T23:00:00Z' });
const expectedFor = (constraint = 'NONE', { a = summarizeResponse(200, success()), b = summarizeResponse(200, success({ sha: SHA_B, timetable: SHA_B, journeys: [{ ...JOURNEY, plannedDepartureTime: '2026-10-06T00:01:00Z' }] })) } = {}) => ({ entry: entry(constraint), expected: { a, b } });
const judge = (phase, actual, constraint = 'NONE', overrides = {}, extra = {}) => {
  const { entry: requestEntry, expected } = expectedFor(constraint, overrides);
  return judgeResponse({ phase, entry: requestEntry, actual, expected, bundles: BUNDLES, facilityMode: FACILITY_MODES.REQUIRED, ...extra });
};

test('request ids are ULID-shaped, unique per sequence, and reject invalid sequences', () => {
  const ids = [0, 1, 31, 32, 1_000_000].map(requestIdFor);
  for (const id of ids) assert.match(id, /^[0-7][0-9A-HJKMNP-TV-Z]{25}$/);
  assert.equal(new Set(ids).size, ids.length);
  for (const invalid of [-1, 1.5, Number.NaN, '3', 2 ** 53]) assert.throws(() => requestIdFor(invalid), /sequence/);
});

test('the corpus is deterministic, covers the three mobility mixes evenly, and stays inside the service day', () => {
  const corpus = buildCorpus(FIXTURE, LOAD_PLAN);
  assert.equal(corpus.length, LOAD_PLAN.corpusSize);
  assert.deepEqual(corpus, buildCorpus(FIXTURE, LOAD_PLAN));
  const mixes = new Map();
  for (const item of corpus) {
    assert.notEqual(item.origin, item.destination);
    assert.ok(FIXTURE.stations.includes(item.origin) && FIXTURE.stations.includes(item.destination));
    const mix = `${item.mobility}/${item.constraint}`;
    mixes.set(mix, (mixes.get(mix) ?? 0) + 1);
    const seconds = (Date.parse(item.requestedAt) - Date.parse('2026-10-05T15:00:00Z')) / 1000;
    assert.ok(seconds >= 23_400 && seconds < 23_400 + 55_800, `${item.requestedAt} is outside 06:30-22:00 KST`);
  }
  assert.deepEqual([...mixes.keys()].sort(), ['STANDARD/NONE', 'STEP_FREE/NONE', 'STEP_FREE/REQUIRE_STEP_FREE']);
  assert.ok([...mixes.values()].every((count) => count === LOAD_PLAN.corpusSize / 3));
  assert.notDeepEqual(corpus, buildCorpus(FIXTURE, { ...LOAD_PLAN, corpusSeed: LOAD_PLAN.corpusSeed + 1 }));
});

test('the request body carries exactly the fields the search endpoint accepts', () => {
  const body = JSON.parse(requestBody(entry('REQUIRE_STEP_FREE'), requestIdFor(7)));
  assert.deepEqual(Object.keys(body).sort(), ['alternativeCount', 'constraintMode', 'departure', 'destinationStationId', 'maxTransfers', 'mobilityProfile', 'originStationId', 'requestId', 'timePolicy', 'walkingPace']);
  assert.deepEqual(body.departure, { mode: 'SCHEDULED', requestedAt: '2026-10-05T23:00:00Z' });
  assert.equal(body.timePolicy, 'TIMETABLE_REQUIRED');
  assert.equal(body.constraintMode, 'REQUIRE_STEP_FREE');
  assert.equal(body.requestId, requestIdFor(7));
});

test('summaries ignore per-request fields and keep identity, facility status and journeys', () => {
  const first = summarizeResponse(200, success({ requestId: 'x1' }));
  const second = summarizeResponse(200, success({ requestId: 'x2' }));
  assert.deepEqual(first, second, 'requestId, queryId and calculatedAt must not change the summary');
  assert.equal(first.bundleSha, SHA_A);
  assert.equal(first.facilityStatus, 'APPLIED');
  assert.notEqual(first.fingerprint, summarizeResponse(200, success({ journeys: [] })).fingerprint);
  assert.notEqual(first.fingerprint, summarizeResponse(200, success({ timetable: SHA_B })).fingerprint);
  assert.equal(first.fingerprint, summarizeResponse(200, success({ facilityStatus: 'UNOBSERVED' })).fingerprint, 'facility status is compared separately');
});

test('error responses summarize to status and machine code; unreadable bodies are marked', () => {
  assert.deepEqual(summarizeResponse(422, failure('ROUTE_NOT_FOUND')), { status: 422, code: 'ROUTE_NOT_FOUND', bundleSha: null, facilityStatus: null, fingerprint: '' });
  assert.equal(summarizeResponse(503, failure(FACILITY_OUTAGE_CODE)).code, FACILITY_OUTAGE_CODE);
  assert.equal(summarizeResponse(502, '<html>bad gateway</html>').code, 'UNREADABLE_BODY');
  assert.equal(summarizeResponse(200, 'not json').code, 'UNREADABLE_BODY');
  assert.equal(summarizeResponse(0, null).code, 'UNREADABLE_BODY');
});

test('outage expectation is an explicit 503 for step-free-required requests and a disclosed UNOBSERVED answer otherwise', () => {
  const healthy = summarizeResponse(200, success());
  assert.deepEqual(outageExpectation(healthy, entry('REQUIRE_STEP_FREE'), FACILITY_MODES.REQUIRED), { status: 503, code: FACILITY_OUTAGE_CODE, bundleSha: null, facilityStatus: null, fingerprint: '' });
  assert.deepEqual(outageExpectation(healthy, entry('NONE'), FACILITY_MODES.REQUIRED), { ...healthy, facilityStatus: 'UNOBSERVED' });
  const noRoute = summarizeResponse(422, failure('ROUTE_NOT_FOUND'));
  assert.deepEqual(outageExpectation(noRoute, entry('NONE'), FACILITY_MODES.REQUIRED), noRoute);
});

test('steady and recovered phases accept only the first bundle answer', () => {
  for (const phase of ['steady', 'recovered']) {
    assert.deepEqual(judge(phase, summarizeResponse(200, success())), { ok: true, bundle: 'a', explicitFailure: false, disclosedUnobserved: false });
    assert.equal(judge(phase, summarizeResponse(200, success({ sha: SHA_B, timetable: SHA_B, journeys: [{ ...JOURNEY, plannedDepartureTime: '2026-10-06T00:01:00Z' }] }))).violation, VIOLATIONS.WRONG_BUNDLE);
  }
});

test('the swap phase accepts either bundle answer and attributes it', () => {
  const second = summarizeResponse(200, success({ sha: SHA_B, timetable: SHA_B, journeys: [{ ...JOURNEY, plannedDepartureTime: '2026-10-06T00:01:00Z' }] }));
  assert.equal(judge('swap', summarizeResponse(200, success())).bundle, 'a');
  assert.equal(judge('swap', second).bundle, 'b');
});

test('a response mixing one bundle identity with the other bundle content is a mixed response', () => {
  const identityB = success({ sha: SHA_B, timetable: SHA_B });
  assert.equal(judge('swap', summarizeResponse(200, identityB)).violation, VIOLATIONS.MIXED_RESPONSE);
  const timetableMismatch = success({ sha: SHA_A, timetable: SHA_B });
  assert.equal(judge('swap', summarizeResponse(200, timetableMismatch)).violation, VIOLATIONS.MIXED_RESPONSE);
});

test('during the outage a step-free-required request must fail explicitly, never succeed or report no route', () => {
  const explicit = summarizeResponse(503, failure(FACILITY_OUTAGE_CODE));
  assert.deepEqual(judge('outage', explicit, 'REQUIRE_STEP_FREE'), { ok: true, bundle: null, explicitFailure: true, disclosedUnobserved: false });
  assert.equal(judge('outage', summarizeResponse(200, success()), 'REQUIRE_STEP_FREE').violation, VIOLATIONS.MASKED_OUTAGE);
  assert.equal(judge('outage', summarizeResponse(422, failure('ROUTE_NOT_FOUND')), 'REQUIRE_STEP_FREE').violation, VIOLATIONS.MASKED_OUTAGE);
});

test('during the outage ordinary requests must disclose the missing facility status', () => {
  const disclosed = summarizeResponse(200, success({ facilityStatus: 'UNOBSERVED' }));
  assert.deepEqual(judge('outage', disclosed), { ok: true, bundle: 'a', explicitFailure: false, disclosedUnobserved: true });
  assert.equal(judge('outage', summarizeResponse(200, success({ facilityStatus: 'APPLIED' }))).violation, VIOLATIONS.MASKED_OUTAGE);
});

test('unexpected failures and unknown answers are classified separately', () => {
  assert.equal(judge('steady', summarizeResponse(500, failure('INTERNAL'))).violation, VIOLATIONS.UNEXPECTED_FAILURE);
  assert.equal(judge('steady', summarizeResponse(503, failure(FACILITY_OUTAGE_CODE))).violation, VIOLATIONS.UNEXPECTED_FAILURE, 'an outage answer without an outage is a failure');
  assert.equal(judge('steady', summarizeResponse(422, failure('ROUTE_NOT_FOUND'))).violation, VIOLATIONS.UNEXPECTED_RESPONSE);
  assert.equal(judge('steady', summarizeResponse(0, null)).violation, VIOLATIONS.UNEXPECTED_FAILURE, 'a transport failure is a failure');
});

test('a successful answer from an unknown bundle is an unexpected response, never classified through a bundle that does not exist', () => {
  const unknown = (facilityStatus) => summarizeResponse(200, success({ sha: 'c'.repeat(64), timetable: 'c'.repeat(64), facilityStatus }));
  for (const phase of ['steady', 'swap', 'recovered']) assert.equal(judge(phase, unknown('APPLIED')).violation, VIOLATIONS.UNEXPECTED_RESPONSE, phase);
  assert.equal(judge('outage', unknown('UNOBSERVED')).violation, VIOLATIONS.UNEXPECTED_RESPONSE, 'outage');
});

test('in the strict phases the second bundle identity with the first bundle content is a mixed response, and its exact answer is the wrong bundle', () => {
  const secondIdentityFirstContent = summarizeResponse(200, success({ sha: SHA_B, timetable: SHA_B }));
  for (const phase of ['steady', 'recovered']) assert.equal(judge(phase, secondIdentityFirstContent).violation, VIOLATIONS.MIXED_RESPONSE, phase);
  const exactSecondBundle = summarizeResponse(200, success({ sha: SHA_B, timetable: SHA_B, journeys: [{ ...JOURNEY, plannedDepartureTime: '2026-10-06T00:01:00Z' }] }));
  for (const phase of ['steady', 'recovered']) assert.equal(judge(phase, exactSecondBundle).violation, VIOLATIONS.WRONG_BUNDLE, phase);
});

test('a no-route answer that both bundles agree on is accepted in every non-outage phase', () => {
  const noRoute = summarizeResponse(422, failure('ROUTE_NOT_FOUND'));
  for (const phase of ['steady', 'swap', 'recovered']) {
    assert.equal(judge(phase, noRoute, 'NONE', { a: noRoute, b: noRoute }).ok, true);
  }
});

const counters = (values) => Object.fromEntries(Object.entries(values).map(([name, count]) => [name, { type: 'counter', values: { count } }]));
const trend = (values) => ({ type: 'trend', values });

test('distillation reads the per-phase counters and latency trends from the k6 summary', () => {
  const metrics = {
    ...counters({
      [metricName('steady', 'requests')]: 1000, [metricName('steady', 'status5xx')]: 0, [metricName('steady', 'status429')]: 0,
      [metricName('steady', 'lateIterations')]: 4, [metricName('steady', 'bundleA')]: 1000, [metricName('steady', 'bundleB')]: 0, [metricName('steady', 'explicitFailures')]: 0, [metricName('steady', 'disclosedUnobserved')]: 0,
      [metricName('steady', VIOLATIONS.MIXED_RESPONSE)]: 0, [metricName('steady', VIOLATIONS.MASKED_OUTAGE)]: 0, [metricName('steady', VIOLATIONS.WRONG_BUNDLE)]: 0,
      [metricName('steady', VIOLATIONS.UNEXPECTED_FAILURE)]: 0, [metricName('steady', VIOLATIONS.UNEXPECTED_RESPONSE)]: 2,
      control_calls: 12, control_failures: 1, dropped_iterations: 3,
    }),
    [metricName('steady', 'latencyMs')]: trend({ 'p(50)': 5, 'p(95)': 12, 'p(99)': 20, max: 30 }),
  };
  const distilled = distillK6Summary({ metrics }, LOAD_PLAN, FACILITY_MODES.DEFAULT);
  assert.equal(distilled.schemaVersion, 1);
  assert.equal(distilled.facilityMode, FACILITY_MODES.DEFAULT);
  assert.equal(distilled.droppedIterations, 3);
  assert.deepEqual(distilled.controls, { calls: 12, failures: 1 });
  assert.deepEqual(Object.keys(distilled.phases), LOAD_PLAN.phases.map(({ name }) => name));
  assert.deepEqual(distilled.phases.steady.latencyMs, { p50: 5, p95: 12, p99: 20, max: 30 });
  assert.equal(distilled.phases.steady.requests, 1000);
  assert.equal(distilled.phases.steady.lateIterations, 4);
  assert.equal(distilled.phases.steady.violations[VIOLATIONS.UNEXPECTED_RESPONSE], 2);
  assert.equal(distilled.phases.swap.requests, 0, 'a phase that never ran reports zero requests');
  assert.equal(distilled.phases.swap.latencyMs, null);
});

test('metric names stay valid k6 identifiers', () => {
  for (const { name } of LOAD_PLAN.phases) for (const kind of ['requests', 'latencyMs', ...Object.values(VIOLATIONS)]) {
    assert.match(metricName(name, kind), /^[A-Za-z_][A-Za-z0-9_]{0,127}$/);
  }
});

test('the plan lays phases out in order, keeps every injection a guard away from the strict phases, and ends the swap on the first bundle', () => {
  const [steady, swap, outage, recovered] = LOAD_PLAN.phases;
  const { guardSeconds, settleSeconds, flipIntervalSeconds, controls } = LOAD_PLAN;
  const end = ({ startSeconds, durationSeconds }) => startSeconds + durationSeconds;
  assert.deepEqual(LOAD_PLAN.phases.map(({ name }) => name), ['steady', 'swap', 'outage', 'recovered']);
  assert.equal(steady.startSeconds, 0);
  assert.equal(swap.startSeconds, end(steady));
  assert.equal(controls.swap.flips % 2, 0, 'an even number of flips returns to the first bundle');
  const lastFlip = controls.swap.startSeconds + (controls.swap.flips - 1) * flipIntervalSeconds;
  assert.ok(controls.swap.startSeconds >= swap.startSeconds + guardSeconds, 'the first flip must not race the end of the steady phase');
  assert.ok(lastFlip < end(swap), 'the last flip happens while the swap phase still loads');
  assert.ok(controls.outageOn.startSeconds >= end(swap) + guardSeconds, 'the outage is injected after the last swap request');
  assert.ok(outage.startSeconds >= controls.outageOn.startSeconds + settleSeconds, 'outage load starts after the outage is injected');
  assert.ok(controls.outageOff.startSeconds >= end(outage) + guardSeconds, 'recovery is injected after the last outage request');
  assert.ok(recovered.startSeconds >= controls.outageOff.startSeconds + settleSeconds, 'recovered load starts after the recovery');
});

test('an iteration belongs to its phase while it starts inside the window, including the arrival the scheduler fires at the very end', () => {
  const { startSeconds, durationSeconds } = LOAD_PLAN.phases.find(({ name }) => name === 'swap');
  const lastAllowed = startSeconds + durationSeconds + LOAD_PLAN.windowToleranceSeconds;
  assert.ok(LOAD_PLAN.windowToleranceSeconds < LOAD_PLAN.guardSeconds, 'the next injection must come after the tolerated window');
  assert.equal(phaseWindowContains(LOAD_PLAN, 'swap', startSeconds), true);
  assert.equal(phaseWindowContains(LOAD_PLAN, 'swap', startSeconds + durationSeconds), true, 'k6 fires one arrival exactly at the end');
  assert.equal(phaseWindowContains(LOAD_PLAN, 'swap', lastAllowed - 0.001), true);
  assert.equal(phaseWindowContains(LOAD_PLAN, 'swap', lastAllowed), false, 'the tolerated end is exclusive');
  assert.equal(phaseWindowContains(LOAD_PLAN, 'swap', startSeconds - 0.001), false);
  assert.throws(() => phaseWindowContains(LOAD_PLAN, 'unknown', 0), /phase/);
});

const secondBundleAnswer = () => summarizeResponse(200, success({ sha: SHA_B, timetable: SHA_B, journeys: [{ ...JOURNEY, plannedDepartureTime: '2026-10-06T00:01:00Z' }] }));
const DEFAULT_MODE = { facilityMode: FACILITY_MODES.DEFAULT };

test('with the production default every request, step-free-required ones included, must keep its answer and disclose the missing facility status', () => {
  const disclosed = summarizeResponse(200, success({ facilityStatus: 'UNOBSERVED' }));
  assert.deepEqual(judge('outage', disclosed, 'REQUIRE_STEP_FREE', {}, DEFAULT_MODE), { ok: true, bundle: 'a', explicitFailure: false, disclosedUnobserved: true });
  assert.deepEqual(judge('outage', disclosed, 'NONE', {}, DEFAULT_MODE), { ok: true, bundle: 'a', explicitFailure: false, disclosedUnobserved: true });
  const noRoute = summarizeResponse(422, failure('ROUTE_NOT_FOUND'));
  assert.equal(judge('outage', noRoute, 'REQUIRE_STEP_FREE', { a: noRoute, b: noRoute }, DEFAULT_MODE).ok, true, 'an answer that does not depend on facility status is unchanged');
  assert.deepEqual(outageExpectation(summarizeResponse(200, success()), entry('REQUIRE_STEP_FREE'), FACILITY_MODES.DEFAULT), disclosed);
});

test('with the production default a response that looks as if facility status was observed during the outage is a masked outage', () => {
  const applied = summarizeResponse(200, success({ facilityStatus: 'APPLIED' }));
  for (const constraint of ['NONE', 'REQUIRE_STEP_FREE']) assert.equal(judge('outage', applied, constraint, {}, DEFAULT_MODE).violation, VIOLATIONS.MASKED_OUTAGE, constraint);
});

test('with the production default an explicit outage failure is not an allowed answer', () => {
  const explicit = summarizeResponse(503, failure(FACILITY_OUTAGE_CODE));
  assert.equal(judge('outage', explicit, 'REQUIRE_STEP_FREE', {}, DEFAULT_MODE).violation, VIOLATIONS.UNEXPECTED_FAILURE);
});

test('the facility modes are the two configurations the scenarios run, each named after its production setting', () => {
  assert.deepEqual(Object.values(FACILITY_MODES), ['default', 'required']);
  assert.match(FACILITY_MODE_LABELS[FACILITY_MODES.DEFAULT], /required=false/);
  assert.match(FACILITY_MODE_LABELS[FACILITY_MODES.REQUIRED], /required=true/);
});

test('with the production default a step-free-required answer with mixed content is a mixed response, not a masked outage', () => {
  const secondIdentityFirstContent = summarizeResponse(200, success({ sha: SHA_B, timetable: SHA_B, facilityStatus: 'UNOBSERVED' }));
  assert.equal(judge('outage', secondIdentityFirstContent, 'REQUIRE_STEP_FREE', {}, DEFAULT_MODE).violation, VIOLATIONS.MIXED_RESPONSE);
});

test('only a loopback origin with an explicit port is accepted as the load target', () => {
  for (const origin of ['http://127.0.0.1:8080', 'http://127.0.0.1:1', 'http://127.0.0.1:65535']) assert.doesNotThrow(() => assertLoopbackOrigin(origin), origin);
  const hostile = ['prefixhttp://127.0.0.1:8080', 'https://127.0.0.1:8080', 'http://localhost:8080', 'http://127.0.0.2:8080', 'http://user@127.0.0.1:8080', 'http://127.0.0.1:8080/path', 'http://127.0.0.1', 'http://127.0.0.1:0', 'http://127.0.0.1:99999', 'https://easysubway-api.aquilaxk.site', '', undefined, null];
  for (const origin of hostile) assert.throws(() => assertLoopbackOrigin(origin), /local load server origin/, String(origin));
});

test('after an activation has had time to land, the swap phase accepts only the bundle that flip activated', () => {
  const { startSeconds, flips } = LOAD_PLAN.controls.swap;
  const { activationLagSeconds, flipJitterSeconds, flipIntervalSeconds } = LOAD_PLAN;
  const swap = LOAD_PLAN.phases.find(({ name }) => name === 'swap');
  const flipAt = (index) => startSeconds + index * flipIntervalSeconds;
  assert.equal(expectedSwapBundle(LOAD_PLAN, swap.startSeconds), 'a', 'before the first flip only the first bundle can answer');
  assert.equal(expectedSwapBundle(LOAD_PLAN, flipAt(0) - flipJitterSeconds), null, 'just before a flip is not decided');
  for (let flip = 0; flip < flips; flip += 1) {
    const expected = swapFlipBundle(flip);
    assert.equal(expectedSwapBundle(LOAD_PLAN, flipAt(flip) + activationLagSeconds), expected, `flip ${flip} start of the strict window`);
    const strictEnd = flip + 1 < flips ? flipAt(flip + 1) - flipJitterSeconds : swap.startSeconds + swap.durationSeconds + LOAD_PLAN.windowToleranceSeconds;
    assert.equal(expectedSwapBundle(LOAD_PLAN, strictEnd - 0.001), expected, `flip ${flip} end of the strict window`);
    assert.equal(expectedSwapBundle(LOAD_PLAN, strictEnd), null, `flip ${flip} the strict window ends before the next flip can land`);
    assert.equal(expectedSwapBundle(LOAD_PLAN, flipAt(flip) + activationLagSeconds / 2), null, `flip ${flip} while the activation may still be landing`);
  }
  assert.equal(swapFlipBundle(flips - 1), 'a', 'the last flip returns to the first bundle');
  assert.ok(activationLagSeconds + flipJitterSeconds < flipIntervalSeconds, 'every flip must leave a strict window');
});

test('a swap answer after the activation landed must come from the activated bundle, a stale one is the wrong bundle', () => {
  const second = secondBundleAnswer();
  assert.equal(judge('swap', second, 'NONE', {}, { expectedBundle: 'b' }).bundle, 'b');
  assert.equal(judge('swap', summarizeResponse(200, success()), 'NONE', {}, { expectedBundle: 'a' }).bundle, 'a');
  assert.equal(judge('swap', summarizeResponse(200, success()), 'NONE', {}, { expectedBundle: 'b' }).violation, VIOLATIONS.WRONG_BUNDLE);
  assert.equal(judge('swap', second, 'NONE', {}, { expectedBundle: 'a' }).violation, VIOLATIONS.WRONG_BUNDLE);
  assert.equal(judge('swap', summarizeResponse(200, success()), 'NONE', {}, { expectedBundle: null }).ok, true, 'undecided instants accept either bundle');
});
