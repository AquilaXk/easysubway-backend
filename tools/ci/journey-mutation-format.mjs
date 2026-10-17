// 뮤테이션 추세 요약과 경고 문구의 값 서식 (backend #523).
export const formatPercent = (score) => `${score.toFixed(1)}%`;
// 부호를 항상 붙여 증감을 한눈에 보이게 한다. 0은 부호 없이 쓴다.
export const formatPoints = (delta) => `${delta > 0 ? '+' : ''}${delta.toFixed(1)}%p`;

const SECONDS_PER_MINUTE = 60;
export const formatDuration = (seconds) => (seconds < SECONDS_PER_MINUTE ? `${seconds}초` : `${Math.floor(seconds / SECONDS_PER_MINUTE)}분 ${seconds % SECONDS_PER_MINUTE}초`);
