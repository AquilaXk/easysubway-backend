import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const buildRoot = join(repositoryRoot, "backend/build");
const outputRoot = join(buildRoot, "stage-journey-contracts-test");
const stager = join(repositoryRoot, "backend/tools/stage-journey-contracts.mjs");
const builder = join(repositoryRoot, "backend/tools/build-journey-contract-bundle.mjs");
const trackedLock = join(repositoryRoot, "backend/journey-contracts.lock.json");

test("stage-journey-contracts는 lock과 일치하는 raw resources만 원자적으로 staging한다", () => {
  const fixture = createFixture();
  try {
    runStager(fixture);
    for (const resource of fixture.lock.resources) {
      assert.deepEqual(readFileSync(join(fixture.output, resource.path)), readFileSync(join(repositoryRoot, resource.path)));
    }
  } finally {
    fixture.cleanup();
  }
});

test("stage-journey-contracts는 current payload/resource identity drift 뒤 final output을 만들지 않는다", () => {
  for (const mutate of [mutatePayloadByte, mutateResourceDigest, mutateResourceContent]) {
    const fixture = createFixture({ mutate });
    try {
      assert.throws(() => runStager(fixture));
      assert.equal(existsSync(fixture.output), false);
    } finally {
      fixture.cleanup();
    }
  }
});

test("stage-journey-contracts는 malformed lock, duplicate/extra resource와 unsafe path를 거부한다", () => {
  for (const mutate of [addLockKey, removeLockKey, duplicateResourceId, duplicateResourcePath, addResource, setTraversalPath]) {
    const fixture = createFixture({ mutate });
    try {
      assert.throws(() => runStager(fixture));
      assert.equal(existsSync(fixture.output), false);
    } finally {
      fixture.cleanup();
    }
  }
});

test("stage-journey-contracts는 symlink/non-regular input과 output escape/symlink ancestor를 거부한다", () => {
  for (const fixture of [createSymlinkInputFixture(), createDirectoryInputFixture(), createSymlinkLockFixture(), createSymlinkOutputFixture()]) {
    try {
      assert.throws(() => runStager(fixture));
      assert.equal(readFileSync(fixture.sentinel, "utf8"), "unchanged\n");
      assert.equal(existsSync(fixture.escapedOutput), false);
    } finally {
      fixture.cleanup();
    }
  }
});

function createFixture({ mutate } = {}) {
  mkdirSync(outputRoot, { recursive: true });
  const directory = mkdtempSync(join(outputRoot, "fixture-"));
  const lockPath = join(directory, "journey-contracts.lock.json");
  cpSync(trackedLock, lockPath);
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const input = join(directory, lock.payload.fileName);
  execFileSync(process.execPath, [builder, "--producer-sha", lock.producer.gitSha, "--output", input], { stdio: "pipe" });
  if (mutate) mutate({ directory, lock, lockPath, input });
  return {
    directory,
    lock: JSON.parse(readFileSync(lockPath, "utf8")),
    lockPath,
    input,
    output: join(directory, "staged"),
    cleanup() {
      rmSync(directory, { recursive: true, force: true });
    },
  };
}

function createSymlinkInputFixture() {
  const fixture = createFixture();
  const sentinel = join(fixture.directory, "sentinel.txt");
  const inputLink = join(fixture.directory, "input-link.json");
  writeFileSync(sentinel, "unchanged\n");
  symlinkSync(fixture.input, inputLink);
  return { ...fixture, input: inputLink, sentinel, escapedOutput: join(fixture.directory, "escaped") };
}

function createDirectoryInputFixture() {
  const fixture = createFixture();
  const sentinel = join(fixture.directory, "sentinel.txt");
  const inputDirectory = join(fixture.directory, "input-directory");
  writeFileSync(sentinel, "unchanged\n");
  mkdirSync(inputDirectory);
  return { ...fixture, input: inputDirectory, sentinel, escapedOutput: join(fixture.directory, "escaped") };
}

function createSymlinkLockFixture() {
  const fixture = createFixture();
  const sentinel = join(fixture.directory, "sentinel.txt");
  const lockLink = join(fixture.directory, "lock-link.json");
  writeFileSync(sentinel, "unchanged\n");
  symlinkSync(fixture.lockPath, lockLink);
  return { ...fixture, lockPath: lockLink, sentinel, escapedOutput: join(fixture.directory, "escaped") };
}

function createSymlinkOutputFixture() {
  const fixture = createFixture();
  const sentinelDirectory = mkdtempSync(join(outputRoot, "sentinel-"));
  const sentinel = join(sentinelDirectory, "sentinel.txt");
  const escapedOutput = join(sentinelDirectory, "staged");
  writeFileSync(sentinel, "unchanged\n");
  const outputLink = join(fixture.directory, "output-link");
  symlinkSync(sentinelDirectory, outputLink);
  return {
    ...fixture,
    output: join(outputLink, "staged"),
    sentinel,
    escapedOutput,
    cleanup() {
      rmSync(fixture.directory, { recursive: true, force: true });
      rmSync(sentinelDirectory, { recursive: true, force: true });
    },
  };
}

function mutatePayloadByte({ input }) {
  const bytes = readFileSync(input);
  bytes[bytes.length - 2] ^= 1;
  writeFileSync(input, bytes);
}

function mutateResourceDigest({ lock, lockPath }) {
  lock.resources[0].sha256 = "0".repeat(64);
  writeJson(lockPath, lock);
}

function mutateResourceContent({ input }) {
  const bundle = JSON.parse(readFileSync(input, "utf8"));
  bundle.resources[0].contentBase64 = Buffer.from("changed\n").toString("base64");
  writeJson(input, bundle);
}

function addLockKey({ lock, lockPath }) {
  lock.extra = true;
  writeJson(lockPath, lock);
}

function removeLockKey({ lock, lockPath }) {
  delete lock.artifact;
  writeJson(lockPath, lock);
}

function duplicateResourceId({ lock, lockPath }) {
  lock.resources[1].id = lock.resources[0].id;
  writeJson(lockPath, lock);
}

function duplicateResourcePath({ lock, lockPath }) {
  lock.resources[1].path = lock.resources[0].path;
  writeJson(lockPath, lock);
}

function addResource({ lock, lockPath }) {
  lock.resources.push({ ...lock.resources[0], id: "unexpected" });
  writeJson(lockPath, lock);
}

function setTraversalPath({ lock, lockPath }) {
  lock.resources[0].path = "../escape.json";
  writeJson(lockPath, lock);
}

function runStager(fixture) {
  try {
    return execFileSync(process.execPath, [stager, "--lock", fixture.lockPath, "--input", fixture.input, "--output", fixture.output], { encoding: "utf8", stdio: "pipe" });
  } catch (error) {
    throw new Error(error.stderr?.toString() || error.message, { cause: error });
  }
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value)}\n`);
}
