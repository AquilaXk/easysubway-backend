#!/usr/bin/env node
// Backend CI 테스트 shard 분할·검증 (Backend #416).
//
// 분할 규칙은 tools/ci/gradle/backend-ci.init.gradle과 같다: 테스트 클래스의 최상위 binary 이름
// (중첩 클래스의 `$` 앞부분, 점 구분)을 UTF-8 SHA-256으로 해시해 앞 8자리 16진수를 정수로 읽고
// `% shardCount + 1`을 shard 번호로 쓴다. 중첩 클래스는 바깥 클래스와 같은 shard에 들어간다.
// 집계 job은 각 shard의 JUnit XML로 실제 실행 클래스를 모아, 모든 테스트 클래스가 정확히 한
// shard에서 실행됐는지(누락·중복·오배정 없음)를 확인한다. 하나라도 어긋나면 실패한다.
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { isMainModule } from '../lib/is-main-module.mjs';

export const SHARD_COUNT = 4;
const TEST_ROOT = 'backend/src/test/java';
const TEST_ANNOTATION = /@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|ArchTest)\b/;
const fail = (message) => { throw new Error(`backend test shards: ${message}`); };

export const topLevelClass = (binaryName) => {
  if (typeof binaryName !== 'string' || !/^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/.test(binaryName)) fail(`invalid class name ${JSON.stringify(binaryName)}`);
  const top = binaryName.split('$')[0];
  if (top.length === 0 || top.endsWith('.')) fail(`invalid class name ${JSON.stringify(binaryName)}`);
  return top;
};

export const shardOf = (binaryName, shardCount = SHARD_COUNT) => {
  if (!Number.isInteger(shardCount) || shardCount < 1) fail('shard count must be a positive integer');
  const hex = createHash('sha256').update(topLevelClass(binaryName), 'utf8').digest('hex');
  return (Number.parseInt(hex.slice(0, 8), 16) % shardCount) + 1;
};

const walk = (directory) => readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
  const path = join(directory, entry.name);
  if (entry.isSymbolicLink()) fail(`symbolic link in test sources: ${path}`);
  return entry.isDirectory() ? walk(path) : [path];
});

// 테스트 소스 중 JUnit 테스트 어노테이션을 가진 최상위 클래스(추상 클래스 제외)가 실행 기대 집합이다.
export function expectedTestClasses(repositoryRoot) {
  const root = resolve(repositoryRoot, TEST_ROOT);
  return walk(root).filter((path) => path.endsWith('.java')).flatMap((path) => {
    const source = readFileSync(path, 'utf8');
    if (!TEST_ANNOTATION.test(source)) return [];
    const binaryName = relative(root, path).slice(0, -'.java'.length).split(sep).join('.');
    const simpleName = binaryName.slice(binaryName.lastIndexOf('.') + 1);
    if (new RegExp(`^\\s*(?:public\\s+|protected\\s+)?abstract\\s+class\\s+${simpleName}\\b`, 'm').test(source)) return [];
    return [topLevelClass(binaryName)];
  }).sort();
}

export function planShards(classes, shardCount = SHARD_COUNT) {
  const plan = Array.from({ length: shardCount }, () => []);
  for (const name of classes) plan[shardOf(name, shardCount) - 1].push(name);
  return plan;
}

const attribute = (tag, name) => {
  const match = new RegExp(`\\s${name}="([^"]*)"`).exec(tag);
  return match ? match[1] : null;
};
const xmlNumber = (value, label) => {
  if (value === null || !/^(?:0|[1-9]\d*)$/.test(value)) fail(`${label} is not a non-negative integer`);
  return Number(value);
};

// JUnit XML 결과 디렉터리 하나를 읽어 실행된 최상위 클래스와 건수를 돌려준다.
export function readShardResults(directory) {
  const files = readdirSync(directory).filter((name) => /^TEST-.+\.xml$/.test(name)).sort();
  if (files.length === 0) fail(`no JUnit XML results in ${directory}`);
  const classes = new Set(), cases = [];
  let tests = 0, skipped = 0, failures = 0, errors = 0;
  for (const name of files) {
    const path = join(directory, name);
    if (!lstatSync(path).isFile()) fail(`${path} is not a regular file`);
    const xml = readFileSync(path, 'utf8');
    const suite = /<testsuite\b[^>]*>/.exec(xml)?.[0];
    if (!suite) fail(`${path} has no testsuite`);
    tests += xmlNumber(attribute(suite, 'tests'), `${name} tests`);
    skipped += xmlNumber(attribute(suite, 'skipped'), `${name} skipped`);
    failures += xmlNumber(attribute(suite, 'failures'), `${name} failures`);
    errors += xmlNumber(attribute(suite, 'errors'), `${name} errors`);
    for (const match of xml.matchAll(/<testcase\b[^>]*>/g)) {
      const className = attribute(match[0], 'classname'), caseName = attribute(match[0], 'name');
      if (className === null || caseName === null) fail(`${name} has a testcase without classname or name`);
      classes.add(topLevelClass(className));
      cases.push(`${className}\u0000${caseName}`);
    }
  }
  return { classes: [...classes].sort(), cases, tests, skipped, failures, errors };
}

export function verifyShards({ expected, shards, shardCount = SHARD_COUNT }) {
  if (!Array.isArray(shards) || shards.length !== shardCount) fail(`expected results for ${shardCount} shards`);
  const owner = new Map(), totals = { tests: 0, skipped: 0, failures: 0, errors: 0, classes: 0 };
  shards.forEach((result, index) => {
    const shard = index + 1;
    if (result.classes.length === 0) fail(`shard ${shard} executed no test classes`);
    if (result.failures !== 0 || result.errors !== 0) fail(`shard ${shard} reported failures or errors`);
    for (const name of result.classes) {
      if (owner.has(name)) fail(`test class ${name} ran in shards ${owner.get(name)} and ${shard}`);
      if (shardOf(name, shardCount) !== shard) fail(`test class ${name} ran in shard ${shard} but belongs to shard ${shardOf(name, shardCount)}`);
      owner.set(name, shard);
    }
    for (const key of ['tests', 'skipped', 'failures', 'errors']) totals[key] += result[key];
  });
  const expectedSet = new Set(expected);
  const missing = expected.filter((name) => !owner.has(name));
  const unexpected = [...owner.keys()].filter((name) => !expectedSet.has(name)).sort();
  if (missing.length > 0) fail(`test classes did not run in any shard: ${missing.join(', ')}`);
  if (unexpected.length > 0) fail(`shards ran classes outside the expected test inventory: ${unexpected.join(', ')}`);
  totals.classes = owner.size;
  return { ...totals, perShard: shards.map(({ classes, tests }, index) => ({ shard: index + 1, classes: classes.length, tests })) };
}

// 컴파일된 클래스 디렉터리의 결정론적 digest(상대 경로·바이트). shard와 집계 job의 main 클래스가
// 같은 바이트코드여야 JaCoCo 실행 데이터가 보고서 클래스와 맞는다.
export function digestClasses(directory) {
  const root = resolve(directory);
  const files = walk(root).map((path) => relative(root, path).split(sep).join('/')).sort();
  if (files.length === 0) fail(`no compiled classes in ${directory}`);
  const hash = createHash('sha256');
  for (const path of files) hash.update(`${path}\u0000${createHash('sha256').update(readFileSync(join(root, path))).digest('hex')}\n`);
  return hash.digest('hex');
}

const options = (values) => {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index], value = values[index + 1];
    if (!/^--[a-z][a-z-]*$/.test(key ?? '') || value === undefined) fail('named options required');
    (result[key.slice(2)] ??= []).push(value);
  }
  return result;
};
const single = (option, key) => {
  if (!option[key] || option[key].length !== 1 || option[key][0].length === 0) fail(`--${key} is required once`);
  return option[key][0];
};

export function main(argv = process.argv.slice(2)) {
  const [command, ...rest] = argv, option = options(rest);
  if (command === 'plan') {
    const plan = planShards(expectedTestClasses(single(option, 'repo-root')));
    process.stdout.write(`${JSON.stringify(plan.map((classes, index) => ({ shard: index + 1, classes: classes.length })))}\n`);
    return;
  }
  if (command === 'digest-classes') {
    process.stdout.write(`${digestClasses(single(option, 'dir'))}\n`);
    return;
  }
  if (command === 'verify') {
    const results = option.results ?? [];
    if (results.length !== SHARD_COUNT) fail(`--results must be given ${SHARD_COUNT} times in shard order`);
    const digests = option['classes-digest'] ?? [];
    if (digests.length !== SHARD_COUNT + 1 || digests.some((value) => !/^[a-f0-9]{64}$/.test(value)) || new Set(digests).size !== 1) fail('compiled main classes differ between shards and the report job');
    const summary = verifyShards({ expected: expectedTestClasses(single(option, 'repo-root')), shards: results.map(readShardResults) });
    const lines = [
      '## Backend test shards', '',
      `- shards: ${SHARD_COUNT}`, `- test classes: ${summary.classes}`, `- tests: ${summary.tests} (skipped ${summary.skipped})`,
      ...summary.perShard.map(({ shard, classes, tests }) => `- shard ${shard}: ${classes} classes, ${tests} tests`), '',
    ];
    writeFileSync(single(option, 'summary'), `${lines.join('\n')}`);
    process.stdout.write(`${JSON.stringify(summary)}\n`);
    return;
  }
  fail('usage is plan, digest-classes, or verify');
}

if (isMainModule(import.meta.url)) {
  try { main(); } catch (error) { process.stderr.write(`${error.message}\n`); process.exitCode = 1; }
}
