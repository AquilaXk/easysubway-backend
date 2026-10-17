// 경로 판정 클래스 뮤테이션 점수 추세 비교 (backend #523): 현재 결과를 직전 성공 run들의 중앙값과 클래스별로 견준다.
import { median } from './journey-benchmark-result.mjs';
import { fail } from './journey-mutation-result.mjs';
import { formatPercent, formatPoints } from './journey-mutation-format.mjs';

// 같은 코드·같은 테스트에서 PIT 점수는 거의 흔들리지 않는다. 다만 시간 초과(TIMED_OUT)와 테스트 실패(KILLED)는 둘 다 탐지로 세므로 그 사이 변동은 점수에 안 보인다.
// 0.5%p는 반올림 잡음을 막는 하한이다. 하락폭이 허용값과 같으면 경고하지 않고(엄격한 초과만 경고), 그래서 점수만 보면
// 클래스에서 mutant 하나를 놓칠 때 200개 미만의 클래스에서만 경고가 난다. 큰 클래스의 한 건은 아래 생존 증가 판정이 잡는다.
// 실제 편차는 아직 측정한 적이 없으므로 판정이 아니라 신호다. 비차단 관찰이 쌓이면 재조정한다(#523).
export const SCORE_DROP_TOLERANCE_POINTS = 0.5;

const POINT_DECIMALS = 100;
const roundPoints = (value) => Math.round(value * POINT_DECIMALS) / POINT_DECIMALS;
const missedOf = (counts) => counts.survived + counts.noCoverage;
// 버전과 측정 설정(대상·테스트·변이 구성·시간 제한을 묶은 해시)이 같아야 같은 mutant 집합을 잰 결과다.
const CONFIG_HASH_DISPLAY_LENGTH = 8;
const versionKey = ({ run }) => `${run.pitestVersion}/${run.junit5PluginVersion}/${run.configHash.slice(0, CONFIG_HASH_DISPLAY_LENGTH)}`;

const scoreMessage = ({ baselineScore, score, delta }) => `뮤테이션 점수 기준 중앙값 ${formatPercent(baselineScore)} → 현재 ${formatPercent(score)} (${formatPoints(delta)}, 허용 하락 ${SCORE_DROP_TOLERANCE_POINTS.toFixed(1)}%p)`;
const survivorMessage = ({ baselineSurvived, survived }) => `생존 mutant 기준 중앙값 ${baselineSurvived}건 → 현재 ${survived}건`;

function compareClass(className, counts, baselineCounts) {
  const baselineScore = median(baselineCounts.map(({ score }) => score));
  const delta = roundPoints(counts.score - baselineScore);
  const row = { className, status: 'compared', baselineScore, score: counts.score, delta, baselineSurvived: median(baselineCounts.map(({ survived }) => survived)), survived: counts.survived, missed: missedOf(counts) };
  const reasons = [
    ...(-delta > SCORE_DROP_TOLERANCE_POINTS ? [scoreMessage(row)] : []),
    ...(row.survived > row.baselineSurvived ? [survivorMessage(row)] : []),
  ];
  return reasons.length === 0 ? { ...row, regressed: false } : { ...row, regressed: true, message: `${className}: ${reasons.join(' · ')}` };
}

const unmatched = (className, status, counts) => ({ className, status, score: counts?.score, missed: counts === undefined ? undefined : missedOf(counts), regressed: false });

/** 같은 PIT·JUnit 플러그인 버전과 측정 설정으로 잰 직전 run만 기준이 된다. 둘 중 하나가 달라지면 mutant 집합이 다르다. */
export function compareResults({ current, previous }) {
  if (previous.length === 0) fail('at least one previous result is required');
  const runs = previous.filter((run) => versionKey(run) === versionKey(current));
  const notes = [];
  if (runs.length < previous.length) {
    notes.push(`직전 run ${previous.length - runs.length}개는 PIT·JUnit 플러그인 버전 또는 측정 설정이 현재(${versionKey(current)})와 달라 기준에서 제외했습니다.`);
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
