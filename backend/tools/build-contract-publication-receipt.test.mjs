import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const script = join(repositoryRoot, "backend/tools/build-contract-publication-receipt.mjs");
const outputRoot = join(repositoryRoot, "release-artifacts/backend");
const repository = "ghcr.io/aquilaxk/easysubway-backend-contracts";
const gitSha = "a".repeat(40);
const artifactType = "application/vnd.easysubway.journey.contract-bundle.v2";
const layerMediaType = "application/vnd.easysubway.journey.contract-bundle.v2+json";

test("Journey contract publication receipt는 exact raw manifest와 bundle을 deterministic receipt로 결속한다", () => {
  const fixture = createFixture();
  try {
    const first = run(fixture, join(fixture.directory, "first.json"));
    const second = run(fixture, join(fixture.directory, "second.json"));
    assert.deepEqual(first, second);
    assert.equal(first.at(-1), 0x0a);
    assert.deepEqual(JSON.parse(first), {
      schemaVersion: 1,
      component: "backend",
      bundleVersion: "2.0.0",
      producer: { repository: "AquilaXk/easysubway-backend", gitSha },
      artifact: { repository, manifestDigest: `sha256:${sha256(fixture.manifest)}`, artifactType },
      payload: { fileName: "journey-v3-contract-bundle-v2.json", mediaType: layerMediaType, sha256: sha256(fixture.bundle) },
    });
  } finally {
    fixture.cleanup();
  }
});

test("Journey contract publication receipt는 descriptor와 raw manifest contract 위반을 fail closed한다", () => {
  const cases = [
    ["producer SHA", (fixture) => ({ gitSha: "invalid" })],
    ["different valid producer SHA", (fixture) => ({ gitSha: "b".repeat(40) })],
    ["descriptor digest", (fixture) => mutateJson(fixture.descriptorPath, (value) => { value.digest = `sha256:${"0".repeat(64)}`; })],
    ["descriptor reference", (fixture) => mutateJson(fixture.descriptorPath, (value) => { value.reference = `${repository}@sha256:${"0".repeat(64)}`; })],
    ["descriptor raw manifest SHA binding", (fixture) => mutateJson(fixture.descriptorPath, (value) => {
      const wrongDigest = `sha256:${"0".repeat(64)}`;
      value.digest = wrongDigest;
      value.reference = `${repository}@${wrongDigest}`;
    })],
    ["descriptor media type", (fixture) => mutateJson(fixture.descriptorPath, (value) => { value.mediaType = "application/json"; })],
    ["descriptor artifact type", (fixture) => mutateJson(fixture.descriptorPath, (value) => { value.artifactType = "application/json"; })],
    ["raw artifact type", (fixture) => mutateManifest(fixture, (value) => { value.artifactType = "application/json"; })],
    ["raw layer count", (fixture) => mutateManifest(fixture, (value) => { value.layers.push({ ...value.layers[0] }); })],
    ["raw layer media type", (fixture) => mutateManifest(fixture, (value) => { value.layers[0].mediaType = "application/json"; })],
    ["raw layer title", (fixture) => mutateManifest(fixture, (value) => { value.layers[0].annotations["org.opencontainers.image.title"] = "other.json"; })],
    ["raw layer size", (fixture) => mutateManifest(fixture, (value) => { value.layers[0].size += 1; })],
    ["raw layer digest", (fixture) => mutateManifest(fixture, (value) => { value.layers[0].digest = `sha256:${"0".repeat(64)}`; })],
  ];
  for (const [name, mutate] of cases) {
    const fixture = createFixture();
    try {
      const options = mutate(fixture) ?? {};
      const output = join(fixture.directory, `${name}.json`);
      assert.throws(() => run(fixture, output, options));
      assert.equal(existsSync(output), false, `${name}: failed receipt must not leave output`);
    } finally {
      fixture.cleanup();
    }
  }
});

test("Journey contract publication receipt는 confined output, symlink, malformed CLI와 partial temp를 fail closed한다", () => {
  const fixture = createFixture();
  try {
    const output = join(fixture.directory, "receipt.json");
    assert.throws(() => run(fixture, output, { extra: ["--unknown", "value"] }), /unknown option/i);
    assert.throws(() => run(fixture, output, { extra: ["--manifest", fixture.manifestPath] }), /duplicate option/i);
    assert.throws(() => run(fixture, join(repositoryRoot, "release-artifacts", "escape.json")), /release-artifacts/i);
    const bundleLink = join(fixture.directory, "bundle-link.json");
    symlinkSync(fixture.bundlePath, bundleLink);
    assert.throws(() => run(fixture, output, { bundle: bundleLink }), /regular|symlink/i);
    const manifestLink = join(fixture.directory, "manifest-link.json");
    symlinkSync(fixture.manifestPath, manifestLink);
    assert.throws(() => run(fixture, output, { manifest: manifestLink }), /regular|symlink/i);
    const descriptorLink = join(fixture.directory, "descriptor-link.json");
    symlinkSync(fixture.descriptorPath, descriptorLink);
    fixture.descriptorPath = descriptorLink;
    assert.throws(() => run(fixture, output), /regular|symlink/i);
    fixture.descriptorPath = join(fixture.directory, "descriptor.json");
    const outputLink = join(fixture.directory, "output-link.json");
    symlinkSync(fixture.bundlePath, outputLink);
    assert.throws(() => run(fixture, outputLink), /regular|symlink/i);
    const ancestorLink = join(fixture.directory, "ancestor-link");
    symlinkSync(fixture.directory, ancestorLink);
    assert.throws(() => run(fixture, join(ancestorLink, "receipt.json")), /symlink|parent/i);
    const partialOutput = join(fixture.directory, "partial.json");
    assert.throws(() => run(fixture, partialOutput, { env: { NODE_ENV: "test", EASYSUBWAY_RECEIPT_TEST_PARTIAL_WRITE: "1" } }), /partial/i);
    assert.equal(existsSync(partialOutput), false);
    assert.deepEqual(readdirSync(fixture.directory).filter((entry) => entry.startsWith("partial.json.tmp-")), []);
    assert.throws(() => run(fixture, output, { env: { EASYSUBWAY_RECEIPT_TEST_PARTIAL_WRITE: "1" } }), /test-only|partial/i);
  } finally {
    fixture.cleanup();
  }
});

function createFixture() {
  mkdirSync(outputRoot, { recursive: true });
  const directory = mkdtempSync(join(outputRoot, "journey-contract-receipt-test-"));
  const bundle = Buffer.from(`${JSON.stringify({ schemaVersion: 2, bundleVersion: "2.0.0", component: "backend", producerRepository: "AquilaXk/easysubway-backend", producerSha: gitSha, resources: [] })}\n`);
  const bundlePath = join(directory, "journey-v3-contract-bundle-v2.json");
  const manifestPath = join(directory, "manifest.json");
  const descriptorPath = join(directory, "descriptor.json");
  writeFileSync(bundlePath, bundle);
  const manifest = Buffer.from(`${JSON.stringify(manifestFor(bundle))}\n`);
  writeFileSync(manifestPath, manifest);
  writeJson(descriptorPath, { reference: `${repository}@sha256:${sha256(manifest)}`, digest: `sha256:${sha256(manifest)}`, mediaType: "application/vnd.oci.image.manifest.v1+json", artifactType, extraMetadata: true });
  return { directory, bundle, manifest, bundlePath, manifestPath, descriptorPath, cleanup() { rmSync(directory, { recursive: true, force: true }); } };
}

function manifestFor(bundle) {
  return { schemaVersion: 2, mediaType: "application/vnd.oci.image.manifest.v1+json", artifactType, layers: [{ mediaType: layerMediaType, digest: `sha256:${sha256(bundle)}`, size: bundle.byteLength, annotations: { "org.opencontainers.image.title": "journey-v3-contract-bundle-v2.json" } }] };
}

function mutateJson(path, mutate) {
  const value = JSON.parse(readFileSync(path, "utf8"));
  mutate(value);
  writeJson(path, value);
}

function mutateManifest(fixture, mutate) {
  mutateJson(fixture.manifestPath, mutate);
  const digest = `sha256:${sha256(readFileSync(fixture.manifestPath))}`;
  mutateJson(fixture.descriptorPath, (value) => {
    value.digest = digest;
    value.reference = `${repository}@${digest}`;
  });
}

function run(fixture, output, options = {}) {
  const args = ["--bundle", options.bundle ?? fixture.bundlePath, "--descriptor", fixture.descriptorPath, "--manifest", options.manifest ?? fixture.manifestPath, "--repository", repository, "--git-sha", options.gitSha ?? gitSha, "--output", output, ...(options.extra ?? [])];
  try {
    execFileSync(process.execPath, [script, ...args], { encoding: "buffer", env: { ...process.env, ...options.env }, stdio: ["ignore", "pipe", "pipe"] });
    return readFileSync(output);
  } catch (error) {
    const failure = new Error(error.stderr?.toString() || error.message);
    failure.cause = error;
    throw failure;
  }
}

function writeJson(path, value) { writeFileSync(path, `${JSON.stringify(value)}\n`); }
function sha256(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
