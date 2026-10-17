#!/usr/bin/env node
// 경로 검색 부하·장애 시나리오 판정 (backend #520).
//
// k6 요약에서 뽑은 입력(journey-load-contract.mjs의 distillK6Summary)을 읽어 정확성 계약과 지연 한도로 판정한다.
// 정확성 계약 위반과 측정 불성립은 failures로 종료 코드 1이다. 지연 한도 초과는 warnings로 ::warning만 내고 성공이다(관찰형).
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { isMainModule } from '../lib/is-main-module.mjs';
import { annotation } from './journey-benchmark-summary.mjs';
import { FACILITY_MODES, FACILITY_MODE_LABELS, LOAD_PLAN } from './journey-load-contract.mjs';

const TITLE = '경로 검색 부하·장애 시나리오';
const CLEAN_PHASES = ['steady', 'recovered'];
/**
 * 지연 한도 초과는 공유 러너 잡음일 수 있어 경고만 한다. 한도는 steady·recovered 단계의 p95다.
 * 25ms는 로컬 기준 임시값이었다. 호스티드 러너 첫 실측(backend journey-load run 39024609532, 200 rps)에서 steady p95는
 * default 27.4ms·required 21.2ms(p99 55.0ms·48.8ms), recovered p95는 10.5ms·6.3ms(p99 23.7ms·11.2ms)였다.
 * 가장 느린 p95(27.4ms)의 약 1.5배인 40ms로 올린다. 측정이 1회뿐이라 계속 경고(비차단)로만 쓴다.
 * 호스티드 러너 실측이 5회 이상 쌓이면 단계별 최대 p95의 1.5배 안에서 다시 정하고, 경고가 반복되면 원인부터 조사한다.
 */
export const CLEAN_PHASE_P95_LIMIT_MS = 40;
/**
 * 부하 유지의 허용 비율이다. 단계 요청 수가 계획의 이 비율 이상이고 버려진 반복이 전체 계획의 (1 - 비율) 이하면 측정이 성립한 것으로 본다.
 * k6 constant-arrival-rate는 VU가 부족하거나 스케줄러가 밀리면 반복을 dropped_iterations로 버린다. 호스티드 러너 첫 실측에서
 * 단계별 부족은 0~11건(최대 0.28%), 버려진 반복은 계획 12000건 중 21·22건(0.18%)이었고 정확성 위반은 없었다. 이 정도는 스케줄링
 * 잡음이므로 1%까지 허용한다. 버려진 반복은 요청이 되지 않아 판정 대상이 아니며, 그 수는 verdict와 summary에 적는다.
 * 그 위반 검사는 실제로 도착한 요청 전부에 그대로 적용되므로 부족분이 정확성 판정을 피하는 통로가 되지 않는다.
 */
export const LOAD_SUSTAIN_MIN_RATIO = 0.99;
const CONTROL_CALLS_OUTSIDE_SWAP = 2;

const fail = (message) => { throw new Error(`journey load: ${message}`); };

const finding = (code, message) => ({ code, message });

function phaseFindings(name, phase, plan, facilityMode) {
  const { durationSeconds } = plan.phases.find((candidate) => candidate.name === name);
  const planned = plan.requestsPerSecond * durationSeconds;
  const findings = [];
  if (phase.requests === 0) return [finding('PHASE_NOT_RUN', `${name} 단계에서 요청이 하나도 도착하지 않았습니다.`)];
  if (phase.requests < planned * LOAD_SUSTAIN_MIN_RATIO) {
    findings.push(finding('LOAD_SHORTFALL', `${name} 단계 요청 ${phase.requests}건이 계획 ${planned}건보다 적습니다.`));
  }
  if (phase.lateIterations > 0) findings.push(finding('LOAD_NOT_SUSTAINED', `${name} 단계 창이 끝난 뒤에 시작한 반복 ${phase.lateIterations}건은 판정하지 않았습니다.`));
  for (const [violation, count] of Object.entries(phase.violations)) {
    if (count > 0) findings.push(finding('CONTRACT_VIOLATION', `${name} 단계에서 ${violation} ${count}건`));
  }
  // 시설 상태 필수 구성의 장애 단계에서 5xx는 명시 실패 응답(FACILITY_STATUS_UNAVAILABLE 503)뿐이어야 한다. 그 밖의 5xx는 모두 오류다.
  const allowed5xx = name === 'outage' && facilityMode === FACILITY_MODES.REQUIRED ? phase.explicitFailures : 0;
  if (phase.status5xx !== allowed5xx) findings.push(finding('SERVER_ERROR', `${name} 단계 5xx ${phase.status5xx}건 (허용 ${allowed5xx}건)`));
  if (phase.status429 > 0) findings.push(finding('RATE_LIMITED', `${name} 단계에서 429 ${phase.status429}건`));
  return findings;
}

/** 주입이 걸리지 않은 실행이 통과하지 못하게, 시나리오가 의도한 상태가 실제로 관측됐는지 본다. */
function observationFindings(phases, facilityMode) {
  const findings = [];
  if (phases.swap.requests > 0 && (phases.swap.bundleA === 0 || phases.swap.bundleB === 0)) {
    findings.push(finding('SWAP_NOT_OBSERVED', `swap 단계에서 두 팩의 응답이 모두 관측되어야 합니다(a ${phases.swap.bundleA}건, b ${phases.swap.bundleB}건).`));
  }
  const { requests, explicitFailures, disclosedUnobserved } = phases.outage;
  const explicitRequired = facilityMode === FACILITY_MODES.REQUIRED;
  if (requests > 0 && ((explicitRequired && explicitFailures === 0) || disclosedUnobserved === 0)) {
    const expectation = explicitRequired ? '명시 실패와 시설 상태 미적용 응답이 모두' : '시설 상태 미적용 응답이';
    findings.push(finding('OUTAGE_NOT_OBSERVED', `outage 단계에서 ${expectation} 관측되어야 합니다(명시 실패 ${explicitFailures}건, 시설 상태 미적용 응답 ${disclosedUnobserved}건).`));
  }
  return findings;
}

const plannedRequests = (plan) => plan.phases.reduce((sum, { durationSeconds }) => sum + plan.requestsPerSecond * durationSeconds, 0);
const allowedDroppedIterations = (plan) => Math.floor(plannedRequests(plan) * (1 - LOAD_SUSTAIN_MIN_RATIO));

function runFindings(run, plan) {
  const findings = [];
  const allowedDropped = allowedDroppedIterations(plan);
  if (run.droppedIterations > allowedDropped) findings.push(finding('LOAD_NOT_SUSTAINED', `목표 도착률을 유지하지 못해 반복 ${run.droppedIterations}건이 버려졌습니다(허용 ${allowedDropped}건).`));
  const expectedControlCalls = plan.controls.swap.flips + CONTROL_CALLS_OUTSIDE_SWAP;
  if (run.controls.failures > 0 || run.controls.calls !== expectedControlCalls) {
    findings.push(finding('CONTROL_FAILED', `제어 호출 ${run.controls.calls}건 중 실패 ${run.controls.failures}건 (기대 ${expectedControlCalls}건)`));
  }
  return findings;
}

function latencyWarnings(phases) {
  return CLEAN_PHASES.filter((name) => phases[name].latencyMs !== null && phases[name].latencyMs.p95 > CLEAN_PHASE_P95_LIMIT_MS)
    .map((name) => finding('LATENCY_LIMIT', `${name} 단계 p95 ${phases[name].latencyMs.p95.toFixed(1)}ms가 한도 ${CLEAN_PHASE_P95_LIMIT_MS}ms를 넘었습니다.`));
}

export function judgeRun(run, plan) {
  const { facilityMode } = run;
  if (!Object.values(FACILITY_MODES).includes(facilityMode)) fail(`unknown facility mode ${JSON.stringify(facilityMode)}`);
  const phases = Object.fromEntries(plan.phases.map(({ name, durationSeconds }) => [name, { ...run.phases[name], throughputRps: run.phases[name].requests / durationSeconds }]));
  const failures = [
    ...plan.phases.flatMap(({ name }) => phaseFindings(name, run.phases[name], plan, facilityMode)),
    ...observationFindings(run.phases, facilityMode),
    ...runFindings(run, plan),
  ];
  return { facilityMode, status: failures.length === 0 ? 'PASS' : 'FAIL', failures, warnings: latencyWarnings(run.phases), droppedIterations: run.droppedIterations, allowedDroppedIterations: allowedDroppedIterations(plan), phases };
}

const ms = (value) => (value === null || value === undefined ? '-' : value.toFixed(1));
const violationTotal = (phase) => Object.values(phase.violations).reduce((sum, count) => sum + count, 0);

function phaseRow(name, phase) {
  const latency = phase.latencyMs;
  return `| ${name} | ${phase.requests} | ${phase.throughputRps.toFixed(1)} | ${ms(latency?.p50)} | ${ms(latency?.p95)} | ${ms(latency?.p99)} | ${phase.status5xx} | ${phase.status429} | ${violationTotal(phase)} |`;
}

const contextLine = (context) => (context
  ? `- 실행: ${context.commitSha.slice(0, 7)}${context.runUrl ? ` · [run](${context.runUrl})` : ''}, 목표 ${context.rps} rps`
  : '- 실행: 로컬');

export function renderSummary(verdict, context) {
  const bullet = ({ code, message }) => `- \`${code}\` ${message}`;
  return `${[
    `## ${TITLE} — ${FACILITY_MODE_LABELS[verdict.facilityMode]}`, '',
    `- 판정: **${verdict.status}**`,
    contextLine(context),
    `- 버려진 반복(판정 제외): ${verdict.droppedIterations}건 (허용 ${verdict.allowedDroppedIterations}건, 계획의 ${((1 - LOAD_SUSTAIN_MIN_RATIO) * 100).toFixed(0)}%)`,
    `- 지연 한도(경고, 비차단): steady·recovered 단계 p95 ${CLEAN_PHASE_P95_LIMIT_MS}ms`,
    '', '| 단계 | 요청 | 처리량(rps) | p50(ms) | p95(ms) | p99(ms) | 5xx | 429 | 계약 위반 |', '|---|---|---|---|---|---|---|---|---|',
    ...Object.entries(verdict.phases).map(([name, phase]) => phaseRow(name, phase)),
    '', ...(verdict.failures.length === 0 ? ['실패 항목 없음'] : ['### 실패', ...verdict.failures.map(bullet)]),
    ...(verdict.warnings.length === 0 ? [] : ['', '### 경고', ...verdict.warnings.map(bullet)]),
  ].join('\n')}\n`;
}

export function annotations(verdict) {
  return [
    ...verdict.failures.map(({ code, message }) => annotation(`[${verdict.facilityMode}] ${code} ${message}`, 'error', TITLE)),
    ...verdict.warnings.map(({ code, message }) => annotation(`[${verdict.facilityMode}] ${code} ${message}`, 'warning', TITLE)),
  ].join('');
}

const options = (values) => {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index], value = values[index + 1];
    if (!/^--[a-z][a-z-]*$/.test(key ?? '') || value === undefined) fail('named options required');
    result[key.slice(2)] = value;
  }
  return result;
};

function readDistilled(path) {
  let run;
  try { run = JSON.parse(readFileSync(path, 'utf8')); } catch (error) { fail(`distilled summary is not valid JSON: ${error.message}`); }
  const wellFormed = run?.schemaVersion === 1 && Object.values(FACILITY_MODES).includes(run.facilityMode) && Number.isInteger(run.droppedIterations) && run.controls
    && LOAD_PLAN.phases.every(({ name }) => run.phases?.[name] && run.phases[name].violations);
  if (!wellFormed) fail('distilled summary has an unexpected shape');
  return run;
}

const SHA = /^[0-9a-f]{40}$/;
const SERVER_URL = /^https:\/\/[A-Za-z0-9.-]+$/;
const REPOSITORY = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/;
const RUN_ID = /^[1-9][0-9]*$/;

/** GitHub Actions 밖(로컬)에서는 실행 정보가 없다. 있으면 형식을 검증해 링크로 쓴다. */
function readContext(env, rps) {
  if (!env.GITHUB_SHA) return null;
  const { GITHUB_SHA: commitSha, GITHUB_SERVER_URL: server, GITHUB_REPOSITORY: repository, GITHUB_RUN_ID: runId } = env;
  if (!SHA.test(commitSha) || !SERVER_URL.test(server ?? '') || !REPOSITORY.test(repository ?? '') || !RUN_ID.test(runId ?? '')) fail('GitHub run context has an invalid format');
  return { commitSha, runUrl: `${server}/${repository}/actions/runs/${runId}`, rps };
}

export function main(argv = process.argv.slice(2), env = process.env, stdout = process.stdout) {
  const option = options(argv);
  for (const key of ['distilled', 'output', 'summary']) if (!option[key]) fail(`--${key} is required`);
  const verdict = judgeRun(readDistilled(option.distilled), LOAD_PLAN);
  writeFileSync(option.output, `${JSON.stringify(verdict, null, 2)}\n`);
  appendFileSync(option.summary, renderSummary(verdict, readContext(env, LOAD_PLAN.requestsPerSecond)));
  stdout.write(annotations(verdict));
  return verdict.status === 'PASS' ? 0 : 1;
}

if (isMainModule(import.meta.url)) {
  try { process.exitCode = main(); } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 2; }
}
