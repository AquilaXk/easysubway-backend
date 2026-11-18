import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';

// Backend #416 F1: 범위 판정기와 집계 판정기는 PR head가 아니라 신뢰된 base 커밋의 사본으로 실행한다.
const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
const scopeSource = readFileSync(new URL('./backend-ci-scope.mjs', import.meta.url), 'utf8');
const mainModuleSource = readFileSync(new URL('../lib/is-main-module.mjs', import.meta.url), 'utf8');

const runBlock = (stepName) => {
  const start = workflow.indexOf(`      - name: ${stepName}\n`);
  assert.ok(start >= 0, `step ${stepName} is required`);
  const end = workflow.indexOf('\n      - name: ', start + 1);
  const step = workflow.slice(start, end < 0 ? undefined : end);
  assert.doesNotMatch(step.slice(step.indexOf('run: |')), /\$\{\{/, `${stepName} run script must read inputs only from env`);
  const body = [];
  for (const line of step.slice(step.indexOf('run: |\n') + 'run: |\n'.length).split('\n')) {
    if (line.length > 0 && !line.startsWith('          ')) break;
    body.push(line.slice(10));
  }
  return body.join('\n');
};

const withRepository = (build, run) => {
  const root = mkdtempSync(join(tmpdir(), 'backend-ci-trust-'));
  try {
    const repository = join(root, 'repo');
    mkdirSync(repository);
    const git = (...args) => execFileSync('git', ['-c', 'user.email=ci@example.invalid', '-c', 'user.name=ci', '-c', 'init.defaultBranch=main', ...args], { cwd: repository, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    const put = (path, text) => { mkdirSync(dirname(join(repository, path)), { recursive: true }); writeFileSync(join(repository, path), text); };
    git('init', '-q');
    build({ git, put });
    return run({ root, repository });
  } finally { rmSync(root, { recursive: true, force: true }); }
};

// base(main) 위에 PR 브랜치를 만들고 GitHub pull_request checkout과 같은 two-parent merge commit을 만든다.
const pullRequestMerge = ({ base, head }) => ({ git, put }) => {
  put('backend/src/main/java/Example.java', 'class Example {}\n');
  base(put);
  git('add', '-A');
  git('commit', '-q', '-m', 'base');
  git('checkout', '-q', '-b', 'pr');
  put('backend/src/main/java/Example.java', 'class Example { int changed; }\n');
  head(put);
  git('add', '-A');
  git('commit', '-q', '-m', 'pr');
  git('checkout', '-q', 'main');
  git('merge', '-q', '--no-ff', '-m', 'merge', 'pr');
};
const trustedBase = (put) => { put('tools/ci/backend-ci-scope.mjs', scopeSource); put('tools/lib/is-main-module.mjs', mainModuleSource); };
const widenedHead = (put) => put('tools/ci/backend-ci-scope.mjs', scopeSource.replace('export const LIGHT_PATHS = [\n', "export const LIGHT_PATHS = [\n  { pattern: /^(?:backend|tools)\\//, reason: 'widened by the pull request' },\n"));

const runStep = (repository, root, script, env) => {
  const output = join(root, 'output'), summary = join(root, 'summary');
  writeFileSync(output, ''); writeFileSync(summary, '');
  const result = spawnSync('bash', ['-c', script], { cwd: repository, encoding: 'utf8', env: { ...process.env, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary, ...env } });
  return { ...result, output: readFileSync(output, 'utf8'), summary: readFileSync(summary, 'utf8') };
};
const classify = (repository, root, event = 'pull_request') => runStep(repository, root, runBlock('Classify changed paths'), { SCOPE_EVENT: event, SCOPE_TRUSTED_ROOT: join(root, 'scope-trusted') });

test('Backend #416 F1: a pull request that widens its own allowlist still runs the heavy stages', () => {
  withRepository(pullRequestMerge({ base: trustedBase, head: widenedHead }), ({ root, repository }) => {
    const headCopy = spawnSync(process.execPath, ['tools/ci/backend-ci-scope.mjs', 'classify', '--event', 'pull_request', '--output', join(root, 'head-output'), '--summary', join(root, 'head-summary')], { cwd: repository, encoding: 'utf8' });
    assert.equal(headCopy.status, 0, headCopy.stderr);
    assert.equal(readFileSync(join(root, 'head-output'), 'utf8'), 'heavy=false\n', 'the widened head classifier would skip the backend change');
    const result = classify(repository, root);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.output, 'heavy=true\n');
    assert.match(result.summary, /run: `backend\/src\/main\/java\/Example\.java`/);
  });
});

test('Backend #416 F1: a base without the trusted classifier runs the heavy stages', () => {
  withRepository(pullRequestMerge({ base: () => {}, head: (put) => { trustedBase(put); widenedHead(put); } }), ({ root, repository }) => {
    const result = classify(repository, root);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.output, 'heavy=true\n');
    assert.match(result.summary, /trusted classifier is absent in the base commit/);
  });
});

test('Backend #416 F1: a trusted classifier that produces no decision fails the scope job', () => {
  const silent = (put) => { put('tools/ci/backend-ci-scope.mjs', "import '../lib/is-main-module.mjs';\n"); put('tools/lib/is-main-module.mjs', mainModuleSource); };
  withRepository(pullRequestMerge({ base: silent, head: () => {} }), ({ root, repository }) => {
    const result = classify(repository, root);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /trusted classifier produced no decision/);
    assert.equal(result.output, '');
  });
});

test('Backend #416 F1: push and dispatch run everything without consulting the pull request tree', () => {
  withRepository(({ git, put }) => { put('README.md', 'x\n'); git('add', '-A'); git('commit', '-q', '-m', 'only'); }, ({ root, repository }) => {
    for (const event of ['push', 'workflow_dispatch']) {
      const result = classify(repository, root, event);
      assert.equal(result.status, 0, result.stderr);
      assert.equal(result.output, 'heavy=true\n');
    }
  });
});

test('Backend #416 F1: the aggregate verifier also runs from the trusted base copy', () => {
  const weakenedVerifier = (put) => put('tools/ci/backend-ci-scope.mjs', `${scopeSource}\nif (process.argv[2] === 'verify-jobs') process.exit(0);\n`.replace("if (isMainModule(import.meta.url)) {", "if (isMainModule(import.meta.url) && process.argv[2] !== 'verify-jobs') {"));
  const failedNeeds = JSON.stringify({ scope: { result: 'success', outputs: { heavy: 'true' } }, 'backend-contracts': { result: 'success' }, 'backend-test': { result: 'failure' }, 'backend-spotbugs': { result: 'success' } });
  const verify = (repository, root) => runStep(repository, root, runBlock('Verify Backend CI job results'), { BACKEND_CI_EVENT: 'pull_request', BACKEND_CI_NEEDS: failedNeeds, VERIFY_TRUSTED_ROOT: join(root, 'verify-trusted') });
  withRepository(pullRequestMerge({ base: trustedBase, head: weakenedVerifier }), ({ root, repository }) => {
    const weakened = spawnSync(process.execPath, ['tools/ci/backend-ci-scope.mjs', 'verify-jobs', '--event', 'pull_request', '--needs', failedNeeds, '--summary', join(root, 'weak')], { cwd: repository, encoding: 'utf8' });
    assert.equal(weakened.status, 0, 'the weakened head verifier would accept a failed shard');
    const result = verify(repository, root);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /backend-test is in scope but ended failure/);
  });
  withRepository(pullRequestMerge({ base: () => {}, head: trustedBase }), ({ root, repository }) => {
    const result = verify(repository, root);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /backend-test is in scope but ended failure/);
    assert.match(result.summary, /verifier: pull request head \(base has no verifier\)/);
  });
});
