#!/usr/bin/env node
// Backend CI 실행 범위 분류와 결과 집계 (Backend #416).
//
// pull_request에서 PR이 바꾼 경로(merge commit의 first parent 대비)가 모두 아래 LIGHT 규칙에
// 들면 무거운 단계(Gradle test·JaCoCo·coverage gate·SpotBugs·Sonar·image preflight)를 건너뛰고
// Node 계약 단계만 실행한다. 규칙에 없는 경로는 무거운 단계를 실행한다(보수적 기본값).
// push·workflow_dispatch는 항상 전체 실행이다. 집계 job `Backend CI`는 verify-jobs로
// 실행 대상 job이 모두 success인지, 범위 밖이면 skipped인지 확인하고 skipped-by-scope를 기록한다.
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { isMainModule } from '../lib/is-main-module.mjs';

const fail = (message) => { throw new Error(`backend CI scope: ${message}`); };

// Node 계약 테스트만으로 검증되는 경로. 여기 없는 경로는 모두 무거운 단계 대상이다.
export const LIGHT_PATHS = [
  { pattern: /^README(?:\.ko)?\.md$/, reason: 'product README' },
  { pattern: /^\.github\/(?:ISSUE_TEMPLATE|PULL_REQUEST_TEMPLATE)\/[^\0]+$/, reason: 'GitHub templates' },
  { pattern: /^\.github\/pull_request_template\.md$/, reason: 'GitHub templates' },
  { pattern: /^\.github\/dependabot\.yml$/, reason: 'Dependabot configuration' },
  { pattern: /^\.github\/workflows\/(?!ci\.ya?ml$)[^/]+\.ya?ml$/, reason: 'workflow other than Backend CI (Node workflow contracts)' },
  { pattern: /^tools\/(?:realtime|routes|qa|repo)\/[^\0]+$/, reason: 'Node-only tooling' },
  { pattern: /^tools\/ci\/(?!backend-)[^/]+$/, reason: 'Node-only CI tooling outside backend gates' },
  { pattern: /^contracts\/documentation\/[^\0]+$/, reason: 'documentation fragment' },
  { pattern: /^package\.json$/, reason: 'Node test scripts' },
  { pattern: /^\.nvmrc$/, reason: 'Node version' },
];

export function classifyPath(path) {
  if (typeof path !== 'string' || path.length === 0 || path.startsWith('/') || path.split('/').includes('..')) fail(`invalid changed path ${JSON.stringify(path)}`);
  const light = LIGHT_PATHS.find(({ pattern }) => pattern.test(path));
  return light ? { path, heavy: false, reason: light.reason } : { path, heavy: true, reason: 'backend build, gate, or unclassified path' };
}

export function classifyChanges({ event, paths }) {
  if (event !== 'pull_request') return { heavy: true, reason: `${event} always runs the full Backend CI`, paths: [] };
  if (!Array.isArray(paths)) fail('changed paths are required for pull_request');
  if (paths.length === 0) return { heavy: true, reason: 'no changed paths were detected; running the full Backend CI', paths: [] };
  const classified = paths.map(classifyPath);
  const heavy = classified.some((entry) => entry.heavy);
  return { heavy, reason: heavy ? 'backend build, gate, or unclassified path changed' : 'only Node-contract paths changed', paths: classified };
}

const git = (args) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });

export function changedPathsFromMergeCommit(run = git) {
  const parents = run(['rev-list', '--parents', '-n', '1', 'HEAD']).trim().split(' ');
  if (parents.length !== 3 || !parents.every((sha) => /^[a-f0-9]{40}$/.test(sha))) fail('pull_request checkout must be a two-parent merge commit');
  return run(['diff', '--name-only', '--no-renames', '-z', 'HEAD^1', 'HEAD']).split('\0').filter((path) => path.length > 0);
}

const RESULTS = ['success', 'failure', 'cancelled', 'skipped'];
export function verifyJobs({ event, needs }) {
  if (needs === null || typeof needs !== 'object' || Array.isArray(needs)) fail('needs must be an object');
  const result = (job) => {
    const value = needs[job]?.result;
    if (!RESULTS.includes(value)) fail(`${job} result is missing`);
    return value;
  };
  if (result('scope') !== 'success') fail('scope job did not succeed');
  const heavyOutput = needs.scope.outputs?.heavy;
  if (heavyOutput !== 'true' && heavyOutput !== 'false') fail('scope heavy output is missing');
  const heavy = heavyOutput === 'true';
  if (event !== 'pull_request' && !heavy) fail(`${event} must run the full Backend CI`);
  if (result('backend-contracts') !== 'success') fail('backend-contracts did not succeed');
  const lines = ['## Backend CI scope', '', `- event: ${event}`, `- heavy: ${heavy}`, '- backend-contracts: success'];
  for (const job of ['backend-test', 'backend-spotbugs']) {
    const value = result(job);
    if (heavy && value !== 'success') fail(`${job} is in scope but ended ${value}`);
    if (!heavy && value !== 'skipped') fail(`${job} is out of scope but ended ${value}`);
    lines.push(`- ${job}: ${heavy ? value : 'skipped-by-scope'}`);
  }
  return { heavy, summary: `${lines.join('\n')}\n` };
}

const options = (values) => {
  if (values.length % 2 !== 0) fail('named options required');
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    if (!/^--[a-z][a-z-]*$/.test(values[index]) || Object.hasOwn(result, values[index].slice(2))) fail('options must be unique --name value pairs');
    result[values[index].slice(2)] = values[index + 1];
  }
  return result;
};

export function main(argv = process.argv.slice(2)) {
  const [command, ...rest] = argv, option = options(rest);
  for (const key of ['event', 'summary']) if (!option[key]) fail(`--${key} is required`);
  if (command === 'classify') {
    if (!option.output) fail('--output is required');
    const decision = classifyChanges({ event: option.event, paths: option.event === 'pull_request' ? changedPathsFromMergeCommit() : undefined });
    appendFileSync(option.output, `heavy=${decision.heavy}\n`);
    const rows = decision.paths.map(({ path, heavy, reason }) => `- ${heavy ? 'run' : 'node-only'}: \`${path}\` (${reason})`);
    appendFileSync(option.summary, `${['## Backend CI scope', '', `- heavy: ${decision.heavy} — ${decision.reason}`, ...rows].join('\n')}\n`);
    return;
  }
  if (command === 'verify-jobs') {
    if (!option.needs) fail('--needs is required');
    let needs;
    try { needs = JSON.parse(option.needs); } catch { fail('--needs must be JSON'); }
    appendFileSync(option.summary, verifyJobs({ event: option.event, needs }).summary);
    return;
  }
  fail('usage is classify or verify-jobs');
}

if (isMainModule(import.meta.url)) {
  try { main(); } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
}
