// 경로 엔진 벤치마크 추세 비교 (backend #516): 현재 결과를 직전 성공 run들의 중앙값과 견준다.
import { isDeepStrictEqual } from 'node:util';
import { ALLOCATION, WALL_CLOCK, fail, median, metricsFor, scopeObject, scopesOf } from './journey-benchmark-result.mjs';
import { formatValue } from './journey-benchmark-format.mjs';

// 지연(p50·p99·컴파일)은 같은 JVM에서 잰 보정 작업 시간으로 나눈 뒤 비교한다.
// GitHub 호스티드 러너는 run마다 CPU 모델과 이웃 부하가 달라 절대 시간은 같은 코드에서도 크게 흔들린다.
// 보정 정규화는 기계 속도 차이를 지우지만 측정 중 일시 정지 같은 잡음은 남는다. 같은 커밋을 CI에서 3회 잰 정규화 p50이
// 중앙값 대비 0.67~1.52배까지 흔들린 기록이 있다(엔진 설계 문서 7절, #461). 단일 run끼리 1.30배로 견주면 잡음만으로 경고가 나므로
// 기준을 직전 성공 run 최대 5개의 중앙값으로 잡고(BASELINE_RUN_LIMIT), 새 JVM 3회 중앙값으로 낸 각 run 값에서 이상치가 기준을 못 끌게 한다.
// 그래도 실제 편차는 아직 측정한 적이 없으므로 1.30배는 판정이 아니라 신호다. 수집 2주 뒤 실제 편차로 재조정한다(#516).
// 회귀 게이트(JourneyEnginePerformanceGateTest)의 실패선(p50 2.0배, 그 밖의 지연 2.5배)보다 낮게 둬서 게이트 전에 보이게 한다.
export const WALL_CLOCK_REGRESSION_RATIO = 1.3;
// 할당 바이트는 기계 속도와 무관하고 반복 간 흔들림이 작아 정규화하지 않는다.
// 게이트의 할당 허용폭(30%)과 같은 값이라 게이트가 실패하기 전에 같은 크기의 증가가 경고로 먼저 보인다.
export const ALLOCATION_REGRESSION_RATIO = 1.3;
// 기준 run이 이보다 적으면 중앙값이 단일 run 잡음과 구분되지 않는다. 표는 보여 주되 경고는 보류한다.
export const MIN_BASELINE_RUNS_FOR_WARNING = 3;

const THRESHOLDS = { [WALL_CLOCK]: WALL_CLOCK_REGRESSION_RATIO, [ALLOCATION]: ALLOCATION_REGRESSION_RATIO };
// 값이 달라지면 모든 지표가 움직이는 측정 방법 필드. arch·processors는 러너 속성이라 러너 비교에서 다룬다.
const METHOD_FIELDS = ['warmupRounds', 'measuredRounds', 'aggregation', 'profileLimits', 'java'];

/** 비율을 소수 6자리로 맞춰 부동소수점 잡음이 임계 경계(정확히 1.30배)를 흔들지 못하게 한다. */
const ratioOf = (current, baseline) => Math.round((current / baseline) * 1e6) / 1e6;
const runnerKey = ({ runner }) => `${runner.os}/${runner.arch}/${runner.cpuModel}/${runner.cpuCount} vCPU`;

function methodDifferences(current, previous) {
  return METHOD_FIELDS.filter((field) => !isDeepStrictEqual(current.measurement.method[field], previous.measurement.method[field]));
}

function regressionMessage({ workload, scope, metric, previous, current, ratio }) {
  const basis = metric.kind === WALL_CLOCK ? '보정 후' : '원값';
  return `${workload} ${scope} ${metric.label}: 기준 중앙값 ${formatValue(metric, previous)} → 현재 ${formatValue(metric, current)} (${basis} ${ratio.toFixed(2)}배, 임계 ${THRESHOLDS[metric.kind].toFixed(2)}배)`;
}

function metricRow({ id, scope, metric, baseline, currentCalibration, sufficient }) {
  const samples = baseline
    .map((run) => ({ value: scopeObject(run.measurement.workloads[id], scope)?.[metric.field], calibration: run.measurement.calibrationNanos }))
    .filter(({ value }) => value !== undefined);
  if (samples.length === 0) return null;
  const current = scope.object[metric.field];
  const wallClock = metric.kind === WALL_CLOCK;
  const baselineValue = median(samples.map(({ value, calibration }) => (wallClock ? value / calibration : value)));
  const ratio = ratioOf(wallClock ? current / currentCalibration : current, baselineValue);
  const exceeded = ratio >= THRESHOLDS[metric.kind];
  const row = { workload: id, scope: scope.name, metric, previous: median(samples.map(({ value }) => value)), current, ratio, exceeded, regressed: exceeded && sufficient };
  return row.regressed ? { ...row, message: regressionMessage(row) } : row;
}

function compareWorkload(id, workload, baseline, currentCalibration) {
  const sufficient = baseline.length >= MIN_BASELINE_RUNS_FOR_WARNING;
  const rows = [];
  for (const scope of scopesOf(workload)) {
    for (const metric of metricsFor(scope)) {
      const row = metricRow({ id, scope, metric, baseline, currentCalibration, sufficient });
      if (row !== null) rows.push(row);
    }
  }
  return { id, status: 'compared', rows, baselineRuns: baseline.length, sufficient };
}

const incomparable = (id, status) => ({ id, status, rows: [], baselineRuns: 0, sufficient: false });

/** 같은 측정 방법의 직전 run들 중 이 workload를 같은 번들·질의 집합으로 잰 것만 기준이 된다. */
function workloadStatus(id, workload, runs) {
  const withWorkload = runs.filter((run) => run.measurement.workloads[id] !== undefined);
  if (withWorkload.length === 0) return { status: 'added' };
  const sameCorpus = withWorkload.filter((run) => {
    const before = run.measurement.workloads[id];
    return before.bundle.sha256 === workload.bundle.sha256 && before.querySet.sha256 === workload.querySet.sha256;
  });
  return sameCorpus.length === 0 ? { status: 'corpus-changed' } : { baseline: sameCorpus };
}

export function compareResults({ current, previous }) {
  if (previous.length === 0) fail('at least one previous result is required');
  const notes = [];
  const groups = previous.map((run) => ({ run, differences: methodDifferences(current, run) }));
  const excluded = groups.filter(({ differences }) => differences.length > 0);
  const runs = groups.filter(({ differences }) => differences.length === 0).map(({ run }) => run);
  if (excluded.length > 0) {
    const fields = [...new Set(excluded.flatMap(({ differences }) => differences))].join(', ');
    notes.push(`직전 run ${excluded.length}개는 측정 방법(${fields})이 달라 기준에서 제외했습니다.`);
  }
  const otherRunner = runs.filter((run) => runnerKey(run.context) !== runnerKey(current.context));
  if (otherRunner.length > 0) {
    notes.push(`기준 run ${otherRunner.length}개의 러너 구성이 현재(${runnerKey(current.context)})와 다릅니다. 보정 정규화로 기계 속도 차이를 줄였지만 오차가 남을 수 있습니다.`);
  }
  const currentWorkloads = current.measurement.workloads;
  const workloads = Object.entries(currentWorkloads).map(([id, workload]) => {
    if (runs.length === 0) return incomparable(id, 'method-changed');
    const { status, baseline } = workloadStatus(id, workload, runs);
    return baseline ? compareWorkload(id, workload, baseline, current.measurement.calibrationNanos) : incomparable(id, status);
  });
  for (const id of Object.keys(runs[0]?.measurement.workloads ?? {})) if (currentWorkloads[id] === undefined) workloads.push(incomparable(id, 'removed'));
  const regressions = workloads.flatMap(({ rows }) => rows.filter((row) => row.regressed));
  return { workloads, regressions, notes, baselineContexts: runs.map((run) => run.context), baselineRuns: runs.length };
}
