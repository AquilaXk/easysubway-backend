#!/usr/bin/env node
// 경로 엔진 성능 벤치마크 추세 관찰 CLI (backend #516).
//
// aggregate       새 JVM 반복의 측정 JSON을 값별 중앙값 한 건으로 합쳐 커밋·러너 정보와 함께 기록한다.
// fetch-previous  직전 성공 run들의 결과를 gh로 내려받아 목록(manifest)으로 남긴다.
// compare         현재 결과를 직전 결과들의 중앙값과 비교해 job summary와 ::warning으로 보여 준다.
//
// 관찰형이다. 임계를 넘어도, 직전 결과가 없거나 손상돼도 종료 코드는 0이다. 현재 측정이 유효하지 않거나
// 입력 인자가 잘못된 경우처럼 측정 자체를 믿을 수 없을 때만 실패한다.
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname } from 'node:path';
import { isMainModule } from '../lib/is-main-module.mjs';
import { compareResults } from './journey-benchmark-compare.mjs';
import { fetchPreviousResults } from './journey-benchmark-previous.mjs';
import { aggregateRepeats, buildResult, fail, parseResult, readContext } from './journey-benchmark-result.mjs';
import { annotation, renderSummary } from './journey-benchmark-summary.mjs';

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
const REPOSITORY = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/;
const SKIP_KINDS = ['expired', 'missing-artifact', 'error', 'unreadable'];

function runAggregate(option, env) {
  const measurements = (option.measured ?? []).map((path) => JSON.parse(readFileSync(path, 'utf8')));
  const built = buildResult({ measurement: aggregateRepeats(measurements), repeats: measurements.length, context: readContext(env) });
  writeFileSync(single(option, 'output'), `${JSON.stringify(built, null, 2)}\n`);
}

function runFetchPrevious(option, stdout, gh) {
  const repo = single(option, 'repo');
  if (!REPOSITORY.test(repo)) fail('--repo must be owner/name');
  const outcome = fetchPreviousResults({ gh, repo, workflow: single(option, 'workflow'), directory: single(option, 'dir') });
  const { results, skipped, reason } = outcome;
  mkdirSync(dirname(single(option, 'output')), { recursive: true });
  writeFileSync(single(option, 'output'), `${JSON.stringify({ results, skipped, reason }, null, 2)}\n`);
  if (outcome.warning) stdout.write(annotation(reason));
  // 만료는 보존 기간이 지나면 생기는 예상된 일이라 알림으로만, 그 밖의 원인은 추세가 조용히 멈추지 않도록 경고로 드러낸다.
  for (const { runId, kind, message } of skipped) stdout.write(annotation(`run ${runId}: ${message}`, kind === 'expired' ? 'notice' : 'warning'));
}

function readManifest(path) {
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
function readPreviousResults(paths, skipped, stdout) {
  const previous = [];
  for (const path of paths) {
    try {
      previous.push(parseResult(readFileSync(path, 'utf8'), 'previous result'));
    } catch (error) {
      const runId = basename(dirname(path));
      skipped.push({ runId, kind: 'unreadable', message: `직전 결과를 읽지 못했습니다: ${error.message}` });
      stdout.write(annotation(`run ${runId}: 직전 결과를 읽지 못했습니다: ${error.message}`));
    }
  }
  return previous;
}

function runCompare(option, stdout) {
  const current = parseResult(readFileSync(single(option, 'current'), 'utf8'), 'current result');
  const manifest = readManifest(single(option, 'previous-manifest'));
  const skipped = [...manifest.skipped];
  const previous = readPreviousResults(manifest.results, skipped, stdout);
  const comparison = previous.length === 0 ? null : compareResults({ current, previous });
  const noPreviousReason = manifest.reason ?? '사용할 수 있는 직전 결과가 없습니다.';
  appendFileSync(single(option, 'summary'), renderSummary({ current, comparison, noPreviousReason, skipped }));
  for (const regression of comparison?.regressions ?? []) stdout.write(annotation(regression.message));
}

export function main(argv = process.argv.slice(2), env = process.env, stdout = process.stdout, gh = undefined) {
  const [command, ...rest] = argv, option = options(rest);
  if (command === 'aggregate') return runAggregate(option, env);
  if (command === 'fetch-previous') return runFetchPrevious(option, stdout, gh);
  if (command === 'compare') return runCompare(option, stdout);
  return fail('usage is aggregate, fetch-previous or compare');
}

if (isMainModule(import.meta.url)) {
  try { main(); } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
}
