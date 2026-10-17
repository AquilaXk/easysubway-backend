// 경로 판정 클래스 뮤테이션 점수 추세 비교 (backend #523): 현재 결과를 직전 성공 run들의 중앙값과 클래스별로 견준다.
import { median } from './journey-benchmark-result.mjs';
import { fail } from './journey-mutation-result.mjs';
import { formatPercent, formatPoints } from './journey-mutation-format.mjs';

// 같은 코드·같은 테스트에서 PIT 점수는 거의 흔들리지 않는다. 다만 시간 초과(TIMED_OUT)와 사망(KILLED)은 둘 다 탐지로 세므로 그 사이 변동은 점수에 안 보인다.
// 0.5%p는 반올림 잡음을 막는 하한이고, 클래스에서 mutant 하나를 놓치면(200개 중 1개 = 0.5%p, 그보다 작은 클래스는 더 큰 폭) 경고가 난다.
// 실제 편차는 아직 측정한 적이 없으므로 판정이 아니라 신호다. 비차단 관찰이 쌓이면 재조정한다(#523).
export const SCORE_DROP_TOLERANCE_POINTS = 0.5;

const POINT_DECIMALS = 100;
const roundPoints = (value) => Math.round(value * POINT_DECIMALS) / POINT_DECIMALS;
const missedOf = (counts) => counts.survived + counts.noCoverage;
const versionKey = ({ run }) => `${run.pitestVersion}/${run.junit5PluginVersion}`;

function regressionMessage({ className, baselineScore, score, delta }) {
  return `${className}: 뮤테이션 점수 기준 중앙값 ${formatPercent(baselineScore)} → 현재 ${formatPercent(score)} (${formatPoints(delta)}, 허용 하락 ${SCORE_DROP_TOLERANCE_POINTS.toFixed(1)}%p)`;
}

function compareClass(className, counts, baselineCounts) {
  const baselineScore = median(baselineCounts.map(({ score }) => score));
  const delta = roundPoints(counts.score - baselineScore);
  const regressed = -delta > SCORE_DROP_TOLERANCE_POINTS;
  const row = { className, status: 'compared', baselineScore, score: counts.score, delta, baselineMissed: median(baselineCounts.map(missedOf)), missed: missedOf(counts), regressed };
  return regressed ? { ...row, message: regressionMessage(row) } : row;
}

const unmatched = (className, status, counts) => ({ className, status, score: counts?.score, missed: counts === undefined ? undefined : missedOf(counts), regressed: false });

/** 같은 PIT·JUnit 플러그인 버전으로 잰 직전 run만 기준이 된다. 버전이 달라지면 mutator 구성이 달라 mutant 집합이 다르다. */
export function compareResults({ current, previous }) {
  if (previous.length === 0) fail('at least one previous result is required');
  const runs = previous.filter((run) => versionKey(run) === versionKey(current));
  const notes = [];
  if (runs.length < previous.length) {
    notes.push(`직전 run ${previous.length - runs.length}개는 PIT 버전 또는 JUnit 플러그인 버전이 현재(${versionKey(current)})와 달라 기준에서 제외했습니다.`);
  }
  const classes = Object.entries(current.classes).map(([className, counts]) => {
    if (runs.length === 0) return unmatched(className, 'method-changed', counts);
    const baselineCounts = runs.filter((run) => run.classes[className] !== undefined).map((run) => run.classes[className]);
    return baselineCounts.length === 0 ? unmatched(className, 'added', counts) : compareClass(className, counts, baselineCounts);
  });
  const baselineNames = new Set(runs.flatMap((run) => Object.keys(run.classes)));
  for (const className of [...baselineNames].sort()) if (current.classes[className] === undefined) classes.push(unmatched(className, 'removed'));
  return { classes, regressions: classes.filter(({ regressed }) => regressed), notes, baselineContexts: runs.map((run) => run.context), baselineRuns: runs.length };
}
