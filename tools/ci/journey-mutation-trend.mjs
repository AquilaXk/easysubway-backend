#!/usr/bin/env node
// 경로 판정 클래스 뮤테이션(PIT) 점수 추세 관찰 CLI (backend #523).
//
// summarize       PIT 보고서 디렉터리(mutations.xml, Gradle 태스크가 남긴 실행 설명)를 커밋·러너 정보와 함께 결과 한 건으로 기록한다.
// fetch-previous  직전 성공 run들의 결과를 gh로 내려받아 목록(manifest)으로 남긴다.
// compare         현재 결과를 직전 결과들의 중앙값과 클래스별로 비교해 job summary와 ::warning으로 보여 준다.
//
// 관찰형이다. 점수가 내려가도, 직전 결과가 없거나 손상돼도 종료 코드는 0이다. 현재 측정이 유효하지 않거나
// 입력 인자가 잘못된 경우처럼 측정 자체를 믿을 수 없을 때만 실패한다.
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { isMainModule } from '../lib/is-main-module.mjs';
import { readContext } from './journey-benchmark-result.mjs';
import { annotation } from './journey-benchmark-summary.mjs';
import { compareResults } from './journey-mutation-compare.mjs';
import { buildMutationResult, fail, parseMutationResult, parseMutations } from './journey-mutation-result.mjs';
import { MUTATION_TITLE, renderSummary } from './journey-mutation-summary.mjs';
import { parseOptions, readManifest, readPreviousResults, runFetchPrevious, single } from './journey-trend-cli.mjs';

// workflow의 artifact 이름 `journey-mutation-<sha>`의 접두사. workflow 구조 테스트가 둘의 일치를 확인한다.
export const ARTIFACT_PREFIX = 'journey-mutation-';
// PIT가 보고서 디렉터리에 쓰는 파일 이름과, Gradle init script가 같은 디렉터리에 남기는 실행 설명 파일 이름.
export const REPORT_FILE_NAME = 'mutations.xml';
export const RUN_FILE_NAME = 'journey-mutation-run.json';

const SOURCE = { artifactPrefix: ARTIFACT_PREFIX, parse: parseMutationResult, title: MUTATION_TITLE };

function runSummarize(option, env) {
  const reportDir = single(option, 'report-dir');
  const mutants = parseMutations(readFileSync(join(reportDir, REPORT_FILE_NAME), 'utf8'));
  const run = JSON.parse(readFileSync(join(reportDir, RUN_FILE_NAME), 'utf8'));
  const built = buildMutationResult({ mutants, run, context: readContext(env) });
  writeFileSync(single(option, 'output'), `${JSON.stringify(built, null, 2)}\n`);
}

function runCompare(option, stdout) {
  const current = parseMutationResult(readFileSync(single(option, 'current'), 'utf8'), 'current result');
  const manifest = readManifest(single(option, 'previous-manifest'));
  const skipped = [...manifest.skipped];
  const previous = readPreviousResults({ paths: manifest.results, skipped, stdout, parse: SOURCE.parse, title: SOURCE.title });
  const comparison = previous.length === 0 ? null : compareResults({ current, previous });
  const noPreviousReason = manifest.reason ?? '사용할 수 있는 직전 결과가 없습니다.';
  appendFileSync(single(option, 'summary'), renderSummary({ current, comparison, noPreviousReason, skipped }));
  for (const regression of comparison?.regressions ?? []) stdout.write(annotation(regression.message, 'warning', MUTATION_TITLE));
}

export function main(argv = process.argv.slice(2), env = process.env, stdout = process.stdout, gh = undefined) {
  const [command, ...rest] = argv, option = parseOptions(rest);
  if (command === 'summarize') return runSummarize(option, env);
  if (command === 'fetch-previous') return runFetchPrevious({ option, stdout, gh, source: SOURCE });
  if (command === 'compare') return runCompare(option, stdout);
  return fail('usage is summarize, fetch-previous or compare');
}

if (isMainModule(import.meta.url)) {
  try { main(); } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
}
