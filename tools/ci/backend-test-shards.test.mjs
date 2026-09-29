import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { SHARD_COUNT, digestClasses, expectedTestClasses, planShards, readShardResults, shardOf, topLevelClass, verifyShards } from './backend-test-shards.mjs';

const repositoryRoot = new URL('../../', import.meta.url).pathname;
const tool = new URL('./backend-test-shards.mjs', import.meta.url).pathname;
const initScript = readFileSync(new URL('./gradle/backend-ci.init.gradle', import.meta.url), 'utf8');

const withDirectory = (name, run) => {
  const directory = mkdtempSync(join(tmpdir(), name));
  try { return run(directory); } finally { rmSync(directory, { recursive: true, force: true }); }
};
const writeResults = (directory, suites) => {
  mkdirSync(directory, { recursive: true });
  for (const { className, cases, failures = 0, errors = 0, skipped = 0 } of suites) {
    const body = cases.map((name) => `<testcase name="${name}" classname="${className}" time="0.1"/>`).join('');
    writeFileSync(join(directory, `TEST-${className}.xml`), `<?xml version="1.0" encoding="UTF-8"?><testsuite name="${className}" tests="${cases.length}" skipped="${skipped}" failures="${failures}" errors="${errors}" timestamp="2026-09-29T00:00:00" hostname="ci" time="0.1">${body}</testsuite>`);
  }
};

test('Backend #416 shard rule is a stable SHA-256 of the top-level binary class name', () => {
  assert.equal(SHARD_COUNT, 4);
  for (const [name, four, three] of [
    ['com.easysubway.architecture.PackageDependencyRulesTest', 1, 3],
    ['com.easysubway.route.domain.EtaSourceTest', 3, 1],
    ['a.B', 4, 3],
  ]) {
    assert.equal(shardOf(name, 4), four, name);
    assert.equal(shardOf(name, 3), three, name);
    const hex = createHash('sha256').update(name, 'utf8').digest('hex');
    assert.equal(shardOf(name, 4), (Number.parseInt(hex.slice(0, 8), 16) % 4) + 1);
  }
  assert.equal(topLevelClass('com.easysubway.FooTest$Nested$Deeper'), 'com.easysubway.FooTest');
  assert.equal(shardOf('com.easysubway.FooTest$Nested'), shardOf('com.easysubway.FooTest'));
  for (const invalid of ['', '.Foo', 'com..Foo', 'com/easysubway/Foo', '$Foo', 42]) assert.throws(() => shardOf(invalid), /invalid class name/);
  assert.throws(() => shardOf('a.B', 0), /positive integer/);
});

test('Backend #416 Gradle init script applies the same shard rule and fails closed on bad properties', () => {
  for (const literal of [
    "MessageDigest.getInstance('SHA-256').digest(topLevel.getBytes(StandardCharsets.UTF_8))",
    "binaryName.split('\\\\$', 2)[0]",
    'Long.parseLong(hex.substring(0, 8), 16) % shardCount) + 1',
    ".replace('/', '.')",
    "test.inputs.property('backendTestShardIndex', shardIndex)",
    "test.inputs.property('backendTestShardCount', shardCount)",
    'backendTestShardOf(element.relativePath.pathString, shardCount) != shardIndex',
    "throw new GradleException('backendTestShardIndex and backendTestShardCount must be set together')",
    "throw new GradleException('test sharding and merged JaCoCo reporting are separate invocations')",
    "report.executionData.setFrom(executionFiles)",
    'throw new GradleException("no JaCoCo execution data in ${directory}")',
  ]) assert.ok(initScript.includes(literal), `init script missing ${literal}`);
  assert.doesNotMatch(initScript, /ignoreFailures|failOnNoMatchingTests\s*=\s*false|maxParallelForks|enabled\s*=\s*false/);
});

test('Backend #416 every test class in the tracked sources belongs to exactly one non-empty shard', () => {
  const expected = expectedTestClasses(repositoryRoot);
  assert.ok(expected.length > 300, `unexpectedly small test inventory: ${expected.length}`);
  assert.equal(new Set(expected).size, expected.length);
  assert.ok(expected.includes('com.easysubway.architecture.PackageDependencyRulesTest'));
  assert.ok(expected.includes('com.google.api.client.http.BoundedHttpTransportTest'));
  const plan = planShards(expected);
  assert.equal(plan.length, SHARD_COUNT);
  assert.ok(plan.every((classes) => classes.length > 0));
  assert.deepEqual(plan.flat().sort(), expected);
  plan.forEach((classes, index) => assert.ok(classes.every((name) => shardOf(name) === index + 1)));
});

test('Backend #416 expected inventory keeps annotated concrete classes and skips helpers and abstract bases', () => {
  withDirectory('backend-shard-inventory-', (root) => {
    const put = (path, source) => { mkdirSync(join(root, path, '..'), { recursive: true }); writeFileSync(join(root, path), source); };
    put('backend/src/test/java/com/example/ConcreteTest.java', 'package com.example;\nclass ConcreteTest { @Test void works() {} }\n');
    put('backend/src/test/java/com/example/ArchitectureTest.java', 'package com.example;\nclass ArchitectureTest { @ArchTest static final Object rule = null; }\n');
    put('backend/src/test/java/com/example/Fixtures.java', 'package com.example;\nclass Fixtures { }\n');
    put('backend/src/test/java/com/example/BaseTest.java', 'package com.example;\npublic abstract class BaseTest { @Test void inherited() {} }\n');
    assert.deepEqual(expectedTestClasses(root), ['com.example.ArchitectureTest', 'com.example.ConcreteTest']);
  });
});

test('Backend #416 shard verification rejects missing, duplicated, misassigned, unexpected, empty, or failing shards', () => {
  const pick = (shard) => ['com.easysubway.architecture.PackageDependencyRulesTest', 'com.easysubway.route.domain.EtaSourceTest', 'a.B']
    .concat(Array.from({ length: 200 }, (_, index) => `com.example.Generated${index}Test`))
    .filter((name) => shardOf(name) === shard);
  const classes = [1, 2, 3, 4].map(pick);
  const expected = classes.flat().sort();
  const result = (names, extra = {}) => ({ classes: [...names].sort(), cases: [], tests: names.length * 2, skipped: 0, failures: 0, errors: 0, ...extra });
  const shards = () => classes.map((names) => result(names));
  const summary = verifyShards({ expected, shards: shards() });
  assert.equal(summary.classes, expected.length);
  assert.equal(summary.tests, expected.length * 2);
  assert.deepEqual(summary.perShard.map(({ classes: count }) => count), classes.map((names) => names.length));
  const missing = shards(); missing[0] = result(classes[0].slice(1));
  assert.throws(() => verifyShards({ expected, shards: missing }), /did not run in any shard/);
  const duplicate = shards(); duplicate[1] = result([...classes[1], classes[0][0]]);
  assert.throws(() => verifyShards({ expected, shards: duplicate }), /ran in shards|belongs to shard/);
  const moved = shards(); moved[0] = result(classes[0].slice(1)); moved[1] = result([...classes[1], classes[0][0]]);
  assert.throws(() => verifyShards({ expected, shards: moved }), /belongs to shard 1/);
  const unexpected = shards(); const stray = Array.from({ length: 50 }, (_, index) => `com.example.Stray${index}Test`).find((name) => shardOf(name) === 1);
  unexpected[0] = result([...classes[0], stray]);
  assert.throws(() => verifyShards({ expected, shards: unexpected }), /outside the expected test inventory/);
  const empty = shards(); empty[2] = result([]);
  assert.throws(() => verifyShards({ expected, shards: empty }), /executed no test classes/);
  const failing = shards(); failing[3] = result(classes[3], { failures: 1 });
  assert.throws(() => verifyShards({ expected, shards: failing }), /failures or errors/);
  assert.throws(() => verifyShards({ expected, shards: shards().slice(0, 3) }), /results for 4 shards/);
});

test('Backend #416 JUnit XML reader counts cases and maps nested classes to their top-level class', () => {
  withDirectory('backend-shard-results-', (directory) => {
    writeResults(directory, [
      { className: 'com.example.OuterTest', cases: ['a', 'b'] },
      { className: 'com.example.OuterTest$Nested', cases: ['c'], skipped: 1 },
    ]);
    const result = readShardResults(directory);
    assert.deepEqual(result.classes, ['com.example.OuterTest']);
    assert.equal(result.tests, 3);
    assert.equal(result.skipped, 1);
    assert.equal(result.cases.length, 3);
  });
  withDirectory('backend-shard-empty-', (directory) => assert.throws(() => readShardResults(directory), /no JUnit XML results/));
  withDirectory('backend-shard-bad-', (directory) => {
    writeFileSync(join(directory, 'TEST-com.example.BadTest.xml'), '<testsuite name="x" tests="one" skipped="0" failures="0" errors="0"></testsuite>');
    assert.throws(() => readShardResults(directory), /non-negative integer/);
  });
});

test('Backend #416 compiled class digest is deterministic and byte-sensitive', () => {
  withDirectory('backend-shard-classes-', (root) => {
    const first = join(root, 'first'), second = join(root, 'second');
    for (const directory of [first, second]) {
      mkdirSync(join(directory, 'com/example'), { recursive: true });
      writeFileSync(join(directory, 'com/example/A.class'), 'A');
      writeFileSync(join(directory, 'com/example/B.class'), 'B');
    }
    assert.equal(digestClasses(first), digestClasses(second));
    writeFileSync(join(second, 'com/example/B.class'), 'B2');
    assert.notEqual(digestClasses(first), digestClasses(second));
    mkdirSync(join(root, 'empty'));
    assert.throws(() => digestClasses(join(root, 'empty')), /no compiled classes/);
  });
});

test('Backend #416 verify command requires four ordered result directories and identical class digests', () => {
  const digest = 'a'.repeat(64);
  const run = (args) => spawnSync(process.execPath, [tool, 'verify', '--repo-root', repositoryRoot, ...args], { encoding: 'utf8' });
  const results = [1, 2, 3, 4].flatMap((shard) => ['--results', `/nonexistent/${shard}`]);
  assert.match(run([...results.slice(0, 6), '--summary', '/dev/null']).stderr, /given 4 times/);
  assert.match(run([...results, ...Array(4).fill(['--classes-digest', digest]).flat(), '--summary', '/dev/null']).stderr, /compiled main classes differ/);
  assert.match(run([...results, ...Array(4).fill(['--classes-digest', digest]).flat(), '--classes-digest', 'b'.repeat(64), '--summary', '/dev/null']).stderr, /compiled main classes differ/);
  const unknown = spawnSync(process.execPath, [tool, 'split'], { encoding: 'utf8' });
  assert.notEqual(unknown.status, 0);
  assert.match(unknown.stderr, /usage is plan, digest-classes, or verify/);
});
