// 경로 판정 클래스 뮤테이션 추세 job summary 렌더링 (backend #523).
import { escapeMarkdown, shortSha } from './journey-benchmark-format.mjs';
import { SCORE_DROP_TOLERANCE_POINTS } from './journey-mutation-compare.mjs';
import { formatDuration, formatPercent, formatPoints } from './journey-mutation-format.mjs';

export const MUTATION_TITLE = 'Journey 뮤테이션 추세';
// 생존 mutant 표는 job summary 크기 한도(1MiB) 안에서 읽을 수 있는 길이로 자른다. 전체 목록은 artifact의 result.json에 있다.
export const SURVIVOR_LIST_LIMIT = 50;

// runUrl과 sha는 결과를 읽을 때 형식이 검증되어 있다. 그 밖의 설명 문자열은 escapeMarkdown을 거친다.
const link = (context) => `[${shortSha(context.commitSha)} · run ${context.runId}](${context.runUrl})`;
const escaped = (value) => escapeMarkdown(value);
// 코드 스팬 안에서는 백슬래시가 이스케이프가 아니므로 스팬을 닫을 수 있는 백틱과 줄바꿈만 없앤다.
const code = (value) => `\`${String(value).replace(/\s+/g, ' ').replaceAll('`', "'")}\``;

const JUDGEMENT = { compared: '-', added: '추가', removed: '제외', 'method-changed': '보류' };
const judgement = (row) => (row.regressed ? '경고' : JUDGEMENT[row.status]);

function headerLines({ current, comparison, noPreviousReason, skipped }) {
  const { context, run, totals } = current;
  const detected = totals.killed + totals.timedOut + totals.memoryError;
  return [
    '## 경로 판정 뮤테이션 추세', '',
    `- 현재: ${link(context)} (${escaped(context.ref)}, ${escaped(context.event)})`,
    `- 도구: PIT ${run.pitestVersion} · JUnit 플러그인 ${run.junit5PluginVersion} · 스레드 ${run.threads} · 실행 시간 ${formatDuration(run.elapsedSeconds)}`,
    `- 범위: ${run.targetClasses.map(code).join(', ')}`,
    `- 전체 점수: ${formatPercent(totals.score)} — 탐지 ${detected}(테스트 실패 ${totals.killed} · 시간 초과 ${totals.timedOut} · 메모리 ${totals.memoryError}), 생존 ${totals.survived}, 미커버 ${totals.noCoverage}, 컴파일 불가 ${totals.nonViable}`,
    comparison
      ? `- 기준: 직전 성공 run ${comparison.baselineRuns}개 중앙값 — ${comparison.baselineContexts.map(link).join(', ')}`
      : `- 직전 성공 run: 비교 대상 없음 — ${escaped(noPreviousReason)}`,
    ...(comparison ? [`- 판정: ${comparison.regressions.length === 0 ? '점수 하락 없음' : `점수 하락 ${comparison.regressions.length}건(아래 표와 경고 annotation)`}`] : []),
    `- 임계(비차단): 클래스별 점수가 기준 중앙값보다 ${SCORE_DROP_TOLERANCE_POINTS.toFixed(1)}%p를 넘게 내려가면 경고합니다.`,
    ...(comparison?.notes ?? []).map((note) => `- 참고: ${escaped(note)}`),
    ...skipped.map(({ runId, kind, message }) => `- 기준에서 제외된 run ${escaped(runId)} (${escaped(kind)}): ${escaped(message)}`),
    '',
  ];
}

const missedOf = (counts) => counts.survived + counts.noCoverage;
const detectedOf = (counts) => counts.killed + counts.timedOut + counts.memoryError;

function classRow(className, counts, compared) {
  if (compared?.status === 'removed') return `| ${escaped(className)} | - | - | - | - | - | ${judgement(compared)} |`;
  const base = compared?.status === 'compared' ? formatPercent(compared.baselineScore) : '-';
  const change = compared?.status === 'compared' ? formatPoints(compared.delta) : '-';
  return `| ${escaped(className)} | ${detectedOf(counts)} | ${missedOf(counts)} | ${formatPercent(counts.score)} | ${base} | ${change} | ${compared ? judgement(compared) : '-'} |`;
}

function classLines(current, comparison) {
  const byName = new Map((comparison?.classes ?? []).map((row) => [row.className, row]));
  const lines = ['### 클래스별 점수', '', '| 클래스 | 탐지 | 놓침(생존+미커버) | 점수 | 기준 중앙값 | 변화 | 판정 |', '| --- | ---: | ---: | ---: | ---: | ---: | --- |'];
  for (const [className, counts] of Object.entries(current.classes)) lines.push(classRow(className, counts, byName.get(className)));
  for (const removed of (comparison?.classes ?? []).filter(({ status }) => status === 'removed')) lines.push(classRow(removed.className, null, removed));
  return [...lines, ''];
}

function survivorLines({ survivors }) {
  if (survivors.length === 0) return ['### 잡지 못한 mutant 없음', ''];
  const lines = [`### 잡지 못한 mutant (${survivors.length}건)`, '', '| 클래스 | 메서드 | 줄 | 변이 | 설명 | 상태 |', '| --- | --- | ---: | --- | --- | --- |'];
  for (const { className, method, line, mutator, description, status } of survivors.slice(0, SURVIVOR_LIST_LIMIT)) {
    lines.push(`| ${escaped(className)} | ${escaped(method)} | ${line} | ${escaped(mutator)} | ${escaped(description)} | ${escaped(status)} |`);
  }
  if (survivors.length > SURVIVOR_LIST_LIMIT) lines.push('', `외 ${survivors.length - SURVIVOR_LIST_LIMIT}건은 artifact의 result.json에 있습니다.`);
  return [...lines, ''];
}

export function renderSummary({ current, comparison, noPreviousReason, skipped }) {
  const lines = [...headerLines({ current, comparison, noPreviousReason, skipped }), ...classLines(current, comparison), ...survivorLines(current)];
  return `${lines.join('\n')}\n`;
}
