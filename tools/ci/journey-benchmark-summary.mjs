// 경로 엔진 벤치마크 추세 job summary와 workflow annotation 렌더링 (backend #516).
import { ALLOCATION_REGRESSION_RATIO, MIN_BASELINE_RUNS_FOR_WARNING, WALL_CLOCK_REGRESSION_RATIO } from './journey-benchmark-compare.mjs';
import { escapeMarkdown, formatChange, formatGiB, formatNanos, formatValue, shortSha, truncate } from './journey-benchmark-format.mjs';
import { metricsFor, scopesOf } from './journey-benchmark-result.mjs';

const ANNOTATION_LIMIT = 500;
export const BENCHMARK_TITLE = 'Journey 엔진 벤치마크 추세';

/** workflow command는 한 줄이어야 하므로 %, 줄바꿈을 이스케이프하고 길이를 제한한다. 다른 관찰 workflow(경로 부하)도 title만 바꿔 쓴다. */
export const annotation = (message, level = 'warning', title = BENCHMARK_TITLE) => `::${level} title=${title}::${truncate(message, ANNOTATION_LIMIT).replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A')}\n`;

// runUrl과 sha는 결과를 읽을 때 형식이 검증되어 있다. 그 밖의 설명 문자열은 escapeMarkdown을 거친다.
const link = (context) => `[${shortSha(context.commitSha)} · run ${context.runId}](${context.runUrl})`;

const runnerLine = ({ runner }, method) => {
  const image = runner.image === null ? '이미지 미관측' : [runner.image, runner.imageVersion].filter(Boolean).join(' ');
  return [image, `${runner.os}/${runner.arch}`, runner.cpuModel, `${runner.cpuCount} vCPU`, formatGiB(runner.totalMemoryBytes), method.java].map(escapeMarkdown).join(' · ');
};

function headerLines(current, comparison, noPreviousReason, skipped) {
  const { context, measurement } = current;
  return [
    '## 경로 엔진 벤치마크 추세', '',
    `- 현재: ${link(context)} (${escapeMarkdown(context.ref)}, ${escapeMarkdown(context.event)}), 새 JVM ${current.repeats}회 중앙값`,
    `- 러너: ${runnerLine(context, measurement.method)}`,
    `- 보정 작업 시간: ${formatNanos(measurement.calibrationNanos)} (지연 비교는 이 값으로 나눈 비율)`,
    comparison
      ? `- 기준: 직전 성공 run ${comparison.baselineRuns}개 중앙값 — ${comparison.baselineContexts.map(link).join(', ')}`
      : `- 직전 성공 run: 비교 대상 없음 — ${escapeMarkdown(noPreviousReason)}`,
    ...(comparison ? [`- 판정: ${comparison.regressions.length === 0 ? '임계 초과 없음' : `임계 초과 ${comparison.regressions.length}건(아래 표와 경고 annotation)`}`] : []),
    `- 임계(비차단): 보정 후 지연 ${WALL_CLOCK_REGRESSION_RATIO.toFixed(2)}배, 할당 ${ALLOCATION_REGRESSION_RATIO.toFixed(2)}배 이상이면 경고. 기준 run이 ${MIN_BASELINE_RUNS_FOR_WARNING}개 미만이면 표만 보이고 경고는 보류합니다.`,
    ...(comparison?.notes ?? []).map((note) => `- 참고: ${escapeMarkdown(note)}`),
    ...skipped.map(({ runId, kind, message }) => `- 기준에서 제외된 run ${escapeMarkdown(runId)} (${escapeMarkdown(kind)}): ${escapeMarkdown(message)}`),
    '',
  ];
}

const STATUS_TEXT = {
  added: '직전 결과에 없던 workload라 비교하지 않았습니다.',
  removed: '현재 결과에 없습니다.',
  'corpus-changed': '번들 또는 질의 집합 sha256이 직전과 달라 비교에서 제외했습니다(회귀가 아니라 코퍼스 변경).',
  'method-changed': '측정 방법이 직전과 달라 비교에서 제외했습니다(참고 항목에 달라진 필드).',
};

const judgement = (row) => {
  if (row.regressed) return '경고';
  return row.exceeded ? '보류' : '-';
};

function workloadLines(id, workload, compared) {
  const lines = [`### ${id}`, '', `번들 \`${workload.bundle.sha256.slice(0, 12)}\` · 질의 집합 \`${workload.querySet.id}\` \`${workload.querySet.sha256.slice(0, 12)}\` · ${workload.querySet.count}건`, ''];
  if (compared?.status !== 'compared') {
    if (compared) lines.push(`- ${STATUS_TEXT[compared.status]}`, '');
    lines.push('| 구간 | 지표 | 현재 |', '| --- | --- | ---: |');
    for (const scope of scopesOf(workload)) for (const metric of metricsFor(scope)) lines.push(`| ${scope.name} | ${metric.label} | ${formatValue(metric, scope.object[metric.field])} |`);
    return [...lines, ''];
  }
  lines.push(`- 기준 run ${compared.baselineRuns}개`);
  if (!compared.sufficient) lines.push(`- 기준 표본 부족(${compared.baselineRuns}개 < ${MIN_BASELINE_RUNS_FOR_WARNING}개): 임계를 넘어도 경고하지 않고 표시만 합니다.`);
  lines.push('', '| 구간 | 지표 | 기준 중앙값 | 현재 | 변화 | 판정 |', '| --- | --- | ---: | ---: | ---: | --- |');
  for (const row of compared.rows) {
    lines.push(`| ${row.scope} | ${row.metric.label} | ${formatValue(row.metric, row.previous)} | ${formatValue(row.metric, row.current)} | ${formatChange(row.ratio)} | ${judgement(row)} |`);
  }
  return [...lines, ''];
}

export function renderSummary({ current, comparison, noPreviousReason, skipped }) {
  const byId = new Map((comparison?.workloads ?? []).map((entry) => [entry.id, entry]));
  const lines = headerLines(current, comparison, noPreviousReason, skipped);
  for (const [id, workload] of Object.entries(current.measurement.workloads)) lines.push(...workloadLines(id, workload, byId.get(id)));
  for (const removed of (comparison?.workloads ?? []).filter(({ status }) => status === 'removed')) lines.push(`### ${removed.id}`, '', `- ${STATUS_TEXT.removed}`, '');
  return `${lines.join('\n')}\n`;
}
