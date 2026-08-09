#!/usr/bin/env node
import { lstatSync, readFileSync } from 'node:fs';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const fail = (message) => { throw new Error(`invalid OSV results: ${message}`); };
const policyFail = (message) => { throw new Error(`invalid CI execution control: ${message}`); };
const exactKeys = (value, keys, label) => {
  if (!isObject(value)) policyFail(`${label} must be an object`);
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    policyFail(`${label} keys must be exactly ${expected.join(', ')}`);
  }
};
const requireText = (text, fragment) => {
  if (typeof text !== 'string' || !text.includes(fragment)) policyFail(`workflow missing ${JSON.stringify(fragment)}`);
};
const stepBlock = (workflow, name) => {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`^      - name: ${escaped}\\n([\\s\\S]*?)(?=^      - name:|^  [a-z][a-z0-9-]+:|$(?![\\s\\S]))`, 'm').exec(workflow);
  if (!match) policyFail(`workflow missing step ${name}`);
  return match[0];
};
const requireStep = (workflow, name, fragments, forbidden = []) => {
  const block = stepBlock(workflow, name);
  for (const fragment of fragments) requireText(block, fragment);
  for (const fragment of forbidden) if (block.includes(fragment)) policyFail(`step ${name} must not contain ${JSON.stringify(fragment)}`);
};
const configuredLockfiles = ['backend/gradle.lockfile', 'tools/qa/package-lock.json'];

export function loadConfiguredOsvLockfiles() {
  let policy;
  try { policy = JSON.parse(readFileSync(new URL('../../backend/quality/ci-execution-control.json', import.meta.url), 'utf8')); } catch { fail('machine policy cannot be read'); }
  if (!isObject(policy) || !isObject(policy.osv) || !Array.isArray(policy.osv.lockfiles) || policy.osv.lockfiles.join('|') !== configuredLockfiles.join('|')) {
    fail('machine policy lockfiles mismatch');
  }
  return [...policy.osv.lockfiles];
}

export function validateCiExecutionControl(policy, workflow) {
  exactKeys(policy, ['schemaVersion', 'gateId', 'issue', 'requiredContext', 'events', 'timeouts', 'durationEvidence', 'osv'], 'root');
  if (policy.schemaVersion !== 1 || policy.gateId !== 'backend-ci-execution-control' || policy.issue !== 'https://github.com/AquilaXk/easysubway-backend/issues/87' || policy.requiredContext !== 'Dependency Vulnerability Scan / osv-scan') {
    policyFail('root identity mismatch');
  }
  exactKeys(policy.events, ['pull_request', 'push', 'workflow_dispatch'], 'events');
  const prGroup = "${{ github.workflow }}-${{ github.event.repository.full_name }}-pr-${{ github.event.pull_request.number }}";
  for (const [event, expected] of Object.entries({
    pull_request: { group: prGroup, cancelInProgress: true },
    push: { branch: 'main', group: "${{ github.workflow }}-push-${{ github.run_id }}", cancelInProgress: false },
    workflow_dispatch: { group: "${{ github.workflow }}-workflow_dispatch-${{ github.run_id }}", cancelInProgress: false, operatorOnly: true },
  })) {
    exactKeys(policy.events[event], Object.keys(expected), `events.${event}`);
    for (const [key, value] of Object.entries(expected)) {
      if (policy.events[event][key] !== value) policyFail(`events.${event}.${key} mismatch`);
    }
  }
  exactKeys(policy.timeouts, ['backendCiMinutes', 'osvMinutes'], 'timeouts');
  if (policy.timeouts.backendCiMinutes !== 30 || policy.timeouts.osvMinutes !== 10) policyFail('timeout mismatch');
  exactKeys(policy.durationEvidence, ['representativeRuns', 'cacheMiss', 'reviewTrigger'], 'durationEvidence');
  if (!Array.isArray(policy.durationEvidence.representativeRuns) || policy.durationEvidence.representativeRuns.length !== 3 || policy.durationEvidence.cacheMiss !== 'unknown') {
    policyFail('duration evidence mismatch');
  }
  const representativeRuns = [
    { run: 31289484933, event: 'pull_request', backendCi: '11m57s', osv: '24s', gradleCache: 'hit' },
    { run: 31288974058, event: 'pull_request', backendCi: '10m07s', osv: '30s', gradleCache: 'hit' },
    { run: 31275736279, event: 'push', backendCi: '11m03s', osv: 'unknown', gradleCache: 'hit' },
  ];
  for (const [index, expected] of representativeRuns.entries()) {
    exactKeys(policy.durationEvidence.representativeRuns[index], Object.keys(expected), `durationEvidence.representativeRuns[${index}]`);
    for (const [key, value] of Object.entries(expected)) if (policy.durationEvidence.representativeRuns[index][key] !== value) policyFail(`duration evidence run ${index} mismatch`);
  }
  const reviewTrigger = { representativeGreenCacheMissRuns: 3, backendCiOverMinutes: 20, osvOverMinutes: 5, normalRunTimeout: true };
  exactKeys(policy.durationEvidence.reviewTrigger, Object.keys(reviewTrigger), 'durationEvidence.reviewTrigger');
  for (const [key, value] of Object.entries(reviewTrigger)) if (policy.durationEvidence.reviewTrigger[key] !== value) policyFail(`duration evidence review trigger ${key} mismatch`);
  exactKeys(policy.osv, ['disposition', 'lockfiles', 'scannerContinueOnErrorSteps', 'scanner', 'checkout', 'artifact', 'sarif'], 'osv');
  if (!Array.isArray(policy.osv.lockfiles) || policy.osv.lockfiles.join('|') !== 'backend/gradle.lockfile|tools/qa/package-lock.json' || !Array.isArray(policy.osv.scannerContinueOnErrorSteps) || policy.osv.scannerContinueOnErrorSteps.join('|') !== 'Scan immutable PR base|Scan tested PR head|Scan immutable dispatch SHA') {
    policyFail('OSV disposition mismatch');
  }
  const pins = { scanner: '8dc09193bb540e09b23da07ad7e30bd33bf87018', checkout: '8e8c483db84b4bee98b60c0593521ed34d9990e8', artifact: 'bbbca2ddaa5d8feaa63e36b76fdaad77386f024f', sarif: 'cdefb33c0f6224e58673d9004f47f7cb3e328b89' };
  if (policy.osv.disposition !== 'REPLACE_WITH_LOCAL_BOUNDED_EXECUTION' || Object.entries(pins).some(([key, value]) => policy.osv[key] !== value)) policyFail('OSV disposition mismatch');
  const expectedConcurrency = "concurrency:\n  group: ${{ github.event_name == 'pull_request' && format('{0}-{1}-pr-{2}', github.workflow, github.event.repository.full_name, github.event.pull_request.number) || format('{0}-{1}-{2}', github.workflow, github.event_name, github.run_id) }}\n  cancel-in-progress: ${{ github.event_name == 'pull_request' }}";
  requireText(workflow, expectedConcurrency);
  requireText(workflow, 'name: Dependency Vulnerability Scan / osv-scan');
  if ((workflow.match(/name: Dependency Vulnerability Scan \/ osv-scan/g) || []).length !== 1) policyFail('required OSV job names mismatch');
  requireText(workflow, 'timeout-minutes: 30');
  if ((workflow.match(/timeout-minutes: 10/g) || []).length !== 1) policyFail('OSV timeout mismatch');
  requireText(workflow, "dependency-vulnerability-scan:\n    name: Dependency Vulnerability Scan / osv-scan\n    if: ${{ github.event_name == 'pull_request' || github.event_name == 'workflow_dispatch' }}");
  if (/^  dependency-vulnerability-scan-dispatch:/m.test(workflow)) policyFail('skipped OSV sibling remains');
  if (/google\/osv-scanner-action\/\.github\/workflows\/osv-scanner-reusable/.test(workflow)) policyFail('reusable OSV caller remains');
  for (const fragment of [
    `google/osv-scanner-action/osv-scanner-action@${policy.osv.scanner}`,
    `google/osv-scanner-action/osv-reporter-action@${policy.osv.scanner}`,
    `actions/checkout@${policy.osv.checkout}`,
    `actions/upload-artifact@${policy.osv.artifact}`,
    `github/codeql-action/upload-sarif@${policy.osv.sarif}`,
    '${{ runner.temp }}/base-results.json', '${{ runner.temp }}/head-results.json', '${{ runner.temp }}/results.json', '${{ runner.temp }}/results.sarif',
  ]) requireText(workflow, fragment);
  if ((workflow.match(/continue-on-error: true/g) || []).length !== 3) policyFail('scanner-only continue-on-error mismatch');
  const scannerAction = `uses: google/osv-scanner-action/osv-scanner-action@${policy.osv.scanner}`;
  const reporterAction = `uses: google/osv-scanner-action/osv-reporter-action@${policy.osv.scanner}`;
  for (const [name, event, output] of [
    ['Scan immutable PR base', 'pull_request', '/github/runner_temp/base-results.json'],
    ['Scan tested PR head', 'pull_request', '/github/runner_temp/head-results.json'],
    ['Scan immutable dispatch SHA', 'workflow_dispatch', '/github/runner_temp/results.json'],
  ]) requireStep(workflow, name, ["if: ${{ github.event_name == '" + event + "' }}", 'continue-on-error: true', scannerAction, '--all-packages', `--output=${output}`]);
  for (const [name, event, inputs] of [
    ['Report PR dependency vulnerabilities', 'pull_request', ['/github/runner_temp/results.sarif', '--old=/github/runner_temp/base-results.json', '--new=/github/runner_temp/head-results.json']],
    ['Report dispatch dependency vulnerabilities', 'workflow_dispatch', ['/github/runner_temp/results.sarif', '--new=/github/runner_temp/results.json']],
  ]) requireStep(workflow, name, ["if: ${{ github.event_name == '" + event + "' }}", reporterAction, ...inputs], ['continue-on-error']);
  requireStep(workflow, 'Validate immutable PR scan results', ["if: ${{ github.event_name == 'pull_request' }}", 'validate-osv-results "${{ runner.temp }}/base-results.json" "${{ runner.temp }}/head-results.json"'], ['continue-on-error']);
  requireStep(workflow, 'Validate immutable dispatch results', ["if: ${{ github.event_name == 'workflow_dispatch' }}", 'validate-osv-results "${{ runner.temp }}/results.json"'], ['continue-on-error']);
  requireStep(workflow, 'Upload PR OSV results', ["if: ${{ github.event_name == 'pull_request' && !cancelled() }}", `actions/upload-artifact@${policy.osv.artifact}`, '${{ runner.temp }}/base-results.json', '${{ runner.temp }}/head-results.json', '${{ runner.temp }}/results.sarif'], ['continue-on-error']);
  requireStep(workflow, 'Upload PR OSV SARIF', ["if: ${{ github.event_name == 'pull_request' && !cancelled() }}", `github/codeql-action/upload-sarif@${policy.osv.sarif}`, 'sarif_file: ${{ runner.temp }}/results.sarif'], ['continue-on-error']);
  requireStep(workflow, 'Upload dispatch OSV results', ["if: ${{ github.event_name == 'workflow_dispatch' && !cancelled() }}", `actions/upload-artifact@${policy.osv.artifact}`, '${{ runner.temp }}/results.json', '${{ runner.temp }}/results.sarif'], ['continue-on-error']);
  requireStep(workflow, 'Upload dispatch OSV SARIF', ["if: ${{ github.event_name == 'workflow_dispatch' && !cancelled() }}", `github/codeql-action/upload-sarif@${policy.osv.sarif}`, 'sarif_file: ${{ runner.temp }}/results.sarif'], ['continue-on-error']);
  for (const [checkout, ref, event] of [
    ['Checkout immutable PR base', '${{ github.event.pull_request.base.sha }}', 'pull_request'],
    ['Checkout tested PR head', '${{ github.sha }}', 'pull_request'],
    ['Checkout immutable dispatch SHA', '${{ github.sha }}', 'workflow_dispatch'],
  ]) requireStep(workflow, checkout, ["if: ${{ github.event_name == '" + event + "' }}", `actions/checkout@${policy.osv.checkout}`, `ref: ${ref}`, 'persist-credentials: false']);
  for (const [verify, sha, event] of [
    ['Verify immutable PR base checkout', '${{ github.event.pull_request.base.sha }}', 'pull_request'],
    ['Verify tested PR head checkout', '${{ github.sha }}', 'pull_request'],
    ['Verify immutable dispatch checkout', '${{ github.sha }}', 'workflow_dispatch'],
  ]) requireStep(workflow, verify, ["if: ${{ github.event_name == '" + event + "' }}", `test "$(git rev-parse HEAD)" = "${sha}"`]);
  for (const [checkout, verify] of [
    ['Checkout immutable PR base', 'Verify immutable PR base checkout'],
    ['Checkout tested PR head', 'Verify tested PR head checkout'],
    ['Checkout immutable dispatch SHA', 'Verify immutable dispatch checkout'],
  ]) {
    const checkoutAt = workflow.indexOf(`      - name: ${checkout}`);
    const nextStepAt = workflow.indexOf('\n      - name:', checkoutAt + 1);
    if (nextStepAt < 0 || !workflow.slice(nextStepAt + 1).startsWith(`      - name: ${verify}`)) policyFail(`${checkout} must be immediately followed by ${verify}`);
  }
  for (const job of ['dependency-vulnerability-scan']) {
    const jobBlock = new RegExp(`^  ${job}:\\n([\\s\\S]*?)(?=^  [a-z][a-z0-9-]+:|$(?![\\s\\S]))`, 'm').exec(workflow)?.[0];
    if (!jobBlock) policyFail(`workflow missing job ${job}`);
    const permissions = /^    permissions:\n((?:      [^\n]+\n)+)/m.exec(jobBlock)?.[1];
    if (permissions !== '      actions: read\n      security-events: write\n      contents: read\n') policyFail(`${job} permissions mismatch`);
  }
  return true;
}

export function validateOsvResults(value, lockfiles) {
  if (!isObject(value)) fail('top-level value must be an object');
  if (!Array.isArray(value.results)) fail('results must be an array');
  if (!Array.isArray(lockfiles) || lockfiles.length === 0 || new Set(lockfiles).size !== lockfiles.length || lockfiles.some((path) => typeof path !== 'string' || !/^[a-z0-9][a-z0-9._/-]*$/.test(path) || path.includes('//') || path.split('/').includes('..'))) fail('configured lockfiles are invalid');
  const expected = new Set(lockfiles);
  const seen = new Set();
  for (const [resultIndex, result] of value.results.entries()) {
    if (!isObject(result)) fail(`results[${resultIndex}] must be an object`);
    if (!isObject(result.source) || typeof result.source.path !== 'string' || result.source.type !== 'lockfile') {
      fail(`results[${resultIndex}].source must contain string path and type`);
    }
    if (!Array.isArray(result.packages)) fail(`results[${resultIndex}].packages must be an array`);
    if (result.packages.length === 0) fail(`results[${resultIndex}].packages must be non-empty`);
    const sourcePath = result.source.path.startsWith('/github/workspace/') ? result.source.path.slice('/github/workspace/'.length) : result.source.path;
    if ((sourcePath !== result.source.path && result.source.path !== `/github/workspace/${sourcePath}`) || !expected.has(sourcePath) || seen.has(sourcePath)) fail(`results[${resultIndex}].source path must name one configured lockfile exactly once`);
    seen.add(sourcePath);
    for (const [packageIndex, pkg] of result.packages.entries()) {
      if (!isObject(pkg) || typeof pkg.package?.name !== 'string' || typeof pkg.package?.version !== 'string' || typeof pkg.package?.ecosystem !== 'string') {
        fail(`results[${resultIndex}].packages[${packageIndex}].package must contain string name, version, ecosystem`);
      }
      if (!Array.isArray(pkg.vulnerabilities) || !Array.isArray(pkg.groups)) {
        fail(`results[${resultIndex}].packages[${packageIndex}] must contain vulnerabilities and groups arrays`);
      }
    }
  }
  if (seen.size !== expected.size) fail('every configured lockfile must have exactly one result');
  return true;
}

export function validateOsvResultFile(path, lockfiles) {
  if (typeof path !== 'string' || path === '') fail('path is required');
  let stat;
  try { stat = lstatSync(path); } catch { fail(`${path} is missing`); }
  if (!stat.isFile()) fail(`${path} is not a file`);
  let text;
  try { text = readFileSync(path, 'utf8'); } catch { fail(`${path} cannot be read`); }
  if (text.trim() === '') fail(`${path} is empty`);
  let value;
  try { value = JSON.parse(text); } catch { fail(`${path} is not JSON`); }
  return validateOsvResults(value, lockfiles);
}

export function main(argv = process.argv.slice(2)) {
  if (argv[0] !== 'validate-osv-results' || argv.length < 2) {
    throw new Error('usage: node tools/ci/backend-ci-lifecycle.mjs validate-osv-results <json-path> [<json-path>...]');
  }
  const lockfiles = loadConfiguredOsvLockfiles();
  for (const path of argv.slice(1)) validateOsvResultFile(path, lockfiles);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
