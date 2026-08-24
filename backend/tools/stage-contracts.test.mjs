import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const script = join(repositoryRoot, "backend/tools/stage-contracts.mjs");
const outputRoot = join(repositoryRoot, "backend/build/stage-contracts-test");
const immutableArtifactUrl = "https://raw.githubusercontent.com/AquilaXk/easysubway/6c29b55e6cbdb1713522cb4f766d9754728d5fc8/contracts/bundles/backend-contracts-v1.0.0.json";

test("stage-contracts는 해시가 고정된 정확한 두 계약을 staging한다", () => {
  const fixture = createFixture();
  try {
    run(fixture);

    assert.equal(readFileSync(join(fixture.output, "datapack/source-governance-policy.json"), "utf8"), fixture.bundle.resources["datapack/source-governance-policy.json"]);
    assert.equal(readFileSync(join(fixture.output, "datapack/datapack-freshness-sla.json"), "utf8"), fixture.bundle.resources["datapack/datapack-freshness-sla.json"]);
  } finally {
    fixture.cleanup();
  }
});

test("stage-contracts는 raw-byte hash가 다른 bundle을 거부한다", () => {
  const fixture = createFixture({ hash: "0".repeat(64) });
  try {
    assert.throws(() => run(fixture), /sha256/i);
  } finally {
    fixture.cleanup();
  }
});

test("stage-contracts는 lock과 다른 bundle version을 거부한다", () => {
  const fixture = createFixture({ bundleVersion: "1.0.1" });
  try {
    assert.throws(() => run(fixture), /bundleVersion/i);
  } finally {
    fixture.cleanup();
  }
});

test("stage-contracts는 정확한 immutable Hub artifact URL만 허용한다", () => {
  for (const artifactUrl of [
    "https://raw.githubusercontent.com/AquilaXk/easysubway/main/contracts/bundles/backend-contracts-v1.0.0.json",
    "https://raw.githubusercontent.com/AquilaXk/easysubway/6c29b55e6cbdb1713522cb4f766d9754728d5fc/contracts/bundles/backend-contracts-v1.0.0.json",
    "https://raw.githubusercontent.com/AquilaXk/easysubway/6c29b55e6cbdb1713522cb4f766d9754728d5fc80/contracts/bundles/backend-contracts-v1.0.0.json",
    `${immutableArtifactUrl}\n`,
    [immutableArtifactUrl],
    "https://example.invalid/backend-contracts-v1.0.0.json",
  ]) {
    const fixture = createFixture({ artifactUrl });
    try {
      assert.throws(() => run(fixture), /lock/i);
    } finally {
      fixture.cleanup();
    }
  }
});

test("stage-contracts는 resource 누락이나 추가를 거부한다", () => {
  for (const resources of [
    { "datapack/source-governance-policy.json": { policy: true } },
    {
      "datapack/source-governance-policy.json": { policy: true },
      "datapack/datapack-freshness-sla.json": { freshness: true },
      "datapack/unapproved.json": { extra: true },
    },
  ]) {
    const fixture = createFixture({ resources });
    try {
      assert.throws(() => run(fixture), /resource/i);
    } finally {
      fixture.cleanup();
    }
  }
});

test("stage-contracts는 JSON object 원문이 아닌 resource 값을 거부한다", () => {
  for (const resources of [
    { "datapack/source-governance-policy.json": null, "datapack/datapack-freshness-sla.json": { freshness: true } },
    { "datapack/source-governance-policy.json": "[\"policy\"]\n", "datapack/datapack-freshness-sla.json": "{\"freshness\":true}\n" },
    { "datapack/source-governance-policy.json": "{invalid", "datapack/datapack-freshness-sla.json": "{\"freshness\":true}\n" },
  ]) {
    const fixture = createFixture({ resources });
    try {
      assert.throws(() => run(fixture), /resource/i);
    } finally {
      fixture.cleanup();
    }
  }
});

test("stage-contracts는 duplicate 또는 extra CLI option을 거부한다", () => {
  const fixture = createFixture();
  try {
    assert.throws(() => run(fixture, ["--input", fixture.input]), /duplicate|unknown/i);
    assert.throws(() => run(fixture, ["--unexpected", "value"]), /unknown/i);
  } finally {
    fixture.cleanup();
  }
});

test("stage-contracts는 symlink input과 backend/build 밖 output을 거부한다", () => {
  const fixture = createFixture();
  const link = join(fixture.directory, "bundle-link.json");
  symlinkSync(fixture.input, link);
  try {
    assert.throws(() => run({ ...fixture, input: link }), /regular file|symlink/i);
    assert.throws(() => run({ ...fixture, output: join(fixture.directory, "outside") }), /backend.build|output/i);
  } finally {
    fixture.cleanup();
  }
});

test("stage-contracts는 symlink output parent를 거부하고 외부 sentinel을 변경하지 않는다", () => {
  const fixture = createFixture();
  const sentinelDirectory = mkdtempSync(join(tmpdir(), "stage-contracts-sentinel-"));
  const sentinel = join(sentinelDirectory, "sentinel.txt");
  const link = join(dirname(fixture.output), "escape");
  const escapedOutput = join(link, "staged");
  writeFileSync(sentinel, "unchanged\n");
  symlinkSync(sentinelDirectory, link);
  let error;
  try {
    try {
      run({ ...fixture, output: escapedOutput });
    } catch (caught) {
      error = caught;
    }
    assert.ok(error, "symlink output parent must fail closed");
    assert.equal(readFileSync(sentinel, "utf8"), "unchanged\n");
    assert.equal(existsSync(join(sentinelDirectory, "staged")), false);
  } finally {
    unlinkSync(link);
    fixture.cleanup();
    rmSync(sentinelDirectory, { recursive: true, force: true });
  }
});

function createFixture(options = {}) {
  const directory = mkdtempSync(join(tmpdir(), "stage-contracts-"));
  const output = join(outputRoot, directory.split("/").at(-1));
  mkdirSync(dirname(output), { recursive: true });
  const bundle = {
    schemaVersion: 1,
    bundleVersion: options.bundleVersion ?? "1.0.0",
    resources: options.resources ?? {
      "datapack/source-governance-policy.json": "{\n  \"policy\": true,\n  \"values\": [1, 2]\n}\n",
      "datapack/datapack-freshness-sla.json": "{\"freshness\":true}\n",
    },
  };
  const input = join(directory, "bundle.json");
  const bytes = `${JSON.stringify(bundle)}\n`;
  writeFileSync(input, bytes);
  writeFileSync(join(directory, "lock.json"), `${JSON.stringify({
    schemaVersion: 1,
    bundleVersion: "1.0.0",
    artifactUrl: options.artifactUrl ?? immutableArtifactUrl,
    sha256: options.hash ?? createHash("sha256").update(bytes).digest("hex"),
  })}\n`);
  return {
    directory,
    input,
    lock: join(directory, "lock.json"),
    output,
    bundle,
    cleanup() {
      rmSync(directory, { recursive: true, force: true });
      rmSync(output, { recursive: true, force: true });
    },
  };
}

function run(fixture, extraArguments = []) {
  return execFileSync(process.execPath, [script, "--lock", fixture.lock, "--input", fixture.input, "--output", fixture.output, ...extraArguments], { encoding: "utf8", stdio: "pipe" });
}
