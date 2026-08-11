import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, existsSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const buildRoot = join(repositoryRoot, "backend/build");
const outputRoot = join(buildRoot, "stage-journey-contracts-test");
const stager = join(repositoryRoot, "backend/tools/stage-journey-contracts.mjs");
const trackedLock = join(repositoryRoot, "backend/journey-contracts.lock.json");
const journeyLock = JSON.parse(readFileSync(trackedLock, "utf8"));
const journeyArtifact = `${journeyLock.artifact.repository}@${journeyLock.artifact.manifestDigest}`;

test("CI와 release 및 Gradle은 digest-pinned Journey OCI payload의 현재 staging만 소비한다", () => {
  const ci = readFileSync(join(repositoryRoot, ".github/workflows/ci.yml"), "utf8");
  const release = readFileSync(join(repositoryRoot, ".github/workflows/release-artifacts.yml"), "utf8");
  const build = readFileSync(join(repositoryRoot, "backend/build.gradle"), "utf8");
  const stageCommand = new RegExp(
    `node backend/tools/stage-journey-contracts\\.mjs\\s+\\\\\\n\\s+--lock backend/journey-contracts\\.lock\\.json\\s+\\\\\\n\\s+--input "\\$\\{RUNNER_TEMP\\}/journey-contract-pull/journey-v3-contract-bundle-v2\\.json"\\s+\\\\\\n\\s+--output backend/build/journey-contracts-staging`,
  );

  assert.equal(journeyLock.artifact.repository, "ghcr.io/aquilaxk/easysubway-backend-contracts");
  assert.match(journeyLock.artifact.manifestDigest, /^sha256:[a-f0-9]{64}$/);
  assert.match(journeyArtifact, /^ghcr\.io\/aquilaxk\/easysubway-backend-contracts@sha256:[a-f0-9]{64}$/);
  for (const workflow of [ci, release]) {
    assert.match(workflow, /journey_contract_subject="\$\(jq -er .*backend\/journey-contracts\.lock\.json\)"/s);
    assert.match(workflow, /\[\[ "\$\{journey_contract_subject\}" =~ \^ghcr\\\.io\/aquilaxk\/easysubway-backend-contracts@sha256:\[a-f0-9\]\{64\}\$ \]\]/);
    assert.match(workflow, /oras pull "\$\{journey_contract_subject\}" --output "\$\{RUNNER_TEMP\}\/journey-contract-pull"/);
    assert.match(workflow, stageCommand);
    assert.doesNotMatch(workflow, new RegExp(journeyArtifact.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
    assert.doesNotMatch(workflow, /ghcr\.io\/aquilaxk\/easysubway-backend-contracts@sha256:/);
  }
  assert.equal(countMatches(ci, /journey_contract_subject="\$\(jq -er/g), 1);
  assert.equal(countMatches(ci, /oras pull "\$\{journey_contract_subject\}"/g), 1);
  assert.equal(countMatches(ci, /node backend\/tools\/stage-journey-contracts\.mjs/g), 1);
  assert.equal(countMatches(release, /journey_contract_subject="\$\(jq -er/g), 2);
  assert.equal(countMatches(release, /oras pull "\$\{journey_contract_subject\}"/g), 2);
  assert.equal(countMatches(release, /node backend\/tools\/stage-journey-contracts\.mjs/g), 2);
  for (const workflow of [ci, release]) {
    assert.match(workflow, /# v1 datapack은 Journey OCI artifact의 fallback이 아니라 별도 backend resource다\.[\s\S]*node backend\/tools\/stage-contracts\.mjs/);
    assert.match(workflow, /EASYSUBWAY_CONTRACTS_BUNDLE: \$\{\{ runner\.temp \}\}\/backend-contracts\.json/);
  }
  assert.match(build, /def journeyContractsStaging = layout\.buildDirectory\.dir\('journey-contracts-staging'\)/);
  assert.match(build, /inputs\.file\(journeyContractsLock\)/);
  assert.match(build, /payloadSha256 ==~ \/\[a-f0-9\]\{64\}\//);
  assert.match(build, /Files\.isRegularFile\(marker\.toPath\(\), java\.nio\.file\.LinkOption\.NOFOLLOW_LINKS\)/);
  assert.match(build, /marker\.getText\('UTF-8'\) != "\$\{payloadSha256\}\\n"/);
  assert.match(build, /from\(stageContracts\)/);
  assert.match(build, /from\(layout\.buildDirectory\.dir\('journey-contracts-staging'\)\)/);
});

function countMatches(value, pattern) {
  return [...value.matchAll(pattern)].length;
}

test("stage-journey-contracts는 lock과 일치하는 raw resources만 원자적으로 staging한다", () => {
  const fixture = createFixture();
  try {
    runStager(fixture);
    for (const resource of fixture.lock.resources) {
      assert.deepEqual(readFileSync(join(fixture.output, resource.path)), readFileSync(join(repositoryRoot, resource.path)));
    }
    assert.equal(readFileSync(join(fixture.output, ".stage-complete"), "utf8"), `${fixture.lock.payload.sha256}\n`);
  } finally {
    fixture.cleanup();
  }
});

test("stage-journey-contracts는 missing output parent를 만들지 않는다", () => {
  const fixture = createFixture();
  const missingParent = join(fixture.directory, "missing-parent");
  fixture.output = join(missingParent, "staged");
  try {
    assert.throws(() => runStager(fixture), /output parent must already exist/i);
    assert.equal(existsSync(missingParent), false);
  } finally {
    fixture.cleanup();
  }
});

test("stage-journey-contracts는 publish 직전 output 교체가 writes를 redirect하지 않는다", async () => {
  const { stageAtomically } = await import(`${pathToFileURL(stager).href}?late-target`);
  const fixture = createFixture();
  const escapedParent = mkdtempSync(join(outputRoot, "escaped-"));
  let replaced = false;
  try {
    assert.throws(
      () => stageAtomically(fixture.output, bundleResources(fixture), {
        payloadSha256: fixture.lock.payload.sha256,
        beforePublish() {
          mkdirSync(fixture.output);
          rmSync(fixture.output, { recursive: true });
          symlinkSync(escapedParent, fixture.output, "dir");
          replaced = true;
        },
      }),
      /final output must be absent|secure directory/i,
    );
    assert.equal(existsSync(join(escapedParent, ".stage-complete")), false);
  } finally {
    if (replaced) rmSync(fixture.output, { force: true });
    fixture.cleanup();
    rmSync(escapedParent, { recursive: true, force: true });
  }
});

test("stage-journey-contracts는 publish 직전 late empty output을 덮어쓰지 않는다", async () => {
  const { stageAtomically } = await import(`${pathToFileURL(stager).href}?late-empty-target`);
  const fixture = createFixture();
  try {
    assert.throws(
      () => stageAtomically(fixture.output, bundleResources(fixture), {
        payloadSha256: fixture.lock.payload.sha256,
        beforePublish() {
          mkdirSync(fixture.output);
        },
      }),
      /final output must be absent/i,
    );
    assert.deepEqual(readdirSync(fixture.output), []);
  } finally {
    fixture.cleanup();
  }
});

test("stage-journey-contracts는 검사한 nested ancestor가 교체돼도 외부 parent에 결속하지 않는다", async () => {
  const { stageAtomically } = await import(`${pathToFileURL(stager).href}?ancestor-swap`);
  const fixture = createFixture();
  const nestedParent = join(fixture.directory, "nested");
  fixture.output = join(nestedParent, "staged");
  mkdirSync(nestedParent);
  const movedParent = `${nestedParent}-moved`;
  const escapedParent = mkdtempSync(join(outputRoot, "escaped-"));
  let swapped = false;
  try {
    assert.throws(
      () => stageAtomically(fixture.output, bundleResources(fixture), {
        payloadSha256: fixture.lock.payload.sha256,
        beforePublish() {
          renameSync(nestedParent, movedParent);
          symlinkSync(escapedParent, nestedParent, "dir");
          swapped = true;
        },
      }),
      /output must not have a symlink or non-directory ancestor|secure directory/i,
    );
    assert.equal(existsSync(join(escapedParent, "staged")), false);
  } finally {
    if (swapped) {
      rmSync(nestedParent, { force: true });
      renameSync(movedParent, nestedParent);
    }
    fixture.cleanup();
    rmSync(escapedParent, { recursive: true, force: true });
  }
});

test("stage-journey-contracts는 secure directory stream 미지원 시 mutation 없이 실패한다", async () => {
  const { stageAtomically } = await import(`${pathToFileURL(stager).href}?unsupported-secure-directory-stream`);
  const fixture = createFixture();
  try {
    assert.throws(
      () => stageAtomically(fixture.output, bundleResources(fixture), {
        payloadSha256: fixture.lock.payload.sha256,
        forceUnsupportedSecureDirectoryStream: true,
      }),
      /secure directory stream unavailable/i,
    );
    assert.equal(existsSync(fixture.output), false);
  } finally {
    fixture.cleanup();
  }
});

test("stage-journey-contracts는 regular input을 연 뒤 path가 교체돼도 같은 descriptor bytes만 읽는다", async () => {
  const { readRegularFile } = await import(`${pathToFileURL(stager).href}?input-swap`);
  const fixture = createFixture();
  const movedInput = `${fixture.input}-opened`;
  const replacement = join(fixture.directory, "replacement.json");
  const expected = readFileSync(fixture.input);
  writeFileSync(replacement, "replacement\n");
  try {
    const actual = readRegularFile(fixture.input, "input", {
      afterOpen() {
        renameSync(fixture.input, movedInput);
        symlinkSync(replacement, fixture.input);
      },
    });
    assert.deepEqual(actual, expected);
  } finally {
    fixture.cleanup();
  }
});

test("stage-journey-contracts는 current payload/resource identity drift 뒤 final output을 만들지 않는다", () => {
  for (const { mutate, expectedError } of [
    { mutate: mutatePayloadByte, expectedError: /payload sha256 mismatch/i },
    { mutate: mutateResourceDigest, expectedError: /bundle resource identity mismatch/i },
    { mutate: mutateResourceContent, expectedError: /resource sha256 mismatch/i },
    { mutate: mutateNonCanonicalBase64, expectedError: /invalid resource Base64/i },
  ]) {
    const fixture = createFixture({ mutate });
    try {
      assert.throws(() => runStager(fixture), expectedError);
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
  const bundle = Buffer.from(`${JSON.stringify({
    schemaVersion: 2,
    bundleVersion: lock.bundleVersion,
    component: lock.component,
    producerRepository: lock.producer.repository,
    producerSha: lock.producer.gitSha,
    resources: lock.resources.map((resource) => ({
      ...resource,
      contentBase64: readFileSync(join(repositoryRoot, resource.path)).toString("base64"),
    })),
  })}\n`);
  assert.equal(createHash("sha256").update(bundle).digest("hex"), lock.payload.sha256);
  writeFileSync(input, bundle);
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

function bundleResources(fixture) {
  return JSON.parse(readFileSync(fixture.input, "utf8")).resources.map((resource) => ({
    path: resource.path,
    bytes: Buffer.from(resource.contentBase64, "base64"),
  }));
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

function mutateResourceContent(fixture) {
  const { input } = fixture;
  const bundle = JSON.parse(readFileSync(input, "utf8"));
  bundle.resources[0].contentBase64 = Buffer.from("changed\n").toString("base64");
  writeJson(input, bundle);
  refreshPayloadDigest(fixture);
}

function mutateNonCanonicalBase64(fixture) {
  const { input } = fixture;
  const bundle = JSON.parse(readFileSync(input, "utf8"));
  bundle.resources[0].contentBase64 += "\n";
  writeJson(input, bundle);
  refreshPayloadDigest(fixture);
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

function refreshPayloadDigest({ lock, lockPath, input }) {
  lock.payload.sha256 = createHash("sha256").update(readFileSync(input)).digest("hex");
  writeJson(lockPath, lock);
}
