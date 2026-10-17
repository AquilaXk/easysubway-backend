// 경로 엔진 성능 벤치마크 추세의 결과 모델 (backend #516): 측정 JSON 검증, 반복 중앙값 집계, 컨텍스트 기록, 결과 파싱.
// 직전 run의 artifact는 신뢰할 수 없는 입력이므로 비교와 요약이 읽는 필드는 모두 여기서 형식까지 검증한다.
import { cpus, totalmem } from 'node:os';
import { isDeepStrictEqual } from 'node:util';

export const fail = (message) => { throw new Error(`journey benchmark trend: ${message}`); };

export const SCHEMA_VERSION = 1;
export const WALL_CLOCK = 'wall-clock';
export const ALLOCATION = 'allocation';
export const COMPILE_SCOPE = '컴파일';
// 집계(중앙값)·검증·비교·표가 모두 이 표 하나를 쓴다. scope가 COMPILE_SCOPE이면 workload.compile, 아니면 workload.modes[모드]의 필드다.
export const METRICS = [
  { scope: COMPILE_SCOPE, field: 'nanos', label: '시간', kind: WALL_CLOCK },
  { scope: COMPILE_SCOPE, field: 'allocatedBytes', label: '할당', kind: ALLOCATION },
  { scope: COMPILE_SCOPE, field: 'retainedBytes', label: '보유 힙', kind: ALLOCATION },
  { scope: 'mode', field: 'p50Nanos', label: 'p50', kind: WALL_CLOCK },
  { scope: 'mode', field: 'p99Nanos', label: 'p99', kind: WALL_CLOCK },
  { scope: 'mode', field: 'allocatedBytesPerQuery', label: '질의당 할당', kind: ALLOCATION },
];

const SHA256 = /^[a-f0-9]{64}$/;
const COMMIT_SHA = /^[a-f0-9]{40}$/;
const IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/;
// 요약에 그대로 쓰이는 설명 문자열의 허용 문자. Markdown 구조 문자(` | [ ] < > * # 등)와 줄바꿈은 들어올 수 없다.
const TEXT_CHARS = String.raw`\p{L}\p{N} ._:()@+/-`;
const SAFE_TEXT = new RegExp(`^[${TEXT_CHARS}]{1,100}$`, 'u');
const UNSAFE_TEXT_CHARS = new RegExp(`[^${TEXT_CHARS}]`, 'gu');
const COUNTER = /^[0-9]{1,20}$/;
const RUN_URL = /^https:\/\/[A-Za-z0-9.-]+\/[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+\/actions\/runs\/[0-9]+$/;
const EVENT_NAME = /^[a-z_]{1,32}$/;
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;

const isRecord = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const matches = (value, pattern, name) => { if (typeof value !== 'string' || !pattern.test(value)) fail(`${name} is invalid`); return value; };
const positive = (value, name) => { if (!Number.isFinite(value) || value <= 0) fail(`${name} must be a positive number`); return value; };
const positiveInteger = (value, name) => { if (!Number.isInteger(value) || value <= 0) fail(`${name} must be a positive integer`); return value; };
const nullableText = (value, name) => (value === null ? null : matches(value, SAFE_TEXT, name));
const record = (value, name) => { if (!isRecord(value)) fail(`${name} must be an object`); return value; };

export function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = sorted.length >> 1;
  return sorted.length % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

/** [{name, kind, object}]: 컴파일 구간과 모드별 구간. 지표 표의 scope가 어느 객체를 읽을지 정한다. */
export const scopesOf = (workload) => [
  { name: COMPILE_SCOPE, kind: COMPILE_SCOPE, object: workload.compile },
  ...Object.entries(workload.modes).map(([mode, object]) => ({ name: mode, kind: 'mode', object })),
];
export const metricsFor = (scope) => METRICS.filter(({ scope: kind }) => kind === scope.kind);
export const scopeObject = (workload, scope) => (scope.kind === COMPILE_SCOPE ? workload.compile : workload.modes[scope.name]);

function validateMethod(method) {
  record(method, 'method');
  positiveInteger(method.warmupRounds, 'method.warmupRounds');
  positiveInteger(method.measuredRounds, 'method.measuredRounds');
  matches(method.aggregation, SAFE_TEXT, 'method.aggregation');
  matches(method.java, SAFE_TEXT, 'method.java');
  matches(method.arch, SAFE_TEXT, 'method.arch');
  positiveInteger(method.processors, 'method.processors');
  const limits = record(method.profileLimits, 'method.profileLimits');
  if (Object.keys(limits).length === 0) fail('method.profileLimits must not be empty');
  for (const [key, value] of Object.entries(limits)) { matches(key, IDENTIFIER, 'method.profileLimits key'); positiveInteger(value, `method.profileLimits.${key}`); }
}

export function validateMeasurement(value) {
  if (!isRecord(value) || value.schemaVersion !== SCHEMA_VERSION) fail(`measurement schemaVersion must be ${SCHEMA_VERSION}`);
  validateMethod(value.method);
  positive(value.calibrationNanos, 'calibrationNanos');
  if (!isRecord(value.workloads) || Object.keys(value.workloads).length === 0) fail('measurement workloads must not be empty');
  for (const [id, workload] of Object.entries(value.workloads)) {
    const where = `workload ${id}`;
    matches(id, IDENTIFIER, 'workload id');
    if (!isRecord(workload) || !isRecord(workload.bundle)) fail(`${where} bundle is invalid`);
    matches(workload.bundle.sha256, SHA256, `${where} bundle.sha256`);
    if (!isRecord(workload.querySet)) fail(`${where} querySet is invalid`);
    matches(workload.querySet.sha256, SHA256, `${where} querySet.sha256`);
    matches(workload.querySet.id, IDENTIFIER, `${where} querySet.id`);
    positiveInteger(workload.querySet.count, `${where} querySet.count`);
    if (!isRecord(workload.compile) || !isRecord(workload.modes) || Object.keys(workload.modes).length === 0) fail(`${where} compile and modes are required`);
    for (const scope of scopesOf(workload)) {
      if (scope.kind === 'mode') matches(scope.name, IDENTIFIER, `${where} mode`);
      for (const { field } of metricsFor(scope)) positive(scope.object[field], `${where} ${scope.name} ${field}`);
    }
  }
  return value;
}

export function validateContext(context) {
  record(context, 'context');
  matches(context.commitSha, COMMIT_SHA, 'context.commitSha');
  matches(context.ref, SAFE_TEXT, 'context.ref');
  matches(context.event, EVENT_NAME, 'context.event');
  matches(context.runId, COUNTER, 'context.runId');
  matches(context.runAttempt, COUNTER, 'context.runAttempt');
  matches(context.runUrl, RUN_URL, 'context.runUrl');
  matches(context.measuredAt, TIMESTAMP, 'context.measuredAt');
  const runner = record(context.runner, 'context.runner');
  for (const key of ['name', 'os', 'arch', 'cpuModel']) matches(runner[key], SAFE_TEXT, `context.runner.${key}`);
  nullableText(runner.image, 'context.runner.image');
  nullableText(runner.imageVersion, 'context.runner.imageVersion');
  positiveInteger(runner.cpuCount, 'context.runner.cpuCount');
  positive(runner.totalMemoryBytes, 'context.runner.totalMemoryBytes');
  return context;
}

/** 새 JVM 반복 N회의 측정을 지표별 중앙값 한 건으로 합친다. 지표가 아닌 필드(번들·질의 집합·방법·작업량 카운터)는 반복 간 같아야 한다. */
export function aggregateRepeats(repeats) {
  if (repeats.length === 0) fail('at least one measurement is required');
  if (repeats.length % 2 === 0) fail(`an odd number of repeats is required for an unambiguous median, got ${repeats.length}`);
  repeats.forEach(validateMeasurement);
  const [first, ...others] = repeats;
  const merged = structuredClone(first);
  merged.calibrationNanos = median(repeats.map((repeat) => repeat.calibrationNanos));
  for (const [id, workload] of Object.entries(merged.workloads)) {
    for (const scope of scopesOf(workload)) {
      for (const { field } of metricsFor(scope)) scope.object[field] = median(repeats.map((repeat) => scopeObject(repeat.workloads[id], scope)[field]));
    }
  }
  for (const other of others) {
    if (!isDeepStrictEqual(withoutMetrics(other), withoutMetrics(merged))) fail('workload identity, method or work counters changed between repeats');
  }
  return merged;
}

function withoutMetrics(measurement) {
  const copy = structuredClone(measurement);
  copy.calibrationNanos = 0;
  for (const workload of Object.values(copy.workloads)) {
    for (const scope of scopesOf(workload)) for (const { field } of metricsFor(scope)) scope.object[field] = 0;
  }
  return copy;
}

const validateRepeats = (repeats) => { if (!Number.isInteger(repeats) || repeats < 1 || repeats % 2 === 0) fail('repeats must be a positive odd integer'); };

export function buildResult({ measurement, repeats, context }) {
  validateMeasurement(measurement);
  validateRepeats(repeats);
  validateContext(context);
  // 운영 후보 정책 재현 결과는 성능값이 아니라 게이트가 따로 지키는 결정적 값이라 추세 결과에 싣지 않는다.
  const workloads = Object.fromEntries(Object.entries(measurement.workloads).map(([id, { candidatePolicyReplay: _omitted, ...workload }]) => [id, workload]));
  return { schemaVersion: SCHEMA_VERSION, repeats, context, measurement: { ...measurement, workloads } };
}

const sanitize = (value) => String(value).replace(UNSAFE_TEXT_CHARS, '_').slice(0, 100);
const optionalText = (value) => (value ? sanitize(value) : null);

/**
 * 실행 환경에서 커밋·run·러너 정보를 읽는다. 이미지 정보(ImageOS, ImageVersion)는 공식 기본 환경 변수 목록에 없는
 * 설명용 메타데이터라 없으면 null로 기록한다. 측정 판정에는 쓰이지 않는다.
 */
export function readContext(env, { cpuList = cpus(), memoryBytes = totalmem(), now = new Date() } = {}) {
  const required = (name) => { const value = env[name]; if (!value) fail(`environment variable ${name} is required`); return value; };
  const runId = required('GITHUB_RUN_ID');
  return {
    commitSha: required('GITHUB_SHA'), ref: sanitize(required('GITHUB_REF_NAME')), event: required('GITHUB_EVENT_NAME'),
    runId, runAttempt: required('GITHUB_RUN_ATTEMPT'),
    runUrl: `${required('GITHUB_SERVER_URL')}/${required('GITHUB_REPOSITORY')}/actions/runs/${runId}`,
    measuredAt: now.toISOString(),
    runner: {
      name: sanitize(required('RUNNER_NAME')), os: sanitize(required('RUNNER_OS')), arch: sanitize(required('RUNNER_ARCH')),
      image: optionalText(env.ImageOS), imageVersion: optionalText(env.ImageVersion),
      cpuModel: sanitize(cpuList[0]?.model ?? fail('cpu model is not observable')), cpuCount: cpuList.length, totalMemoryBytes: memoryBytes,
    },
  };
}

export function parseResult(text, name) {
  let value;
  try { value = JSON.parse(text); } catch (error) { fail(`${name} is not valid JSON: ${error.message}`); }
  if (!isRecord(value) || value.schemaVersion !== SCHEMA_VERSION) fail(`${name} is not a schemaVersion ${SCHEMA_VERSION} result`);
  validateRepeats(value.repeats);
  validateContext(value.context);
  validateMeasurement(value.measurement);
  return value;
}
