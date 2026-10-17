// 추세 관찰 CLI들이 같이 쓰는 인자 해석, 직전 run 내려받기, 직전 결과 목록(manifest) 읽기 (backend #516, #523).
// 벤치마크(journey-benchmark-trend)와 뮤테이션(journey-mutation-trend)이 결과 형식만 달리하고 이 흐름을 공유한다.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname } from 'node:path';
import { fetchPreviousResults } from './journey-benchmark-previous.mjs';
import { annotation } from './journey-benchmark-summary.mjs';

export const fail = (message) => { throw new Error(`journey trend cli: ${message}`); };

const OPTION_NAME = /^--[a-z][a-z-]*$/;
const REPOSITORY = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/;
const SKIP_KINDS = ['expired', 'missing-artifact', 'error', 'unreadable'];

/** `--이름 값` 쌍을 {이름: [값...]}으로 모은다. 같은 이름을 여러 번 줄 수 있다. */
export function parseOptions(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index], value = values[index + 1];
    if (!OPTION_NAME.test(key ?? '') || value === undefined) fail('named options required');
    (result[key.slice(2)] ??= []).push(value);
  }
  return result;
}

export function single(option, key) {
  if (!option[key] || option[key].length !== 1 || option[key][0].length === 0) fail(`--${key} is required once`);
  return option[key][0];
}

/**
 * 직전 성공 run의 결과를 내려받아 manifest로 남긴다. 만료는 보존 기간이 지나면 생기는 예상된 일이라 알림으로만,
 * 그 밖의 원인은 추세가 조용히 멈추지 않도록 경고로 드러낸다.
 * source: { artifactPrefix, parse, title } — 어떤 artifact를 어떤 파서로 검증하고 어떤 제목으로 알릴지.
 */
export function runFetchPrevious({ option, stdout, gh, source: { artifactPrefix, parse, title } }) {
  const repo = single(option, 'repo');
  if (!REPOSITORY.test(repo)) fail('--repo must be owner/name');
  const outcome = fetchPreviousResults({ gh, repo, workflow: single(option, 'workflow'), directory: single(option, 'dir'), artifactPrefix, parse });
  const { results, skipped, reason } = outcome;
  mkdirSync(dirname(single(option, 'output')), { recursive: true });
  writeFileSync(single(option, 'output'), `${JSON.stringify({ results, skipped, reason }, null, 2)}\n`);
  if (outcome.warning) stdout.write(annotation(reason, 'warning', title));
  for (const { runId, kind, message } of skipped) stdout.write(annotation(`run ${runId}: ${message}`, kind === 'expired' ? 'notice' : 'warning', title));
}

export function readManifest(path) {
  let manifest;
  try { manifest = JSON.parse(readFileSync(path, 'utf8')); } catch (error) { fail(`previous manifest is not valid JSON: ${error.message}`); }
  const wellFormed = manifest !== null && typeof manifest === 'object'
    && Array.isArray(manifest.results) && manifest.results.every((item) => typeof item === 'string')
    && Array.isArray(manifest.skipped)
    && manifest.skipped.every((item) => item && typeof item.runId === 'string' && SKIP_KINDS.includes(item.kind) && typeof item.message === 'string')
    && (manifest.reason === null || typeof manifest.reason === 'string');
  if (!wellFormed) fail('previous manifest has an unexpected shape');
  return manifest;
}

/** 직전 결과를 읽지 못해도 현재 측정은 유효하므로 job을 막지 않는다. 막으면 새 성공 run이 없어 다음 비교도 영원히 막힌다. */
export function readPreviousResults({ paths, skipped, stdout, parse, title }) {
  const previous = [];
  for (const path of paths) {
    try {
      previous.push(parse(readFileSync(path, 'utf8'), 'previous result'));
    } catch (error) {
      const runId = basename(dirname(path));
      skipped.push({ runId, kind: 'unreadable', message: `직전 결과를 읽지 못했습니다: ${error.message}` });
      stdout.write(annotation(`run ${runId}: 직전 결과를 읽지 못했습니다: ${error.message}`, 'warning', title));
    }
  }
  return previous;
}
