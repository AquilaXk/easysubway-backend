import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';
import test from 'node:test';
import { validateCiExecutionControl, validateOsvResultFile, validateOsvResults } from './backend-ci-lifecycle.mjs';

const workflowUrl = new URL('../../.github/workflows/ci.yml', import.meta.url);
const policyUrl = new URL('../../backend/quality/ci-execution-control.json', import.meta.url);
const scanner = '8dc09193bb540e09b23da07ad7e30bd33bf87018';

test('OSV validator accepts a clean report and fails closed on malformed output', () => {
  const dir = mkdtempSync(join(tmpdir(), 'backend-ci-lifecycle-'));
  try {
    const clean = join(dir, 'clean.json');
    writeFileSync(clean, '{"results":[]}');
    assert.equal(validateOsvResultFile(clean), true);
    for (const [name, value] of [
      ['empty.json', ''], ['broken.json', '{'], ['array.json', '[]'], ['missing-results.json', '{}'],
      ['bad-source.json', '{"results":[{"source":{},"packages":[]}]}'],
      ['bad-package.json', '{"results":[{"source":{"path":"x","type":"lockfile"},"packages":[{}]}]}'],
    ]) {
      const path = join(dir, name); writeFileSync(path, value);
      assert.throws(() => validateOsvResultFile(path), /invalid OSV results/);
    }
    assert.throws(() => validateOsvResultFile(join(dir, 'missing.json')), /missing/);
    assert.equal(validateOsvResults({ results: [{ source: { path: 'x', type: 'lockfile' }, packages: [{ package: { name: 'x', version: '1', ecosystem: 'npm' }, vulnerabilities: [], groups: [] }] }] }), true);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('bounded command double leaves no post-cancel artifact', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'backend-ci-cancel-'));
  const artifact = join(dir, 'unexpected-artifact');
  try {
    const child = spawn(process.execPath, ['-e', "const { writeFileSync } = require('node:fs'); setTimeout(() => writeFileSync(process.argv[1], 'unexpected'), 120)", artifact]);
    await new Promise((resolve, reject) => {
      child.once('error', reject);
      setTimeout(() => child.kill('SIGTERM'), 10);
      child.once('close', resolve);
    });
    await new Promise((resolve) => setTimeout(resolve, 160));
    assert.equal(existsSync(artifact), false, 'cancelled command must not leave an artifact');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('workflow and machine policy close event identities and retain bounded local OSV semantics', async () => {
  const [workflow, policyText] = await Promise.all([readFile(workflowUrl, 'utf8'), readFile(policyUrl, 'utf8')]);
  const policy = JSON.parse(policyText);
  assert.equal(validateCiExecutionControl(policy, workflow), true);
  assert.equal(policy.durationEvidence.cacheMiss, 'unknown');
  assert.match(workflow, /concurrency:\n  group: \$\{\{ github\.event_name == 'pull_request'[\s\S]*github\.event\.pull_request\.number[\s\S]*github\.run_id[^\n]*\}\}/);
  assert.match(workflow, /cancel-in-progress: \$\{\{ github\.event_name == 'pull_request' \}\}/);
  assert.match(workflow, /name: Backend CI\n    runs-on: ubuntu-latest\n    timeout-minutes: 30/);
  assert.match(workflow, /dependency-vulnerability-scan:\n    name: Dependency Vulnerability Scan \/ osv-scan\n    if: \$\{\{ github\.event_name == 'pull_request' \}\}\n    runs-on: ubuntu-latest\n    timeout-minutes: 10/);
  assert.doesNotMatch(workflow, /google\/osv-scanner-action\/\.github\/workflows\/osv-scanner-reusable/);
  assert.match(workflow, new RegExp(`google/osv-scanner-action/osv-scanner-action@${scanner}`, 'g'));
  assert.match(workflow, new RegExp(`google/osv-scanner-action/osv-reporter-action@${scanner}`));
  assert.match(workflow, /github\.event\.pull_request\.base\.sha/);
  assert.match(workflow, /github\.sha/);
  assert.match(workflow, /git rev-parse HEAD/);
  assert.match(workflow, /validate-osv-results "\$\{\{ runner\.temp \}\}\/base-results\.json" "\$\{\{ runner\.temp \}\}\/head-results\.json"/);
  assert.match(workflow, /--old=\/github\/runner_temp\/base-results\.json\n\s+--new=\/github\/runner_temp\/head-results\.json\n\s+--gh-annotations=true\n\s+--fail-on-vuln=true/);
  assert.match(workflow, /--new=\/github\/runner_temp\/results\.json\n\s+--gh-annotations=false\n\s+--fail-on-vuln=true/);
  assert.match(workflow, /--output=\/github\/runner_temp\/base-results\.json/);
  assert.match(workflow, /--output=\/github\/runner_temp\/head-results\.json/);
  assert.match(workflow, /--output=\/github\/runner_temp\/results\.json/);
  assert.match(workflow, /--output=\/github\/runner_temp\/results\.sarif/);
  assert.ok(workflow.indexOf('Checkout tested PR head') < workflow.indexOf('Validate immutable PR scan results'));
  assert.equal((workflow.match(/continue-on-error: true/g) || []).length, 3);
});

test('policy/workflow static validation fails closed for unknown or malformed mutations', async () => {
  const [workflow, policyText] = await Promise.all([readFile(workflowUrl, 'utf8'), readFile(policyUrl, 'utf8')]);
  const policy = JSON.parse(policyText);
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const extraEvent = clone(policy); extraEvent.events.schedule = { group: 'unexpected' };
  assert.throws(() => validateCiExecutionControl(extraEvent, workflow), /events keys/);
  const missingEvent = clone(policy); delete missingEvent.events.push;
  assert.throws(() => validateCiExecutionControl(missingEvent, workflow), /events keys/);
  const timeoutMutation = clone(policy); timeoutMutation.timeouts.osvMinutes = 11;
  assert.throws(() => validateCiExecutionControl(timeoutMutation, workflow), /timeout mismatch/);
  const pinMutation = clone(policy); pinMutation.osv.checkout = '0'.repeat(40);
  assert.throws(() => validateCiExecutionControl(pinMutation, workflow), /OSV disposition mismatch/);
  const lockfileMutation = clone(policy); lockfileMutation.osv.lockfiles[1] = 'tools/qa/other-lock.json';
  assert.throws(() => validateCiExecutionControl(lockfileMutation, workflow), /OSV disposition mismatch/);
  const nestedExtra = clone(policy); nestedExtra.durationEvidence.reviewTrigger.extra = true;
  assert.throws(() => validateCiExecutionControl(nestedExtra, workflow), /durationEvidence\.reviewTrigger keys/);
  const nestedMissing = clone(policy); delete nestedMissing.durationEvidence.representativeRuns[0].gradleCache;
  assert.throws(() => validateCiExecutionControl(nestedMissing, workflow), /durationEvidence\.representativeRuns\[0\] keys/);
  const reporterContinue = workflow
    .replace('      - name: Scan immutable PR base\n        continue-on-error: true', '      - name: Scan immutable PR base')
    .replace('      - name: Report PR dependency vulnerabilities', '      - name: Report PR dependency vulnerabilities\n        continue-on-error: true');
  assert.throws(() => validateCiExecutionControl(policy, reporterContinue), /workflow missing "continue-on-error: true"|step Report PR dependency vulnerabilities/);
  const weakPermission = workflow.replace('      actions: read', '      actions: write');
  assert.throws(() => validateCiExecutionControl(policy, weakPermission), /permissions mismatch/);
  const missingEquality = workflow.replace('run: test "$(git rev-parse HEAD)" = "${{ github.event.pull_request.base.sha }}"', 'run: true');
  assert.throws(() => validateCiExecutionControl(policy, missingEquality), /workflow missing "test/);
  assert.throws(() => validateCiExecutionControl(policy, workflow.replace('name: Dependency Vulnerability Scan / osv-scan', 'name: osv-scan')), /required OSV job names/);
  assert.throws(() => validateCiExecutionControl(policy, workflow.replace('cancel-in-progress: ${{ github.event_name == \'pull_request\' }}', 'cancel-in-progress: false')), /workflow missing/);
});
