import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { cpSync, existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const script = join(repositoryRoot, "backend/tools/build-journey-contract-bundle.mjs");
const buildRoot = join(repositoryRoot, "backend/build");
const producerSha = "a".repeat(40);

test("Journey contract bundle v2 schema는 ordered resource identities와 complete Base64를 강제한다", () => {
  const schema = readJson(join(repositoryRoot, "contracts/api/journey-v3-contract-bundle-v2.schema.json"));
  const resources = schema.properties.resources;
  const producerShaSchema = schema.properties.producerSha;
  const resourceSchema = schema.$defs.resource.properties;

  assert.equal(producerShaSchema.minLength, 40);
  assert.equal(producerShaSchema.maxLength, 40);
  assert.match(producerSha, new RegExp(producerShaSchema.pattern));
  assert.doesNotMatch("A".repeat(40), new RegExp(producerShaSchema.pattern));
  assert.equal(resourceSchema.sha256.minLength, 64);
  assert.equal(resourceSchema.sha256.maxLength, 64);
  assert.match("a".repeat(64), new RegExp(resourceSchema.sha256.pattern));
  assert.doesNotMatch("A".repeat(64), new RegExp(resourceSchema.sha256.pattern));
  for (const value of [`${"a".repeat(64)}\n`, `${"a".repeat(64)}\r\n`]) {
    assert.equal(value.length === resourceSchema.sha256.minLength && value.length === resourceSchema.sha256.maxLength && new RegExp(resourceSchema.sha256.pattern).test(value), false);
  }
  assert.equal(resources.items, false);
  assert.deepEqual(resources.prefixItems.map((item) => item.allOf[1].properties), [
    {
      id: { const: "journey-v3-error-catalog" },
      path: { const: "contracts/api/journey-v3-error-catalog.json" },
      mediaType: { const: "application/json" },
    },
    {
      id: { const: "journey-v3-error-disposition" },
      path: { const: "contracts/api/journey-v3-error-disposition.json" },
      mediaType: { const: "application/json" },
    },
    {
      id: { const: "journey-v3-openapi" },
      path: { const: "contracts/api/journey-v3.openapi.yaml" },
      mediaType: { const: "application/yaml" },
    },
  ]);

  const base64 = new RegExp(resourceSchema.contentBase64.pattern);
  for (const value of ["YQ==", "YWI=", "YWJj", "YWJjYWJj"]) assert.match(value, base64);
  for (const value of ["a", "aa=", "aaa==", "YWJ$", "YQ=A"]) assert.doesNotMatch(value, base64);
  const rejectedLineTerminator = new RegExp(resourceSchema.contentBase64.not.pattern);
  assert.match("YQ==\n", rejectedLineTerminator);
  assert.match("YQ==\r\n", rejectedLineTerminator);
  assert.match("YQ==\u2028", rejectedLineTerminator);
  assert.match("YQ==\u2029", rejectedLineTerminator);
  assert.deepEqual(resourceSchema.contentBase64.not, { pattern: "[\\r\\n\\u2028\\u2029]" });
});

test("Journey contract bundle v2는 exact raw resources를 deterministic하게 생성한다", () => {
  const fixture = createFixture();
  try {
    const firstOutput = join(fixture.outputDirectory, "first.json");
    const secondOutput = join(fixture.outputDirectory, "second.json");
    const first = run(producerSha, firstOutput, fixture.contractRoot);
    const second = run(producerSha, secondOutput, fixture.contractRoot);

    assert.deepEqual(first, second);
    assert.equal(first.at(-1), 0x0a);
    const bundle = JSON.parse(first);
    assert.deepEqual(Object.keys(bundle), [
      "schemaVersion", "bundleVersion", "component", "producerRepository", "producerSha", "resources",
    ]);
    assert.deepEqual(bundle.resources.map(({ path }) => path), [
      "contracts/api/journey-v3-error-catalog.json",
      "contracts/api/journey-v3-error-disposition.json",
      "contracts/api/journey-v3.openapi.yaml",
    ]);
    assert.deepEqual(bundle.resources.map(({ id, owner, mediaType }) => ({ id, owner, mediaType })), [
      { id: "journey-v3-error-catalog", owner: "AquilaXk/easysubway-backend", mediaType: "application/json" },
      { id: "journey-v3-error-disposition", owner: "AquilaXk/easysubway-backend", mediaType: "application/json" },
      { id: "journey-v3-openapi", owner: "AquilaXk/easysubway-backend", mediaType: "application/yaml" },
    ]);
    for (const resource of bundle.resources) {
      assert.deepEqual(Object.keys(resource), ["id", "path", "owner", "mediaType", "sha256", "contentBase64"]);
      const raw = readFileSync(resolve(repositoryRoot, resource.path));
      assert.deepEqual(Buffer.from(resource.contentBase64, "base64"), raw);
      assert.equal(resource.sha256, sha256(raw));
    }
  } finally {
    fixture.cleanup();
  }
});

test("Journey contract bundle v2는 producer SHA와 backend/build 밖 또는 symlink output을 거부한다", () => {
  const fixture = createFixture();
  try {
    const output = join(fixture.outputDirectory, "bundle.json");
    assert.throws(() => run("not-a-sha", output, fixture.contractRoot), /producer sha/i);
    assert.throws(() => run(`${producerSha}\n`, output, fixture.contractRoot), /producer sha/i);
    assert.throws(() => run(`${producerSha}\r\n`, output, fixture.contractRoot), /producer sha/i);
    assert.throws(() => run(producerSha, join(tmpdir(), "journey-contract-bundle.json"), fixture.contractRoot), /backend.build/i);

    const symlinkOutput = join(fixture.outputDirectory, "bundle-link.json");
    symlinkSync(join(fixture.contractRoot, "journey-v3-error-catalog.json"), symlinkOutput);
    assert.throws(() => run(producerSha, symlinkOutput, fixture.contractRoot), /symlink|regular/i);
  } finally {
    fixture.cleanup();
  }
});

test("Journey contract bundle v2는 test override를 production 환경에서 거부한다", () => {
  const fixture = createFixture();
  try {
    assert.throws(
      () => run(producerSha, join(fixture.outputDirectory, "bundle.json"), fixture.contractRoot, "production"),
      /contract root|override|contracts/i,
    );
  } finally {
    fixture.cleanup();
  }
});

test("Journey contract bundle v2는 digest와 raw contract 불일치를 fail closed한다", () => {
  const cases = [
    {
      name: "한 digest entry 변조",
      mutate(root) {
        const digests = readJson(join(root, "journey-v3-contract-digests.json"));
        digests.artifacts[0].sha256 = "0".repeat(64);
        writeJson(join(root, "journey-v3-contract-digests.json"), digests);
      },
    },
    {
      name: "네 번째 digest entry 추가",
      mutate(root) {
        const digests = readJson(join(root, "journey-v3-contract-digests.json"));
        digests.artifacts.push({ path: "unexpected.json", sha256: "0".repeat(64) });
        writeJson(join(root, "journey-v3-contract-digests.json"), digests);
      },
    },
    {
      name: "필수 digest entry 제거",
      mutate(root) {
        const digests = readJson(join(root, "journey-v3-contract-digests.json"));
        digests.artifacts.pop();
        writeJson(join(root, "journey-v3-contract-digests.json"), digests);
      },
    },
    {
      name: "raw resource 손상",
      mutate(root) {
        const path = join(root, "journey-v3-error-catalog.json");
        writeFileSync(path, Buffer.concat([readFileSync(path), Buffer.from(" ")]));
      },
    },
  ];

  for (const { name, mutate } of cases) {
    const fixture = createFixture();
    try {
      mutate(fixture.contractRoot);
      const output = join(fixture.outputDirectory, `${name}.json`);
      assert.throws(() => run(producerSha, output, fixture.contractRoot));
      assert.equal(existsSync(output), false, `${name}: failed producer must not leave an output`);
    } finally {
      fixture.cleanup();
    }
  }
});

function createFixture() {
  mkdirSync(buildRoot, { recursive: true });
  const directory = mkdtempSync(join(buildRoot, "journey-contract-bundle-test-"));
  const contractRoot = mkdtempSync(join(tmpdir(), "journey-contract-root-"));
  cpSync(join(repositoryRoot, "contracts/api"), contractRoot, { recursive: true });
  return {
    contractRoot,
    outputDirectory: directory,
    cleanup() {
      rmSync(directory, { recursive: true, force: true });
      rmSync(contractRoot, { recursive: true, force: true });
    },
  };
}

function run(sha, output, contractRoot, nodeEnv = "test") {
  try {
    execFileSync(process.execPath, [script, "--producer-sha", sha, "--output", output], {
      encoding: "buffer",
      env: { ...process.env, NODE_ENV: nodeEnv, EASYSUBWAY_JOURNEY_CONTRACT_ROOT: contractRoot },
      stdio: ["ignore", "pipe", "pipe"],
    });
    return readFileSync(output);
  } catch (error) {
    const failure = new Error(error.stderr?.toString() || error.message);
    failure.cause = error;
    throw failure;
  }
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value)}\n`);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}
