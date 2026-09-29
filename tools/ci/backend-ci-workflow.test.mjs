import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { SHARD_COUNT } from './backend-test-shards.mjs';

const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
const HEAVY = "needs.scope.outputs.heavy == 'true'";
const job = (name) => {
  const block = new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  [a-z][a-z0-9-]+:|$(?![\\s\\S]))`, 'm').exec(workflow)?.[0];
  assert.ok(block, `job ${name} is required`);
  return block;
};
const steps = (block) => block.split(/^(?=      - name: )/m).slice(1).map((text) => ({ name: /^      - name: ([^\n]+)/.exec(text)[1], text }));
const step = (block, name) => {
  const found = steps(block).filter((candidate) => candidate.name === name);
  assert.equal(found.length, 1, `step ${name} must appear exactly once in its job`);
  return found[0].text;
};

test('Backend #416 job graph keeps one required Backend CI aggregate over scope, contracts, shards, and SpotBugs', () => {
  assert.deepEqual([...workflow.slice(workflow.indexOf('\njobs:\n')).matchAll(/^  ([a-z][a-z0-9-]+):$/gm)].map(([, name]) => name), ['dependency-vulnerability-scan', 'backend-spotbugs', 'scope', 'backend-contracts', 'backend-test', 'backend']);
  assert.equal((workflow.match(/^    name: Backend CI$/gm) || []).length, 1);
  const backend = job('backend');
  assert.match(backend, /^  backend:\n    name: Backend CI\n    runs-on: ubuntu-latest\n    timeout-minutes: 30\n    needs: \[scope, backend-contracts, backend-test, backend-spotbugs\]\n(?:    #[^\n]*\n)*    if: always\(\)\n/);
  const verify = step(backend, 'Verify Backend CI job results');
  assert.match(verify, /BACKEND_CI_NEEDS: \$\{\{ toJSON\(needs\) \}\}/);
  assert.match(verify, /BACKEND_CI_EVENT: \$\{\{ github\.event_name \}\}/);
  assert.match(verify, /git archive "\$\{revision\}" "\$\{source\}" tools\/lib\/is-main-module\.mjs/);
  assert.match(verify, /node "\$\{trusted\}\/\$\{source\}" verify-jobs --event "\$\{BACKEND_CI_EVENT\}" --needs "\$\{BACKEND_CI_NEEDS\}"/);
  assert.doesNotMatch(verify, /\n        if:/);
  const ordered = steps(backend).map(({ name }) => name);
  assert.deepEqual(ordered.slice(0, 3), ['Checkout', 'Set up Node.js', 'Verify Backend CI job results']);
  for (const { name, text } of steps(backend).slice(3)) assert.ok(text.includes(HEAVY), `${name} must run only for in-scope changes`);
  assert.doesNotMatch(workflow, /^    if: \$\{\{ !cancelled\(\) \}\}$|^    if: success\(\)$/m);
});

test('Backend #416 scope runs first on full history and heavy jobs depend on it', () => {
  const scope = job('scope');
  assert.match(scope, /outputs:\n      heavy: \$\{\{ steps\.classify\.outputs\.heavy \}\}/);
  assert.match(scope, /fetch-depth: 0/);
  const classify = step(scope, 'Classify changed paths');
  assert.match(classify, /id: classify\n        env:\n          SCOPE_EVENT: \$\{\{ github\.event_name \}\}\n/);
  for (const literal of [
    'git archive "${base}" "${source}" tools/lib/is-main-module.mjs',
    'node "${trusted}/${source}" classify --event pull_request --output "${decision}"',
    "grep -Eqx 'heavy=(true|false)' \"${decision}\"",
    'trusted classifier is absent in the base commit',
  ]) assert.ok(classify.includes(literal), literal);
  assert.doesNotMatch(classify, /node tools\/ci\/backend-ci-scope\.mjs/);
  assert.doesNotMatch(scope, /\n    (?:if|needs):/);
  for (const name of ['backend-test', 'backend-spotbugs']) {
    const block = job(name);
    assert.match(block, new RegExp(`\\n    needs: scope\\n    if: ${HEAVY.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\n`), name);
  }
  const contracts = job('backend-contracts');
  assert.doesNotMatch(contracts, /\n    (?:if|needs):/);
  for (const name of ['Lint workflows', 'Check documentation fragment preflight sync', 'Test backend-owned Node contracts', 'Check migration DDL compatibility', 'Test admin QA contracts']) step(contracts, name);
  assert.match(step(contracts, 'Test backend-owned Node contracts'), /mkdir -p backend\/build\n          node --test backend\/tools\/\*\.test\.mjs\n          node --test tools\/ci\/\*\.test\.mjs\n          node --test tools\/routes\/\*\.test\.mjs\n          node --test tools\/realtime\/\*\.test\.mjs/);
});

test('Backend #416 test shards run every shard through the init script and upload exact evidence', () => {
  const shards = job('backend-test');
  assert.match(shards, /name: Backend CI test shard \$\{\{ matrix\.shard \}\}/);
  assert.match(shards, /strategy:\n      fail-fast: false\n      matrix:\n(?:        #[^\n]*\n)*        shard: \[([0-9, ]+)\]/);
  assert.deepEqual(/        shard: \[([0-9, ]+)\]/.exec(shards)[1].split(', ').map(Number), Array.from({ length: SHARD_COUNT }, (_, index) => index + 1));
  assert.match(step(shards, 'Test backend shard'), new RegExp(`run: \\./gradlew --init-script \\.\\./tools/ci/gradle/backend-ci\\.init\\.gradle test -PbackendTestShardIndex=\\$\\{\\{ matrix\\.shard \\}\\} -PbackendTestShardCount=${SHARD_COUNT} -x jacocoTestReport --no-daemon`));
  const collect = step(shards, 'Collect shard evidence');
  for (const literal of ['test -s backend/build/jacoco/test.exec', 'cp -R backend/build/test-results/test "${evidence}/test-results"', 'node tools/ci/backend-test-shards.mjs digest-classes --dir backend/build/classes/java/main']) assert.ok(collect.includes(literal), literal);
  const upload = step(shards, 'Upload shard evidence');
  for (const literal of ['uses: actions/upload-artifact@bbbca2ddaa5d8feaa63e36b76fdaad77386f024f', 'name: backend-test-shard-${{ matrix.shard }}-${{ github.sha }}', 'retention-days: 5', 'if-no-files-found: error']) assert.ok(upload.includes(literal), literal);
  assert.doesNotMatch(shards, /continue-on-error/);
});

test('Backend #416 aggregate merges all shard execution data before the unchanged coverage gate', () => {
  const backend = job('backend');
  const download = step(backend, 'Download test shard evidence');
  assert.match(download, /uses: actions\/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c\n        with:\n          pattern: backend-test-shard-\*-\$\{\{ github\.sha \}\}\n          path: \$\{\{ runner\.temp \}\}\/backend-test-shards/);
  const merge = step(backend, 'Test and package backend');
  for (const literal of [
    'id: test_backend',
    `test "$(find "\${shards}" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq ${SHARD_COUNT}`,
    `for shard in ${Array.from({ length: SHARD_COUNT }, (_, index) => index + 1).join(' ')}; do`,
    'test -s "${dir}/test.exec"',
    './gradlew --init-script ../tools/ci/gradle/backend-ci.init.gradle testClasses bootJar jacocoTestReport -x test -PbackendJacocoExecutionDataDir="${execution}" --no-daemon',
    'node tools/ci/backend-test-shards.mjs verify --repo-root . "${results[@]}" "${digests[@]}"',
  ]) assert.ok(merge.includes(literal), literal);
  const names = steps(backend).map(({ name }) => name);
  assert.ok(names.indexOf('Download test shard evidence') < names.indexOf('Test and package backend'));
  assert.ok(names.indexOf('Test and package backend') < names.indexOf('Analyze backend critical coverage'));
  assert.ok(names.indexOf('Enforce backend critical coverage verdict') < names.indexOf('Analyze with SonarQube Cloud'));
  assert.ok(names.indexOf('Analyze with SonarQube Cloud') < names.indexOf('Build image without push'));
  assert.match(step(backend, 'Enforce backend critical coverage verdict'), new RegExp(`if: always\\(\\) && ${HEAVY.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\n`));
});

test('Backend #416 every Gradle job uses the same pinned Java setup and contract staging', () => {
  const setup = (block) => {
    const texts = steps(block).filter(({ name }) => ['Set up Node.js', 'Set up ORAS', 'Set up Java', 'Stage pinned contracts', 'Stage pinned Journey contracts'].includes(name));
    return texts.map(({ text }) => text.replace(`        if: ${HEAVY}\n`, '').replace(/\n+$/, '').replace(/(?:\n      # [^\n]*)+$/, '').replace(/\n+$/, ''));
  };
  const reference = setup(job('backend-test'));
  assert.equal(reference.length, 5);
  assert.deepEqual(setup(job('backend-spotbugs')), reference);
  assert.deepEqual(setup(job('backend')), reference);
});

test('Backend #416 SpotBugs gate job keeps full history and its ordered phase-one steps', () => {
  const spotbugs = job('backend-spotbugs');
  assert.match(spotbugs, /    steps:\n      - name: Checkout\n        uses: actions\/checkout@[0-9a-f]{40}\n        with:\n          persist-credentials: false\n          fetch-depth: 0\n/);
  const names = steps(spotbugs).map(({ name }) => name);
  const expected = ['Run SpotBugs main analysis', 'Capture SpotBugs main evidence', 'Validate SpotBugs report and policy', 'Upload SpotBugs current-run artifact', 'Append SpotBugs summary', 'Enforce SpotBugs gate'];
  assert.deepEqual(names.slice(-expected.length), expected);
});
