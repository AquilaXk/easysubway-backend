// 경로 검색 부하·장애 시나리오 k6 스크립트 (backend #520).
//
// 대상은 journey-load-run.mjs가 띄운 로컬 서버(JourneyLoadServer)뿐이다. 운영 주소는 받지 않는다.
// 단계(steady → swap → outage → recovered)는 constant-arrival-rate 시나리오이고, 장애 주입은 단계 사이의 제어 호출이 한다.
// 응답 판정은 journey-load-contract.mjs, 합격 판정은 journey-load-verdict.mjs가 한다. 이 스크립트는 지표만 모은다.
// 참고: https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import {
  FACILITY_MODES,
  FIRST_BUNDLE,
  K6_OUTPUT_FILES,
  LOAD_PLAN,
  SECOND_BUNDLE,
  assertLoopbackOrigin,
  buildCorpus,
  distillK6Summary,
  droppedIterationsThresholds,
  expectedSwapBundle,
  judgeResponse,
  metricName,
  phaseMetricSpec,
  phaseWindowContains,
  requestBody,
  requestIdFor,
  summarizeResponse,
  swapFlipBundle,
  swapStrictWindows,
  swapWindowIndexAt,
  swapWindowMetricName,
} from './journey-load-contract.mjs';

const baseUrl = __ENV.JOURNEY_LOAD_BASE_URL;
assertLoopbackOrigin(__ENV.JOURNEY_LOAD_BASE_URL);
const facilityMode = __ENV.JOURNEY_LOAD_FACILITY_MODE;
if (!Object.values(FACILITY_MODES).includes(facilityMode)) throw new Error(`JOURNEY_LOAD_FACILITY_MODE must be one of ${Object.values(FACILITY_MODES).join(', ')}`);
const outputDirectory = __ENV.JOURNEY_LOAD_OUTPUT_DIR;
if (typeof outputDirectory !== 'string' || !outputDirectory.startsWith('/')) throw new Error('JOURNEY_LOAD_OUTPUT_DIR is required and must be absolute');

const BUNDLE_LABELS = [FIRST_BUNDLE, SECOND_BUNDLE];
const SEARCH_PATH = '/api/v3/journeys/search';
const PRE_ALLOCATED_VUS = 20;
const MAX_VUS = 100;
const SETUP_TIMEOUT = '120s';
const MILLIS_PER_SECOND = 1000;
const REQUESTS_PER_PHASE_ID_BLOCK = 1_000_000;
/** 단계마다 요청 ID 구간을 따로 쓰고, setup 요청은 그 뒤 구간을 써서 어느 요청도 ID가 겹치지 않는다. */
const SETUP_ID_BASE = LOAD_PLAN.phases.length * REQUESTS_PER_PHASE_ID_BLOCK;
/** 위반 응답은 개수만으로는 원인을 알 수 없어 가상 사용자마다 처음 몇 건만 기대값과 함께 남긴다. */
const MAX_LOGGED_VIOLATIONS_PER_VU = 3;
let loggedViolations = 0;
const JSON_HEADERS = { Authorization: 'Bearer load-session', 'Content-Type': 'application/json' };

const phaseScenario = ({ name, startSeconds, durationSeconds }) => [name, {
  executor: 'constant-arrival-rate',
  rate: LOAD_PLAN.requestsPerSecond,
  timeUnit: '1s',
  duration: `${durationSeconds}s`,
  startTime: `${startSeconds}s`,
  preAllocatedVUs: PRE_ALLOCATED_VUS,
  maxVUs: MAX_VUS,
  exec: 'searchPhase',
  env: { PHASE: name },
}];
const controlScenario = (execName, startSeconds) => ({ executor: 'shared-iterations', vus: 1, iterations: 1, startTime: `${startSeconds}s`, exec: execName, maxDuration: '60s' });

export const options = {
  setupTimeout: SETUP_TIMEOUT,
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: droppedIterationsThresholds(LOAD_PLAN),
  scenarios: {
    ...Object.fromEntries(LOAD_PLAN.phases.map(phaseScenario)),
    swap_control: controlScenario('swapBundles', LOAD_PLAN.controls.swap.startSeconds),
    outage_on: controlScenario('outageOn', LOAD_PLAN.controls.outageOn.startSeconds),
    outage_off: controlScenario('outageOff', LOAD_PLAN.controls.outageOff.startSeconds),
  },
};

const phaseMetrics = Object.fromEntries(LOAD_PLAN.phases.map(({ name }) => [name, {
  counters: Object.fromEntries(phaseMetricSpec.counters.map((kind) => [kind, new Counter(metricName(name, kind))])),
  latency: new Trend(metricName(name, phaseMetricSpec.latency), true),
}]));
const swapWindowCounters = swapStrictWindows(LOAD_PLAN).map((_, index) => new Counter(swapWindowMetricName(index)));
const controlCalls = new Counter('control_calls');
const controlFailures = new Counter('control_failures');

function post(path, body = null) {
  return http.post(`${baseUrl}${path}`, body, { headers: JSON_HEADERS });
}

function search(entry, sequence) {
  const response = post(SEARCH_PATH, requestBody(entry, requestIdFor(sequence)));
  return { response, summary: summarizeResponse(response.status, response.body) };
}

/** 두 팩과 정상 시설 상태에서 질의 목록 전체를 한 번씩 보내 "단독 응답"의 기준을 만든다. 서버가 건강하지 않으면 시작하지 않는다. */
export function setup() {
  const fixtureResponse = http.get(`${baseUrl}/__load/fixture`);
  if (fixtureResponse.status !== 200) throw new Error(`fixture request failed: ${fixtureResponse.status}`);
  const fixture = fixtureResponse.json();
  const corpus = buildCorpus(fixture, LOAD_PLAN);
  const expected = corpus.map(() => ({}));
  requireOk(post('/__load/facility/up'), 'facility up');
  BUNDLE_LABELS.forEach((label, labelIndex) => {
    requireOk(post(`/__load/bundle/${label}`), `activate bundle ${label}`);
    corpus.forEach((entry, index) => {
      const { summary } = search(entry, SETUP_ID_BASE + labelIndex * corpus.length + index);
      if (summary.status === 0 || summary.status >= 500) throw new Error(`baseline request failed with ${summary.status} ${summary.code} on bundle ${label}`);
      expected[index][label] = summary;
    });
  });
  const answersDiffer = expected.some(({ a, b }) => a.status === 200 && b.status === 200 && a.fingerprint !== b.fingerprint);
  if (!answersDiffer) throw new Error('the two bundles answer identically, so a replacement could not be told apart');
  requireOk(post(`/__load/bundle/${FIRST_BUNDLE}`), 'activate the first bundle');
  return { corpus, expected, bundles: fixture.bundles };
}

function requireOk(response, what) {
  if (response.status !== 200) throw new Error(`${what} failed: ${response.status}`);
}

/** 현재 시나리오가 시작한 뒤 흐른 시간. exec.instance.currentTestRunDuration은 setup 시간을 포함해 계획 시각과 어긋난다. */
const millisIntoScenario = () => Date.now() - exec.scenario.startTime;

export function searchPhase(data) {
  const phase = __ENV.PHASE;
  const phaseIndex = LOAD_PLAN.phases.findIndex(({ name }) => name === phase);
  const { counters, latency } = phaseMetrics[phase];
  const planSeconds = LOAD_PLAN.phases[phaseIndex].startSeconds + millisIntoScenario() / MILLIS_PER_SECOND;
  if (!phaseWindowContains(LOAD_PLAN, phase, planSeconds)) {
    counters.lateIterations.add(1);
    return;
  }
  const iteration = exec.scenario.iterationInTest;
  const index = iteration % data.corpus.length;
  const entry = data.corpus[index];
  const { response, summary } = search(entry, phaseIndex * REQUESTS_PER_PHASE_ID_BLOCK + iteration);
  const expectedBundle = phase === 'swap' ? expectedSwapBundle(LOAD_PLAN, planSeconds) : null;
  const verdict = judgeResponse({ phase, entry, actual: summary, expected: data.expected[index], bundles: data.bundles, facilityMode, expectedBundle });
  counters.requests.add(1);
  if (phase === 'swap') swapWindowCounters[swapWindowIndexAt(LOAD_PLAN, planSeconds)]?.add(1);
  latency.add(response.timings.duration);
  if (response.status >= 500) counters.status5xx.add(1);
  if (response.status === 429) counters.status429.add(1);
  if (!verdict.ok) {
    counters[verdict.violation].add(1);
    logViolation(phase, verdict.violation, index, summary, data.expected[index]);
    return;
  }
  if (verdict.bundle === FIRST_BUNDLE) counters.bundleA.add(1);
  if (verdict.bundle === SECOND_BUNDLE) counters.bundleB.add(1);
  if (verdict.explicitFailure) counters.explicitFailures.add(1);
  if (verdict.disclosedUnobserved) counters.disclosedUnobserved.add(1);
}

function logViolation(phase, violation, index, actual, expected) {
  if (loggedViolations >= MAX_LOGGED_VIOLATIONS_PER_VU) return;
  loggedViolations += 1;
  console.error(`${phase} ${violation} entry=${index} actual=${JSON.stringify(actual)} expected=${JSON.stringify(expected)}`);
}

function control(path) {
  const response = post(path);
  controlCalls.add(1);
  if (response.status !== 200) controlFailures.add(1);
}

/** 계획한 시각마다 팩을 번갈아 교체한다. 짝수 번이므로 마지막은 첫 팩으로 돌아온다. */
export function swapBundles() {
  for (let flip = 0; flip < LOAD_PLAN.controls.swap.flips; flip += 1) {
    const waitMillis = flip * LOAD_PLAN.flipIntervalSeconds * MILLIS_PER_SECOND - millisIntoScenario();
    if (waitMillis > 0) sleep(waitMillis / MILLIS_PER_SECOND);
    control(`/__load/bundle/${swapFlipBundle(flip)}`);
  }
}

export function outageOn() {
  control('/__load/facility/down');
}

export function outageOff() {
  control('/__load/facility/up');
}

export function handleSummary(data) {
  return {
    [`${outputDirectory}/${K6_OUTPUT_FILES.distilled}`]: `${JSON.stringify(distillK6Summary(data, LOAD_PLAN, facilityMode), null, 2)}\n`,
    [`${outputDirectory}/${K6_OUTPUT_FILES.raw}`]: `${JSON.stringify(data)}\n`,
  };
}
