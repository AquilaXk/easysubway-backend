// 경로 엔진 벤치마크 추세 요약의 값 서식과 Markdown 이스케이프 (backend #516).
import { WALL_CLOCK } from './journey-benchmark-result.mjs';

export const formatNanos = (nanos) => (nanos < 1_000_000 ? `${(nanos / 1_000).toFixed(1)} µs` : `${(nanos / 1_000_000).toFixed(2)} ms`);
export const formatBytes = (bytes) => (bytes < 1_048_576 ? `${(bytes / 1_024).toFixed(1)} KiB` : `${(bytes / 1_048_576).toFixed(2)} MiB`);
export const formatValue = (metric, value) => (metric.kind === WALL_CLOCK ? formatNanos(value) : formatBytes(value));
export const formatChange = (ratio) => `${ratio >= 1 ? '+' : '-'}${(Math.abs(ratio - 1) * 100).toFixed(1)}%`;
export const formatGiB = (bytes) => `${(bytes / 1_073_741_824).toFixed(1)} GiB`;
export const shortSha = (sha) => sha.slice(0, 7);

/** 검증되지 않은 문자열을 한 줄 Markdown 텍스트로 만든다: 줄바꿈을 접고 구조 문자를 이스케이프한다. */
export const escapeMarkdown = (value) => String(value).replace(/\s+/g, ' ').trim().replace(/[\\`*_{}[\]()<>#+!|~&]/g, '\\$&');

export const truncate = (text, limit) => (text.length > limit ? `${text.slice(0, limit - 1)}…` : text);
