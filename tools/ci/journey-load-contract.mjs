// 경로 검색 부하·장애 시나리오의 계약 (backend #520).
//
// k6 스크립트(journey-load.k6.js)와 Node 판정(journey-load-verdict.mjs)이 같이 읽는 순수 모듈이다. k6 런타임에서도 읽혀야 하므로
// node: 내장 모듈을 가져오지 않는다. 판정의 핵심은 "응답 하나가 그 단계에서 허용된 단독 응답과 정확히 같은가"이다.

const FIRST_DEPARTURE_OFFSET_SECONDS = 23_400; // 06:30 KST
const SERVICE_WINDOW_SECONDS = 55_800; // 06:30부터 22:00까지
const KST_OFFSET = '+09:00';
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const ULID_BODY_LENGTH = 25;

/** k6가 출력 디렉터리에 쓰는 파일. 실행기가 같은 이름으로 읽고 지운다. */
export const K6_OUTPUT_FILES = Object.freeze({ distilled: 'k6-distilled.json', raw: 'k6-raw.json' });

/**
 * 시나리오가 검증하는 서버 구성 둘. default는 운영 기본값(easysubway.journey.facility-status.required=false), required는 시설 상태
 * 필수 모드(true)다. 시설 원천이 멈췄을 때 required에서는 계단 없는 필수 요청이 명시 503이 되고, default에서는 모든 요청이
 * 시설 상태 미적용(UNOBSERVED)을 밝힌 정상 응답이 된다("모르면 정상, 확인된 고장만 반영").
 */
export const FACILITY_MODES = Object.freeze({ DEFAULT: 'default', REQUIRED: 'required' });
export const FACILITY_MODE_LABELS = Object.freeze({
  [FACILITY_MODES.DEFAULT]: '운영 기본 구성(facility-status.required=false)',
  [FACILITY_MODES.REQUIRED]: '시설 상태 필수 구성(facility-status.required=true)',
});

export const FIRST_BUNDLE = 'a';
export const SECOND_BUNDLE = 'b';

const LOOPBACK_ORIGIN = /^http:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})$/;
const MAX_PORT = 65_535;

/** 부하 대상은 러너가 직접 띄운 로컬 서버뿐이다. 운영 주소로 부하가 나가지 않도록 다른 출처는 모두 거부한다. */
export function assertLoopbackOrigin(origin) {
  const match = LOOPBACK_ORIGIN.exec(origin ?? '');
  if (!match || Number(match[1]) > MAX_PORT) throw new Error('the load target must be the local load server origin (http://127.0.0.1:<port>)');
}

export const FACILITY_OUTAGE_CODE = 'FACILITY_STATUS_UNAVAILABLE';
const UNREADABLE_BODY = 'UNREADABLE_BODY';
const STATUS_TRANSPORT_FAILURE = 0;
const STATUS_SERVER_ERROR_FLOOR = 500;
const STATUS_SERVICE_UNAVAILABLE = 503;
const REQUIRE_STEP_FREE = 'REQUIRE_STEP_FREE';
const FACILITY_APPLIED = 'APPLIED';
const FACILITY_UNOBSERVED = 'UNOBSERVED';
/** 요청마다 달라지는 응답 필드. 단독 응답 비교에서 뺀다. */
const PER_REQUEST_FIELDS = ['requestId', 'queryId', 'calculatedAt'];

export const VIOLATIONS = Object.freeze({
  MIXED_RESPONSE: 'MIXED_RESPONSE',
  WRONG_BUNDLE: 'WRONG_BUNDLE',
  MASKED_OUTAGE: 'MASKED_OUTAGE',
  UNEXPECTED_FAILURE: 'UNEXPECTED_FAILURE',
  UNEXPECTED_RESPONSE: 'UNEXPECTED_RESPONSE',
});

/** 제어 호출은 앞 단계의 마지막 요청이 끝난 뒤 이만큼 기다려 실행하고, 제어 뒤 이만큼 지나서 다음 부하 단계를 시작한다. */
const GUARD_SECONDS = 1;
/** k6 도착률 스케줄러는 단계의 끝 시각에도 한 건을 보내고, 약간 밀린 반복도 같은 상태를 보므로 이 정도는 창 안으로 본다. 가드보다 짧아야 한다. */
const WINDOW_TOLERANCE_SECONDS = GUARD_SECONDS / 2;
/** 팩 교체 제어 호출 뒤 새 팩이 응답에 반영되기까지 허용하는 시간(요청 경로 밖 컴파일 포함). 이 시간이 지나면 새 팩만 허용한다. */
const ACTIVATION_LAG_SECONDS = 1;
/** 다음 교체를 향한 시계 오차. 교체 시각 이 값 전까지만 이전 교체의 팩을 엄격히 요구한다. */
const FLIP_JITTER_SECONDS = 0.25;
const SETTLE_SECONDS = 2;
const FLIP_INTERVAL_SECONDS = 2;
const STEADY_SECONDS = 20;
const SWAP_SECONDS = 20;
const OUTAGE_SECONDS = 10;
const RECOVERED_SECONDS = 10;
const SWAP_START = STEADY_SECONDS;
const FIRST_FLIP = SWAP_START + GUARD_SECONDS;
const OUTAGE_ON = SWAP_START + SWAP_SECONDS + GUARD_SECONDS;
const OUTAGE_START = OUTAGE_ON + SETTLE_SECONDS;
const OUTAGE_OFF = OUTAGE_START + OUTAGE_SECONDS + GUARD_SECONDS;
const RECOVERED_START = OUTAGE_OFF + SETTLE_SECONDS;

/**
 * 단계(phase)는 서로 겹치지 않는 k6 시나리오다. 장애 주입은 단계 사이의 제어 호출이 하고, 장애 단계의 부하는 주입이
 * 끝난 뒤(settle)에 시작하므로 그 단계의 모든 응답이 같은 상태를 본다.
 */
export const LOAD_PLAN = Object.freeze({
  requestsPerSecond: 200,
  corpusSize: 120,
  corpusSeed: 20_261_017,
  flipIntervalSeconds: FLIP_INTERVAL_SECONDS,
  guardSeconds: GUARD_SECONDS,
  windowToleranceSeconds: WINDOW_TOLERANCE_SECONDS,
  activationLagSeconds: ACTIVATION_LAG_SECONDS,
  flipJitterSeconds: FLIP_JITTER_SECONDS,
  settleSeconds: SETTLE_SECONDS,
  phases: Object.freeze([
    { name: 'steady', startSeconds: 0, durationSeconds: STEADY_SECONDS },
    { name: 'swap', startSeconds: SWAP_START, durationSeconds: SWAP_SECONDS },
    { name: 'outage', startSeconds: OUTAGE_START, durationSeconds: OUTAGE_SECONDS },
    { name: 'recovered', startSeconds: RECOVERED_START, durationSeconds: RECOVERED_SECONDS },
  ]),
  controls: Object.freeze({
    swap: { startSeconds: FIRST_FLIP, flips: SWAP_SECONDS / FLIP_INTERVAL_SECONDS },
    outageOn: { startSeconds: OUTAGE_ON },
    outageOff: { startSeconds: OUTAGE_OFF },
  }),
});

/** 각 단계에서 허용되는 단독 응답의 팩 집합. */
const ALLOWED_BUNDLES = Object.freeze({
  steady: [FIRST_BUNDLE], swap: [FIRST_BUNDLE, SECOND_BUNDLE], outage: [FIRST_BUNDLE], recovered: [FIRST_BUNDLE],
});

/** 교체 제어는 둘째 팩부터 번갈아 활성화한다. 짝수 번이라 마지막 교체는 첫 팩으로 돌아온다. */
export const swapFlipBundle = (flip) => (flip % 2 === 0 ? SECOND_BUNDLE : FIRST_BUNDLE);

/**
 * swap 단계의 어느 시각에 시작한 요청이 반드시 받아야 하는 팩. 교체를 호출한 뒤 활성화가 반영될 시간(activationLag)이 지나고
 * 다음 교체 직전(jitter)까지는 그 교체가 활성화한 팩만 허용한다. 그 사이의 시각은 정해지지 않아 null(어느 팩이든 허용)이다.
 */
export function expectedSwapBundle(plan, planSeconds) {
  const swap = plan.phases.find(({ name }) => name === 'swap');
  const { startSeconds: firstFlip, flips } = plan.controls.swap;
  const flipAt = (index) => firstFlip + index * plan.flipIntervalSeconds;
  const windowEnd = swap.startSeconds + swap.durationSeconds + plan.windowToleranceSeconds;
  if (planSeconds >= swap.startSeconds && planSeconds < firstFlip - plan.flipJitterSeconds) return FIRST_BUNDLE;
  for (let flip = 0; flip < flips; flip += 1) {
    const strictEnd = flip + 1 < flips ? flipAt(flip + 1) - plan.flipJitterSeconds : windowEnd;
    if (planSeconds >= flipAt(flip) + plan.activationLagSeconds && planSeconds < strictEnd) return swapFlipBundle(flip);
  }
  return null;
}

const COUNTER_KINDS = ['requests', 'lateIterations', 'status5xx', 'status429', 'bundleA', 'bundleB', 'explicitFailures', 'disclosedUnobserved', ...Object.values(VIOLATIONS)];
const LATENCY_KIND = 'latencyMs';

/**
 * 반복이 자기 단계 창(끝 허용 오차 포함) 안에서 시작했는가. 부하가 목표 도착률을 못 따라가면 반복이 밀려 다음 단계의 상태(장애 주입 등)에서
 * 실행되는데, 그 응답을 단계 기준으로 판정하면 제품 위반으로 오인하므로 창 밖에서 시작한 반복은 판정하지 않는다.
 */
export function phaseWindowContains(plan, phaseName, elapsedSeconds) {
  const phase = plan.phases.find(({ name }) => name === phaseName);
  if (!phase) throw new Error(`phaseWindowContains: unknown phase ${phaseName}`);
  return elapsedSeconds >= phase.startSeconds && elapsedSeconds < phase.startSeconds + phase.durationSeconds + plan.windowToleranceSeconds;
}

export const metricName = (phase, kind) => `phase_${phase}_${kind}`;

export function requestIdFor(sequence) {
  if (!Number.isSafeInteger(sequence) || sequence < 0) throw new Error('requestIdFor: sequence must be a non-negative safe integer');
  let remaining = sequence;
  let body = '';
  while (remaining > 0) {
    body = CROCKFORD[remaining % 32] + body;
    remaining = Math.floor(remaining / 32);
  }
  return `0${body.padStart(ULID_BODY_LENGTH, '0')}`;
}

/** 시드가 같으면 같은 수열을 내는 32비트 PRNG(mulberry32). */
function seededRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let mixed = Math.imul(state ^ (state >>> 15), state | 1);
    mixed ^= mixed + Math.imul(mixed ^ (mixed >>> 7), mixed | 61);
    return ((mixed ^ (mixed >>> 14)) >>> 0) / 4_294_967_296;
  };
}

const MOBILITY_MIXES = Object.freeze([
  { mobility: 'STANDARD', constraint: 'NONE' },
  { mobility: 'STEP_FREE', constraint: 'NONE' },
  { mobility: 'STEP_FREE', constraint: REQUIRE_STEP_FREE },
]);

/** 고정 시드 질의 목록: 무작위 OD, 서비스일 06:30~22:00 사이 출발, 세 가지 이동 조건을 번갈아 쓴다. */
export function buildCorpus({ serviceDate, stations }, { corpusSize, corpusSeed }) {
  const random = seededRandom(corpusSeed);
  const serviceDayStart = Date.parse(`${serviceDate}T00:00:00${KST_OFFSET}`);
  const pick = () => stations[Math.floor(random() * stations.length)];
  return Array.from({ length: corpusSize }, (_, index) => {
    const origin = pick();
    let destination = pick();
    while (destination === origin) destination = pick();
    const departureSeconds = FIRST_DEPARTURE_OFFSET_SECONDS + Math.floor(random() * SERVICE_WINDOW_SECONDS);
    const requestedAt = new Date(serviceDayStart + departureSeconds * 1000).toISOString().replace('.000Z', 'Z');
    return { index, origin, destination, requestedAt, ...MOBILITY_MIXES[index % MOBILITY_MIXES.length] };
  });
}

export function requestBody(entry, requestId) {
  return JSON.stringify({
    requestId,
    originStationId: entry.origin,
    destinationStationId: entry.destination,
    departure: { mode: 'SCHEDULED', requestedAt: entry.requestedAt },
    timePolicy: 'TIMETABLE_REQUIRED',
    walkingPace: 'STANDARD',
    mobilityProfile: entry.mobility,
    constraintMode: entry.constraint,
    maxTransfers: 3,
    alternativeCount: 3,
  });
}

/**
 * 응답 본문 전체를 한 줄 문자열로 줄인다(cyrb53, 53비트). k6는 setup 데이터를 가상 사용자마다 복사하므로 본문을 그대로 들고 있지 않는다.
 * 같은 단독 응답을 같은 값으로 줄이는 용도라 충돌 확률(약 2^-53)은 판정에 영향을 주지 않는다.
 */
function fingerprintOf(text) {
  let first = 0xdeadbeef;
  let second = 0x41c6ce57;
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index);
    first = Math.imul(first ^ code, 2654435761);
    second = Math.imul(second ^ code, 1597334677);
  }
  first = Math.imul(first ^ (first >>> 16), 2246822507) ^ Math.imul(second ^ (second >>> 13), 3266489909);
  second = Math.imul(second ^ (second >>> 16), 2246822507) ^ Math.imul(first ^ (first >>> 13), 3266489909);
  return `${text.length}:${(4_294_967_296 * (2_097_151 & second) + (first >>> 0)).toString(36)}`;
}

const unreadable = (status) => ({ status, code: UNREADABLE_BODY, bundleSha: null, facilityStatus: null, fingerprint: '' });

/**
 * 응답을 비교용 요약으로 줄인다. 성공이면 팩 식별자와 시설 상태, 그리고 그 둘과 요청별 필드를 뺀 나머지 전체의 지문이다.
 * 시설 상태는 장애 단계에서만 달라져야 하므로 지문과 따로 비교한다. 실패면 HTTP 상태와 기계 코드다.
 */
export function summarizeResponse(status, bodyText) {
  let body;
  try {
    body = JSON.parse(bodyText);
  } catch {
    return unreadable(status);
  }
  if (body === null || typeof body !== 'object') return unreadable(status);
  if (status !== 200) return { status, code: body.code ?? null, bundleSha: null, facilityStatus: null, fingerprint: '' };
  const facilityStatus = body.stairFreeAlternative?.facilityStatus ?? null;
  const stable = { ...body, stairFreeAlternative: { ...body.stairFreeAlternative, facilityStatus: undefined } };
  for (const field of PER_REQUEST_FIELDS) delete stable[field];
  return { status, code: null, bundleSha: body.sourceIdentity?.routeBundleSha256 ?? null, facilityStatus, fingerprint: fingerprintOf(JSON.stringify(stable)) };
}

const explicitOutage = () => ({ status: STATUS_SERVICE_UNAVAILABLE, code: FACILITY_OUTAGE_CODE, bundleSha: null, facilityStatus: null, fingerprint: '' });

/**
 * 시설 상태 원천이 멈춘 동안 이 요청이 받아야 하는 응답. 필수 구성의 계단 없는 필수 요청은 명시 실패이고, 그 밖의 모든 경우는
 * 원래 응답에 시설 상태 미적용(UNOBSERVED)을 밝힌 정상 응답이다.
 */
export function outageExpectation(healthy, entry, facilityMode) {
  if (facilityMode === FACILITY_MODES.REQUIRED && entry.constraint === REQUIRE_STEP_FREE) return explicitOutage();
  return healthy.status === 200 ? { ...healthy, facilityStatus: FACILITY_UNOBSERVED } : healthy;
}

const sameSummary = (left, right) => left.status === right.status && left.code === right.code && left.bundleSha === right.bundleSha
  && left.facilityStatus === right.facilityStatus && left.fingerprint === right.fingerprint;

const isFailure = ({ status }) => status === STATUS_TRANSPORT_FAILURE || status >= STATUS_SERVER_ERROR_FLOOR;

/** 장애 단계에서 장애가 아닌 것처럼 보이는 응답: 필수 구성의 필수 요청이 명시 실패가 아니거나, 시설 상태를 적용했다고 주장하는 정상 응답. */
const masksOutage = (actual, entry, facilityMode) => (facilityMode === FACILITY_MODES.REQUIRED && entry.constraint === REQUIRE_STEP_FREE && !sameSummary(actual, explicitOutage()))
  || (actual.status === 200 && actual.facilityStatus === FACILITY_APPLIED);

function classifyMismatch({ phase, entry, actual, expected, bundles, facilityMode, allowedLabels }) {
  if (isFailure(actual)) return VIOLATIONS.UNEXPECTED_FAILURE;
  if (phase === 'outage' && masksOutage(actual, entry, facilityMode)) return VIOLATIONS.MASKED_OUTAGE;
  const answeringBundle = Object.keys(bundles).find((label) => bundles[label] === actual.bundleSha);
  if (actual.status === 200 && answeringBundle !== undefined) {
    const fromOtherBundle = !allowedLabels.includes(answeringBundle) && sameSummary(actual, expected[answeringBundle]);
    return fromOtherBundle ? VIOLATIONS.WRONG_BUNDLE : VIOLATIONS.MIXED_RESPONSE;
  }
  return VIOLATIONS.UNEXPECTED_RESPONSE;
}

/**
 * 응답 하나를 그 단계의 허용 집합과 비교한다. expected는 팩별 정상 단독 응답({a, b})이고, bundles는 팩 이름별 manifest sha256이다.
 * expectedBundle은 swap 단계에서 활성화가 이미 반영된 시각이면 그 팩 이름이고, 아니면 null이다(expectedSwapBundle).
 * 반환의 bundle은 정상 응답이 어느 팩의 것인지(식별자가 없는 실패·무경로 응답은 null)이다.
 */
export function judgeResponse({ phase, entry, actual, expected, bundles, facilityMode, expectedBundle = null }) {
  const allowedLabels = phase === 'swap' && expectedBundle !== null ? [expectedBundle] : ALLOWED_BUNDLES[phase];
  const allowed = allowedLabels.map((label) => (phase === 'outage' ? outageExpectation(expected[label], entry, facilityMode) : expected[label]));
  if (!allowed.some((candidate) => sameSummary(actual, candidate))) {
    return { ok: false, violation: classifyMismatch({ phase, entry, actual, expected, bundles, facilityMode, allowedLabels }) };
  }
  const bundle = Object.keys(bundles).find((label) => bundles[label] === actual.bundleSha) ?? null;
  return {
    ok: true,
    bundle,
    explicitFailure: actual.status === STATUS_SERVICE_UNAVAILABLE && actual.code === FACILITY_OUTAGE_CODE,
    disclosedUnobserved: actual.status === 200 && actual.facilityStatus === FACILITY_UNOBSERVED,
  };
}

const counterOf = (metrics, name) => metrics[name]?.values?.count ?? 0;

function latencyOf(metrics, name) {
  const values = metrics[name]?.values;
  return values ? { p50: values['p(50)'], p95: values['p(95)'], p99: values['p(99)'], max: values.max } : null;
}

/** k6 요약에서 이 시나리오가 만든 지표만 뽑아 판정 입력으로 만든다. 한 번도 더해지지 않아 요약에 없는 카운터는 0이다. */
export function distillK6Summary({ metrics }, plan, facilityMode) {
  const phases = Object.fromEntries(plan.phases.map(({ name }) => {
    const count = (kind) => counterOf(metrics, metricName(name, kind));
    return [name, {
      requests: count('requests'),
      lateIterations: count('lateIterations'),
      status5xx: count('status5xx'),
      status429: count('status429'),
      bundleA: count('bundleA'),
      bundleB: count('bundleB'),
      explicitFailures: count('explicitFailures'),
      disclosedUnobserved: count('disclosedUnobserved'),
      violations: Object.fromEntries(Object.values(VIOLATIONS).map((violation) => [violation, count(violation)])),
      latencyMs: latencyOf(metrics, metricName(name, LATENCY_KIND)),
    }];
  }));
  return {
    schemaVersion: 1,
    facilityMode,
    droppedIterations: counterOf(metrics, 'dropped_iterations'),
    controls: { calls: counterOf(metrics, 'control_calls'), failures: counterOf(metrics, 'control_failures') },
    phases,
  };
}

/** k6 스크립트가 단계마다 같은 지표 집합을 만들도록 이름 목록을 준다. */
export const phaseMetricSpec = Object.freeze({ counters: COUNTER_KINDS, latency: LATENCY_KIND });
