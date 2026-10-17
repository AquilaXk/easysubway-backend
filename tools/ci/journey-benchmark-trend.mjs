#!/usr/bin/env node
// 경로 엔진 성능 벤치마크 추세 관찰 CLI (backend #516).
//
// aggregate       새 JVM 반복의 측정 JSON을 값별 중앙값 한 건으로 합쳐 커밋·러너 정보와 함께 기록한다.
// fetch-previous  직전 성공 run들의 결과를 gh로 내려받아 목록(manifest)으로 남긴다.
// compare         현재 결과를 직전 결과들의 중앙값과 비교해 job summary와 ::warning으로 보여 준다.
//
// 관찰형이다. 임계를 넘어도, 직전 결과가 없거나 손상돼도 종료 코드는 0이다. 현재 측정이 유효하지 않거나
// 입력 인자가 잘못된 경우처럼 측정 자체를 믿을 수 없을 때만 실패한다.
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { isMainModule } from '../lib/is-main-module.mjs';
import { compareResults } from './journey-benchmark-compare.mjs';
import { ARTIFACT_PREFIX } from './journey-benchmark-previous.mjs';
import { aggregateRepeats, buildResult, fail, parseResult, readContext } from './journey-benchmark-result.mjs';
import { BENCHMARK_TITLE, annotation, renderSummary } from './journey-benchmark-summary.mjs';
import { parseOptions, readManifest, readPreviousResults, runFetchPrevious, single } from './journey-trend-cli.mjs';

const SOURCE = { artifactPrefix: ARTIFACT_PREFIX, parse: parseResult, title: BENCHMARK_TITLE };

function runAggregate(option, env) {
  const measurements = (option.measured ?? []).map((path) => JSON.parse(readFileSync(path, 'utf8')));
  const built = buildResult({ measurement: aggregateRepeats(measurements), repeats: measurements.length, context: readContext(env) });
  writeFileSync(single(option, 'output'), `${JSON.stringify(built, null, 2)}\n`);
}

function runCompare(option, stdout) {
  const current = parseResult(readFileSync(single(option, 'current'), 'utf8'), 'current result');
  const manifest = readManifest(single(option, 'previous-manifest'));
  const skipped = [...manifest.skipped];
  const previous = readPreviousResults({ paths: manifest.results, skipped, stdout, parse: SOURCE.parse, title: SOURCE.title });
  const comparison = previous.length === 0 ? null : compareResults({ current, previous });
  const noPreviousReason = manifest.reason ?? '사용할 수 있는 직전 결과가 없습니다.';
  appendFileSync(single(option, 'summary'), renderSummary({ current, comparison, noPreviousReason, skipped }));
  for (const regression of comparison?.regressions ?? []) stdout.write(annotation(regression.message));
}

export function main(argv = process.argv.slice(2), env = process.env, stdout = process.stdout, gh = undefined) {
  const [command, ...rest] = argv, option = parseOptions(rest);
  if (command === 'aggregate') return runAggregate(option, env);
  if (command === 'fetch-previous') return runFetchPrevious({ option, stdout, gh, source: SOURCE });
  if (command === 'compare') return runCompare(option, stdout);
  return fail('usage is aggregate, fetch-previous or compare');
}

if (isMainModule(import.meta.url)) {
  try { main(); } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
}
