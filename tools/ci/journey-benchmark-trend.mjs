#!/usr/bin/env node
// 경로 엔진 성능 벤치마크 추세 관찰 (backend #516).
//
// JourneyEnginePerformanceGateTest의 측정 JSON을 새 JVM 반복 N회에서 모아 값별 중앙값 한 건으로 집계하고(aggregate),
// 직전 성공 run의 같은 형식 결과와 비교해 job summary와 ::warning으로 보여 준다(compare).
// 관찰형이다. 임계를 넘어도 종료 코드는 0이고, 입력 누락·형식 위반처럼 측정 자체를 믿을 수 없을 때만 실패한다.
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { cpus, totalmem } from 'node:os';
import { isDeepStrictEqual } from 'node:util';
import { isMainModule } from '../lib/is-main-module.mjs';

const fail = (message) => { throw new Error(`journey benchmark trend: ${message}`); };

// 지연(p50·p99·컴파일)은 같은 JVM에서 잰 보정 작업 시간으로 나눈 뒤 직전 값과 비교한다.
// GitHub 호스티드 러너는 run마다 CPU 모델과 이웃 부하가 달라 절대 시간은 같은 코드에서도 크게 흔들린다.
// 보정 정규화는 기계 속도 차이를 지우지만 측정 중 일시 정지 같은 잡음은 남는다. 같은 커밋을 CI에서 3회 잰 정규화 p50이
// 중앙값 대비 0.67~1.52배까지 흔들린 기록이 있다(엔진 설계 문서 7절, #461). 새 JVM 3회 중앙값이 이 폭을 줄이지만 없애지는 못하므로
// 1.30배는 판정이 아니라 신호다. 단일 run 경고는 다음 run에서 사라질 수 있다. 수집 2주 뒤 실제 편차로 재조정한다(#516).
// 회귀 게이트(JourneyEnginePerformanceGateTest)의 실패선(p50 2.0배, 그 밖의 지연 2.5배)보다 낮게 둬서 게이트 전에 보이게 한다.
export const WALL_CLOCK_REGRESSION_RATIO = 1.3;
// 할당 바이트는 기계 속도와 무관하고 반복 간 흔들림이 작아 정규화하지 않는다.
// 게이트의 할당 허용폭(30%)과 같은 값이라 게이트가 실패하기 전에 같은 크기의 증가가 경고로 먼저 보인다.
export const ALLOCATION_REGRESSION_RATIO = 1.3;

const WALL_CLOCK = 'wall-clock';
const ALLOCATION = 'allocation';
const THRESHOLDS = { [WALL_CLOCK]: WALL_CLOCK_REGRESSION_RATIO, [ALLOCATION]: ALLOCATION_REGRESSION_RATIO };
const COMPILE_SCOPE = '컴파일';
// 집계(중앙값)·검증·비교·표가 모두 이 표 하나를 쓴다. scope가 COMPILE_SCOPE이면 workload.compile, 아니면 workload.modes[모드]의 필드다.
const METRICS = [
  { scope: COMPILE_SCOPE, field: 'nanos', label: '시간', kind: WALL_CLOCK },
  { scope: COMPILE_SCOPE, field: 'allocatedBytes', label: '할당', kind: ALLOCATION },
  { scope: COMPILE_SCOPE, field: 'retainedBytes', label: '보유 힙', kind: ALLOCATION },
  { scope: 'mode', field: 'p50Nanos', label: 'p50', kind: WALL_CLOCK },
  { scope: 'mode', field: 'p99Nanos', label: 'p99', kind: WALL_CLOCK },
  { scope: 'mode', field: 'allocatedBytesPerQuery', label: '질의당 할당', kind: ALLOCATION },
];
const SCHEMA_VERSION = 1;
const SHA256 = /^[a-f0-9]{64}$/;
const COMMIT_SHA = /^[a-f0-9]{40}$/;

const isRecord = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const positive = (value, name) => { if (!Number.isFinite(value) || value <= 0) fail(`${name} must be a positive number`); return value; };
const median = (values) => [...values].sort((a, b) => a - b)[Math.floor((values.length - 1) / 2)];

/** [{scope, object}]: 컴파일 구간과 모드별 구간. 지표 표의 scope가 어느 객체를 읽을지 정한다. */
const scopesOf = (workload) => [
  { name: COMPILE_SCOPE, kind: COMPILE_SCOPE, object: workload.compile },
  ...Object.entries(workload.modes).map(([mode, object]) => ({ name: mode, kind: 'mode', object })),
];
const metricsFor = (scope) => METRICS.filter(({ scope: kind }) => kind === scope.kind);

export function validateMeasurement(value) {
  if (!isRecord(value) || value.schemaVersion !== SCHEMA_VERSION) fail(`measurement schemaVersion must be ${SCHEMA_VERSION}`);
  positive(value.calibrationNanos, 'calibrationNanos');
  if (!isRecord(value.workloads) || Object.keys(value.workloads).length === 0) fail('measurement workloads must not be empty');
  for (const [id, workload] of Object.entries(value.workloads)) {
    const where = `workload ${id}`;
    if (!isRecord(workload) || !isRecord(workload.bundle) || !SHA256.test(workload.bundle.sha256)) fail(`${where} bundle.sha256 is invalid`);
    if (!isRecord(workload.querySet) || !SHA256.test(workload.querySet.sha256) || typeof workload.querySet.id !== 'string') fail(`${where} querySet is invalid`);
    if (!isRecord(workload.compile) || !isRecord(workload.modes) || Object.keys(workload.modes).length === 0) fail(`${where} compile and modes are required`);
    for (const scope of scopesOf(workload)) {
      for (const { field } of metricsFor(scope)) positive(scope.object[field], `${where} ${scope.name} ${field}`);
    }
  }
  return value;
}

/** 새 JVM 반복 N회의 측정을 지표별 중앙값 한 건으로 합친다. 지표가 아닌 필드(번들·질의 집합·작업량 카운터)는 반복 간 같아야 한다. */
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
    if (!isDeepStrictEqual(withoutMetrics(other), withoutMetrics(merged))) fail('workload identity or work counters changed between repeats');
  }
  return merged;
}

const scopeObject = (workload, scope) => (scope.kind === COMPILE_SCOPE ? workload.compile : workload.modes[scope.name]);
function withoutMetrics(measurement) {
  const copy = structuredClone(measurement);
  copy.calibrationNanos = 0;
  for (const workload of Object.values(copy.workloads)) {
    for (const scope of scopesOf(workload)) for (const { field } of metricsFor(scope)) scope.object[field] = 0;
  }
  return copy;
}

export function buildResult({ measurement, repeats, context }) {
  validateMeasurement(measurement);
  if (!Number.isInteger(repeats) || repeats < 1 || repeats % 2 === 0) fail('repeats must be a positive odd integer');
  if (!COMMIT_SHA.test(context?.commitSha ?? '')) fail('context.commitSha must be a 40-character commit sha');
  // 운영 후보 정책 재현 결과는 성능값이 아니라 게이트가 따로 지키는 결정적 값이라 추세 결과에 싣지 않는다.
  const workloads = Object.fromEntries(Object.entries(measurement.workloads).map(([id, { candidatePolicyReplay: _omitted, ...workload }]) => [id, workload]));
  return { schemaVersion: SCHEMA_VERSION, repeats, context, measurement: { ...measurement, workloads } };
}

export function readContext(env, { cpuList = cpus(), memoryBytes = totalmem(), now = new Date() } = {}) {
  const required = (name) => { const value = env[name]; if (!value) fail(`environment variable ${name} is required`); return value; };
  const runUrl = `${required('GITHUB_SERVER_URL')}/${required('GITHUB_REPOSITORY')}/actions/runs/${required('GITHUB_RUN_ID')}`;
  return {
    commitSha: required('GITHUB_SHA'), ref: required('GITHUB_REF_NAME'), event: required('GITHUB_EVENT_NAME'),
    runId: required('GITHUB_RUN_ID'), runAttempt: required('GITHUB_RUN_ATTEMPT'), runUrl, measuredAt: now.toISOString(),
    runner: {
      name: required('RUNNER_NAME'), os: required('RUNNER_OS'), arch: required('RUNNER_ARCH'),
      image: required('ImageOS'), imageVersion: required('ImageVersion'),
      cpuModel: cpuList[0]?.model ?? fail('cpu model is not observable'), cpuCount: cpuList.length, totalMemoryBytes: memoryBytes,
    },
  };
}

export function parseResult(text, name) {
  let value;
  try { value = JSON.parse(text); } catch (error) { fail(`${name} is not valid JSON: ${error.message}`); }
  if (!isRecord(value) || value.schemaVersion !== SCHEMA_VERSION || !isRecord(value.context)) fail(`${name} is not a schemaVersion ${SCHEMA_VERSION} result`);
  validateMeasurement(value.measurement);
  return value;
}

/** 지표 한 건의 비교 값. 지연은 보정 작업 시간 대비 비율로 직전과 현재를 나란히 놓는다. */
function ratioOf({ kind }, previous, current) {
  if (kind === ALLOCATION) return current.value / previous.value;
  return (current.value * previous.calibration) / (previous.value * current.calibration);
}

const runnerKey = ({ runner }) => `${runner.os}/${runner.arch}/${runner.cpuModel}/${runner.cpuCount} vCPU`;

export function compareResults({ current, previous }) {
  const notes = [];
  if (runnerKey(current.context) !== runnerKey(previous.context)) {
    notes.push(`러너 구성이 직전과 다릅니다(직전 ${runnerKey(previous.context)}, 현재 ${runnerKey(current.context)}). 보정 정규화로 기계 속도 차이를 줄였지만 오차가 남을 수 있습니다.`);
  }
  const workloads = [];
  const regressions = [];
  const currentWorkloads = current.measurement.workloads;
  const previousWorkloads = previous.measurement.workloads;
  for (const [id, workload] of Object.entries(currentWorkloads)) {
    const before = previousWorkloads[id];
    if (before === undefined) { workloads.push({ id, status: 'added', rows: [] }); continue; }
    if (before.bundle.sha256 !== workload.bundle.sha256 || before.querySet.sha256 !== workload.querySet.sha256) {
      workloads.push({ id, status: 'corpus-changed', rows: [] });
      continue;
    }
    const rows = compareWorkload(id, before, workload, previous.measurement.calibrationNanos, current.measurement.calibrationNanos);
    regressions.push(...rows.filter((row) => row.regressed));
    workloads.push({ id, status: 'compared', rows });
  }
  for (const id of Object.keys(previousWorkloads)) if (currentWorkloads[id] === undefined) workloads.push({ id, status: 'removed', rows: [] });
  return { workloads, regressions, notes, previousContext: previous.context };
}

function compareWorkload(id, before, now, previousCalibration, currentCalibration) {
  const rows = [];
  for (const scope of scopesOf(now)) {
    const beforeObject = scopeObject(before, scope);
    if (beforeObject === undefined) continue;
    for (const metric of metricsFor(scope)) {
      const previousSide = { value: beforeObject[metric.field], calibration: previousCalibration };
      const currentSide = { value: scope.object[metric.field], calibration: currentCalibration };
      const ratio = ratioOf(metric, previousSide, currentSide);
      const row = { workload: id, scope: scope.name, metric, previous: previousSide.value, current: currentSide.value, ratio, regressed: ratio >= THRESHOLDS[metric.kind] };
      rows.push(row.regressed ? { ...row, message: regressionMessage(row) } : row);
    }
  }
  return rows;
}

const formatNanos = (nanos) => (nanos < 1_000_000 ? `${(nanos / 1_000).toFixed(1)} µs` : `${(nanos / 1_000_000).toFixed(2)} ms`);
const formatBytes = (bytes) => (bytes < 1_048_576 ? `${(bytes / 1_024).toFixed(1)} KiB` : `${(bytes / 1_048_576).toFixed(2)} MiB`);
const formatValue = (metric, value) => (metric.kind === WALL_CLOCK ? formatNanos(value) : formatBytes(value));
const formatChange = (ratio) => `${ratio >= 1 ? '+' : '-'}${(Math.abs(ratio - 1) * 100).toFixed(1)}%`;

function regressionMessage({ workload, scope, metric, previous, current, ratio }) {
  const basis = metric.kind === WALL_CLOCK ? '보정 후' : '원값';
  return `${workload} ${scope} ${metric.label}: 직전 ${formatValue(metric, previous)} → 현재 ${formatValue(metric, current)} (${basis} ${ratio.toFixed(2)}배, 임계 ${THRESHOLDS[metric.kind].toFixed(2)}배)`;
}

const shortSha = (sha) => sha.slice(0, 7);
const link = (context) => `[${shortSha(context.commitSha)} · run ${context.runId}](${context.runUrl})`;
const gib = (bytes) => `${(bytes / 1_073_741_824).toFixed(1)} GiB`;

function headerLines(current, comparison, noPreviousReason) {
  const { runner } = current.context;
  const { method } = current.measurement;
  return [
    '## 경로 엔진 벤치마크 추세', '',
    `- 현재: ${link(current.context)} (${current.context.ref}, ${current.context.event}), 새 JVM ${current.repeats}회 중앙값`,
    `- 러너: ${runner.image} ${runner.imageVersion} · ${runner.os}/${runner.arch} · ${runner.cpuModel} · ${runner.cpuCount} vCPU · ${gib(runner.totalMemoryBytes)} · ${method.java ?? 'Java 미상'}`,
    `- 보정 작업 시간: ${formatNanos(current.measurement.calibrationNanos)} (지연 비교는 이 값으로 나눈 비율)`,
    comparison ? `- 직전 성공 run: ${link(comparison.previousContext)}` : `- 직전 성공 run: 비교 대상 없음 — ${noPreviousReason}`,
    ...(comparison ? [`- 판정: ${comparison.regressions.length === 0 ? '임계 초과 없음' : `임계 초과 ${comparison.regressions.length}건(아래 표와 경고 annotation)`}`] : []),
    `- 임계(비차단): 보정 후 지연 ${WALL_CLOCK_REGRESSION_RATIO.toFixed(2)}배, 할당 ${ALLOCATION_REGRESSION_RATIO.toFixed(2)}배 이상이면 경고`,
    ...(comparison?.notes ?? []).map((note) => `- 참고: ${note}`),
    '',
  ];
}

const STATUS_TEXT = {
  added: '직전 결과에 없던 workload라 비교하지 않았습니다.',
  removed: '현재 결과에 없습니다.',
  'corpus-changed': '번들 또는 질의 집합 sha256이 직전과 달라 비교에서 제외했습니다(회귀가 아니라 코퍼스 변경).',
};

function workloadLines(id, workload, compared) {
  const lines = [`### ${id}`, '', `번들 \`${workload.bundle.sha256.slice(0, 12)}\` · 질의 집합 \`${workload.querySet.id}\` \`${workload.querySet.sha256.slice(0, 12)}\` · ${workload.querySet.count}건`, ''];
  if (compared?.status !== 'compared') {
    if (compared) lines.push(`- ${STATUS_TEXT[compared.status]}`, '');
    lines.push('| 구간 | 지표 | 현재 |', '| --- | --- | ---: |');
    for (const scope of scopesOf(workload)) for (const metric of metricsFor(scope)) lines.push(`| ${scope.name} | ${metric.label} | ${formatValue(metric, scope.object[metric.field])} |`);
    return [...lines, ''];
  }
  lines.push('| 구간 | 지표 | 직전 | 현재 | 변화 | 판정 |', '| --- | --- | ---: | ---: | ---: | --- |');
  for (const row of compared.rows) {
    lines.push(`| ${row.scope} | ${row.metric.label} | ${formatValue(row.metric, row.previous)} | ${formatValue(row.metric, row.current)} | ${formatChange(row.ratio)} | ${row.regressed ? '경고' : '-'} |`);
  }
  return [...lines, ''];
}

export function renderSummary({ current, comparison, noPreviousReason }) {
  const byId = new Map((comparison?.workloads ?? []).map((entry) => [entry.id, entry]));
  const lines = headerLines(current, comparison, noPreviousReason);
  for (const [id, workload] of Object.entries(current.measurement.workloads)) lines.push(...workloadLines(id, workload, byId.get(id)));
  for (const removed of (comparison?.workloads ?? []).filter(({ status }) => status === 'removed')) lines.push(`### ${removed.id}`, '', `- ${STATUS_TEXT.removed}`, '');
  return `${lines.join('\n')}\n`;
}

const annotation = (message) => `::warning title=Journey 엔진 벤치마크 추세::${message.replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A')}\n`;

const options = (values) => {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index], value = values[index + 1];
    if (!/^--[a-z][a-z-]*$/.test(key ?? '') || value === undefined) fail('named options required');
    (result[key.slice(2)] ??= []).push(value);
  }
  return result;
};
const single = (option, key) => {
  if (!option[key] || option[key].length !== 1 || option[key][0].length === 0) fail(`--${key} is required once`);
  return option[key][0];
};
const optional = (option, key) => (option[key] === undefined ? undefined : single(option, key));

function runAggregate(option, env) {
  const measurements = (option.measured ?? []).map((path) => JSON.parse(readFileSync(path, 'utf8')));
  const merged = aggregateRepeats(measurements);
  const built = buildResult({ measurement: merged, repeats: measurements.length, context: readContext(env) });
  writeFileSync(single(option, 'output'), `${JSON.stringify(built, null, 2)}\n`);
}

/** 직전 결과를 읽지 못해도 현재 측정은 유효하므로 job을 막지 않는다. 막으면 새 성공 run이 없어 다음 비교도 영원히 막힌다. 이유는 경고로 남긴다. */
function readPrevious(path, stdout) {
  try {
    return { previous: parseResult(readFileSync(path, 'utf8'), 'previous result') };
  } catch (error) {
    const reason = `직전 결과를 읽지 못했습니다: ${error.message}`;
    stdout.write(annotation(reason));
    return { reason };
  }
}

function runCompare(option, stdout) {
  const current = parseResult(readFileSync(single(option, 'current'), 'utf8'), 'current result');
  const previousPath = optional(option, 'previous');
  const noPreviousReason = optional(option, 'no-previous');
  if ((previousPath === undefined) === (noPreviousReason === undefined)) fail('exactly one of --previous or --no-previous is required');
  const { previous, reason = noPreviousReason } = previousPath === undefined ? {} : readPrevious(previousPath, stdout);
  const comparison = previous === undefined ? null : compareResults({ current, previous });
  appendFileSync(single(option, 'summary'), renderSummary({ current, comparison, noPreviousReason: reason }));
  for (const regression of comparison?.regressions ?? []) stdout.write(annotation(regression.message));
}

export function main(argv = process.argv.slice(2), env = process.env, stdout = process.stdout) {
  const [command, ...rest] = argv, option = options(rest);
  if (command === 'aggregate') return runAggregate(option, env);
  if (command === 'compare') return runCompare(option, stdout);
  return fail('usage is aggregate or compare');
}

if (isMainModule(import.meta.url)) {
  try { main(); } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
}
