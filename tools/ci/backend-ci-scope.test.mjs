import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { changedPathsFromMergeCommit, classifyChanges, classifyPath, verifyJobs } from './backend-ci-scope.mjs';

const tool = new URL('./backend-ci-scope.mjs', import.meta.url).pathname;

test('Backend #416 path table: backend build, gates, contracts, and unknown paths run the heavy stages', () => {
  for (const [path, heavy] of [
    ['backend/src/main/java/com/easysubway/route/domain/EtaSource.java', true],
    ['backend/src/test/java/com/easysubway/route/domain/EtaSourceTest.java', true],
    ['backend/src/main/resources/application.yml', true],
    ['backend/build.gradle', true],
    ['backend/settings.gradle', true],
    ['backend/gradle/wrapper/gradle-wrapper.properties', true],
    ['backend/gradle.lockfile', true],
    ['backend/quality/spotbugs-suppression-policy.json', true],
    ['backend/quality/jacoco-coverage-baseline.json', true],
    ['backend/contracts.lock.json', true],
    ['backend/journey-contracts.lock.json', true],
    ['backend/tools/stage-contracts.mjs', true],
    ['backend/Dockerfile', true],
    ['backend/sonar-project.properties', true],
    ['gradle/wrapper/gradle-wrapper.properties', true],
    ['settings.gradle', true],
    ['settings.gradle.kts', true],
    ['build.gradle', true],
    ['build.gradle.kts', true],
    ['.github/workflows/ci.yml', true],
    ['tools/ci/backend-spotbugs-gate.mjs', true],
    ['tools/ci/backend-spotbugs-gate.test.mjs', true],
    ['tools/ci/backend-coverage-gate.mjs', true],
    ['tools/ci/backend-coverage-gate.test.mjs', true],
    ['tools/ci/backend-test-shards.mjs', true],
    ['tools/ci/backend-ci-scope.mjs', true],
    ['tools/ci/backend-ci-lifecycle.mjs', true],
    ['tools/ci/gradle/backend-ci.init.gradle', true],
    ['tools/lib/is-main-module.mjs', true],
    ['contracts/api/journey-v3.openapi.yaml', true],
    ['contracts/route/route-contract.json', true],
    ['docker-compose.dev.yml', true],
    ['.gitignore', true],
    ['.sdkmanrc', true],
    ['.tool-versions', true],
    ['docs/notes.md', true],
    ['CHANGELOG.md', true],
    ['README.md', false],
    ['README.ko.md', false],
    ['.github/workflows/automerge-queue.yml', false],
    ['.github/workflows/release-artifacts.yml', false],
    ['.github/ISSUE_TEMPLATE/bug.yml', false],
    ['.github/PULL_REQUEST_TEMPLATE/full.md', false],
    ['.github/pull_request_template.md', false],
    ['.github/dependabot.yml', false],
    ['tools/realtime/seoul-topis-provider-contract.json', false],
    ['tools/routes/validate-route.mjs', false],
    ['tools/qa/admin-accessibility-qa.mjs', false],
    ['tools/repo/refresh-documentation-fragment.mjs', false],
    ['tools/ci/automerge-queue.test.mjs', false],
    ['tools/ci/check-documentation-fragment-sync.mjs', false],
    ['contracts/documentation/documentation-fragment.json', false],
    ['package.json', false],
    ['.nvmrc', false],
  ]) assert.equal(classifyPath(path).heavy, heavy, path);
  for (const invalid of ['', '/etc/passwd', 'backend/../README.md']) assert.throws(() => classifyPath(invalid), /invalid changed path/);
});

test('Backend #416 push and dispatch always run everything; PRs run heavy stages if any path needs them', () => {
  assert.equal(classifyChanges({ event: 'push' }).heavy, true);
  assert.equal(classifyChanges({ event: 'workflow_dispatch' }).heavy, true);
  assert.equal(classifyChanges({ event: 'pull_request', paths: [] }).heavy, true);
  assert.equal(classifyChanges({ event: 'pull_request', paths: ['README.md', 'tools/realtime/a.json'] }).heavy, false);
  assert.equal(classifyChanges({ event: 'pull_request', paths: ['README.md', 'backend/build.gradle'] }).heavy, true);
  assert.equal(classifyChanges({ event: 'pull_request', paths: ['.github/workflows/ci.yml'] }).heavy, true);
  assert.throws(() => classifyChanges({ event: 'pull_request' }), /changed paths are required/);
});

test('Backend #416 changed paths come from a two-parent merge commit only', () => {
  const merge = `${'a'.repeat(40)} ${'b'.repeat(40)} ${'c'.repeat(40)}\n`;
  const calls = [];
  const run = (args) => { calls.push(args); return args[0] === 'rev-list' ? merge : 'README.md\0backend/build.gradle\0'; };
  assert.deepEqual(changedPathsFromMergeCommit(run), ['README.md', 'backend/build.gradle']);
  assert.deepEqual(calls[1], ['diff', '--name-only', '--no-renames', '-z', 'HEAD^1', 'HEAD']);
  assert.throws(() => changedPathsFromMergeCommit(() => `${'a'.repeat(40)} ${'b'.repeat(40)}\n`), /two-parent merge commit/);
});

test('Backend #416 aggregation fails on any failed, cancelled, missing, or out-of-scope-but-run job', () => {
  const needs = (heavy, test = heavy ? 'success' : 'skipped', spotbugs = test, contracts = 'success', scope = 'success') => ({
    scope: { result: scope, outputs: { heavy: String(heavy) } },
    'backend-contracts': { result: contracts, outputs: {} },
    'backend-test': { result: test, outputs: {} },
    'backend-spotbugs': { result: spotbugs, outputs: {} },
  });
  assert.equal(verifyJobs({ event: 'pull_request', needs: needs(true) }).heavy, true);
  const light = verifyJobs({ event: 'pull_request', needs: needs(false) });
  assert.equal(light.heavy, false);
  assert.match(light.summary, /backend-test: skipped-by-scope/);
  assert.match(light.summary, /backend-spotbugs: skipped-by-scope/);
  assert.match(verifyJobs({ event: 'push', needs: needs(true) }).summary, /backend-test: success/);
  for (const [event, value, pattern] of [
    ['push', needs(false), /must run the full Backend CI/],
    ['workflow_dispatch', needs(false), /must run the full Backend CI/],
    ['pull_request', needs(true, 'failure', 'success'), /backend-test is in scope but ended failure/],
    ['pull_request', needs(true, 'cancelled', 'success'), /backend-test is in scope but ended cancelled/],
    ['pull_request', needs(true, 'skipped', 'success'), /backend-test is in scope but ended skipped/],
    ['pull_request', needs(true, 'success', 'failure'), /backend-spotbugs is in scope but ended failure/],
    ['pull_request', needs(false, 'success', 'skipped'), /backend-test is out of scope but ended success/],
    ['pull_request', needs(true, 'success', 'success', 'failure'), /backend-contracts did not succeed/],
    ['pull_request', needs(true, 'success', 'success', 'success', 'cancelled'), /scope job did not succeed/],
  ]) assert.throws(() => verifyJobs({ event, needs: value }), pattern, JSON.stringify(value));
  const missing = needs(true); delete missing['backend-spotbugs'];
  assert.throws(() => verifyJobs({ event: 'pull_request', needs: missing }), /backend-spotbugs result is missing/);
  const noOutput = needs(true); noOutput.scope.outputs = {};
  assert.throws(() => verifyJobs({ event: 'pull_request', needs: noOutput }), /heavy output is missing/);
});

test('Backend #416 verify-jobs command writes the scope summary and fails closed on malformed needs', () => {
  const directory = mkdtempSync(join(tmpdir(), 'backend-ci-scope-'));
  try {
    const summary = join(directory, 'summary.md');
    const needs = JSON.stringify({ scope: { result: 'success', outputs: { heavy: 'false' } }, 'backend-contracts': { result: 'success' }, 'backend-test': { result: 'skipped' }, 'backend-spotbugs': { result: 'skipped' } });
    const ok = spawnSync(process.execPath, [tool, 'verify-jobs', '--event', 'pull_request', '--needs', needs, '--summary', summary], { encoding: 'utf8' });
    assert.equal(ok.status, 0, ok.stderr);
    assert.match(readFileSync(summary, 'utf8'), /skipped-by-scope/);
    const bad = spawnSync(process.execPath, [tool, 'verify-jobs', '--event', 'pull_request', '--needs', '{', '--summary', summary], { encoding: 'utf8' });
    assert.notEqual(bad.status, 0);
    assert.match(bad.stderr, /--needs must be JSON/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
