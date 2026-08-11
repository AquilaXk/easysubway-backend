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
const resourceContent = "e30=";
const resourceSha256 = sha256(Buffer.from(resourceContent, "base64"));
const resources = [
  { id: "journey-v3-error-catalog", path: "contracts/api/journey-v3-error-catalog.json", owner: "AquilaXk/easysubway-backend", mediaType: "application/json", sha256: resourceSha256, contentBase64: resourceContent },
  { id: "journey-v3-error-disposition", path: "contracts/api/journey-v3-error-disposition.json", owner: "AquilaXk/easysubway-backend", mediaType: "application/json", sha256: resourceSha256, contentBase64: resourceContent },
  { id: "journey-v3-session-integrity", path: "contracts/api/journey-v3-session-integrity.json", owner: "AquilaXk/easysubway-backend", mediaType: "application/json", sha256: resourceSha256, contentBase64: resourceContent },
  { id: "journey-v3-openapi", path: "contracts/api/journey-v3.openapi.yaml", owner: "AquilaXk/easysubway-backend", mediaType: "application/yaml", sha256: resourceSha256, contentBase64: resourceContent },
];

test("release workflow는 exact Journey contract OCI publication과 digest 재검증을 고정한다", () => {
  const workflow = readFileSync(join(repositoryRoot, ".github/workflows/release-artifacts.yml"), "utf8");

  assert.match(workflow, /backend\/build\/journey-contract-preflight-a\/journey-v3-contract-bundle-v2\.json/);
  assert.match(workflow, /backend\/build\/journey-contract-preflight-b\/journey-v3-contract-bundle-v2\.json/);
  assert.match(workflow, /cmp --silent .*journey-contract-preflight-a.*journey-contract-preflight-b/s);
  assert.match(workflow, /oras-project\/setup-oras@1d808f7d7f6995cc68b7bf507bfe5c5446e1dc9d/);
  assert.match(workflow, /version:\s*1\.3\.3/);
  assert.match(workflow, /tag="git-\$\{GITHUB_SHA\}-run-\$\{GITHUB_RUN_ID\}-\$\{GITHUB_RUN_ATTEMPT\}"/);
  assert.match(workflow, /artifact_type="application\/vnd\.easysubway\.journey\.contract-bundle\.v2"/);
  assert.match(workflow, /layer_type="application\/vnd\.easysubway\.journey\.contract-bundle\.v2\+json"/);
  assert.match(workflow, /created="\$\(git show -s --format=%cI "\$\{GITHUB_SHA\}"\)"/);
  assert.match(workflow, /docker\/login-action@9780b0c442fbb1117ed29e0efdff1e18412f7567/);
  assert.match(workflow, /registry: ghcr\.io\s+username: \$\{\{ github\.actor \}\}\s+password: \$\{\{ secrets\.GITHUB_TOKEN \}\}/);
  assert.match(workflow, /oras login ghcr\.io .*--password-stdin/);
  assert.match(workflow, /raw_manifest=release-artifacts\/backend\/journey-v3-contract-bundle-v2-manifest\.json/);
  assert.match(workflow, /descriptor=release-artifacts\/backend\/journey-v3-contract-bundle-v2-descriptor\.json/);
  assert.match(workflow, /oras manifest fetch "\$\{repository\}:\$\{tag\}" --output "\$\{raw_manifest\}" --format json > "\$\{descriptor\}"/);
  assert.match(workflow, /build-contract-publication-receipt\.mjs/);
  assert.match(workflow, /JSON\.parse\(require\("node:fs"\)\.readFileSync\(process\.argv\[1\], "utf8"\)\)\.artifact\.manifestDigest/);
  assert.doesNotMatch(workflow, /sed .*manifestDigest/);
  assert.match(workflow, /\(\s+cd release-artifacts\/backend\s+oras push "\$\{repository\}:\$\{tag\}"\s+\\\s+--artifact-type "\$\{artifact_type\}"\s+\\\s+--annotation "org\.opencontainers\.image\.created=\$\{created\}"\s+\\\s+"journey-v3-contract-bundle-v2\.json:\$\{layer_type\}"/s);
  assert.match(workflow, /pull_root="\$\{RUNNER_TEMP\}\/journey-contract-pull"/);
  assert.match(workflow, /mkdir -p "\$\{pull_root\}"/);
  assert.match(workflow, /oras pull "\$\{repository\}@\$\{manifest_digest\}" --output "\$\{pull_root\}"/);
  assert.match(workflow, /cmp --silent "\$\{bundle\}" "\$\{pull_root\}\/journey-v3-contract-bundle-v2\.json"/);
  assert.match(workflow, /- name: Build immutable release evidence ledger\s+shell: bash\s+run: \|\s+set -euo pipefail\s+\(\s+cd release-artifacts\/backend\s+for evidence in release-metadata\.txt image-index\.json image-inspect\.json sbom\.json provenance\.json; do\s+sha256sum "\$\{evidence\}"\s+done\s+\) > release-artifacts\/backend\/evidence-ledger\.sha256\s+\(\s+cd release-artifacts\/backend\s+for evidence in journey-v3-contract-bundle-v2\.json journey-v3-contract-bundle-v2-descriptor\.json journey-v3-contract-bundle-v2-manifest\.json journey-v3-contract-bundle-v2-receipt\.json; do\s+sha256sum "\$\{evidence\}"\s+done\s+\) >> release-artifacts\/backend\/evidence-ledger\.sha256/s);
  assert.doesNotMatch(workflow, /tag="[^"]*latest[^"]*"/);
  assert.doesNotMatch(workflow, /oras push "[^"]*latest[^"]*"/);
  assert.doesNotMatch(workflow, /oras manifest fetch "[^"]*latest[^"]*"/);
  assert.doesNotMatch(workflow, /oras pull "[^"]*latest[^"]*"/);
  assert.doesNotMatch(workflow, /oras manifest fetch .*\|\|/);
  assert.doesNotMatch(workflow, /tag.*exist/i);
});

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
    ["bundle resource count", (fixture) => mutateBundle(fixture, (value) => { value.resources = []; })],
    ["bundle resource identity", (fixture) => mutateBundle(fixture, (value) => { value.resources[0].id = "other"; })],
    ["bundle resource path", (fixture) => mutateBundle(fixture, (value) => { value.resources[0].path = "other.json"; })],
    ["bundle resource owner", (fixture) => mutateBundle(fixture, (value) => { value.resources[0].owner = "other/repository"; })],
    ["bundle resource media type", (fixture) => mutateBundle(fixture, (value) => { value.resources[0].mediaType = "text/plain"; })],
    ["bundle resource digest mismatch", (fixture) => mutateBundle(fixture, (value) => { value.resources[0].sha256 = "0".repeat(64); })],
    ["descriptor digest", (fixture) => mutateJson(fixture.descriptorPath, (value) => { value.digest = `sha256:${"0".repeat(64)}`; })],
    ["descriptor reference", (fixture) => mutateJson(fixture.descriptorPath, (value) => { value.reference = `${repository}@sha256:${"0".repeat(64)}`; })],
    ["descriptor raw manifest SHA binding", (fixture) => mutateJson(fixture.descriptorPath, (value) => {
      const wrongDigest = `sha256:${"0".repeat(64)}`;
      value.digest = wrongDigest;
      value.reference = `${repository}@${wrongDigest}`;
    })],
    ["descriptor media type", (fixture) => mutateJson(fixture.descriptorPath, (value) => { value.mediaType = "application/json"; })],
    ["descriptor missing size", (fixture) => mutateJson(fixture.descriptorPath, (value) => { delete value.size; })],
    ["descriptor wrong size", (fixture) => mutateJson(fixture.descriptorPath, (value) => { value.size += 1; })],
    ["raw artifact type", (fixture) => mutateManifest(fixture, (value) => { value.artifactType = "application/json"; })],
    ["raw config missing", (fixture) => mutateManifest(fixture, (value) => { delete value.config; })],
    ["raw config media type", (fixture) => mutateManifest(fixture, (value) => { value.config.mediaType = "application/json"; })],
    ["raw config digest", (fixture) => mutateManifest(fixture, (value) => { value.config.digest = `sha256:${"0".repeat(64)}`; })],
    ["raw config size", (fixture) => mutateManifest(fixture, (value) => { value.config.size = 3; })],
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
  const bundle = Buffer.from(`${JSON.stringify({ schemaVersion: 2, bundleVersion: "2.0.0", component: "backend", producerRepository: "AquilaXk/easysubway-backend", producerSha: gitSha, resources })}\n`);
  const bundlePath = join(directory, "journey-v3-contract-bundle-v2.json");
  const manifestPath = join(directory, "manifest.json");
  const descriptorPath = join(directory, "descriptor.json");
  writeFileSync(bundlePath, bundle);
  const manifest = Buffer.from(`${JSON.stringify(manifestFor(bundle))}\n`);
  writeFileSync(manifestPath, manifest);
  writeJson(descriptorPath, { reference: `${repository}@sha256:${sha256(manifest)}`, digest: `sha256:${sha256(manifest)}`, size: manifest.byteLength, mediaType: "application/vnd.oci.image.manifest.v1+json", extraMetadata: true });
  return { directory, bundle, manifest, bundlePath, manifestPath, descriptorPath, cleanup() { rmSync(directory, { recursive: true, force: true }); } };
}

function manifestFor(bundle) {
  return { schemaVersion: 2, mediaType: "application/vnd.oci.image.manifest.v1+json", artifactType, config: { mediaType: "application/vnd.oci.empty.v1+json", digest: "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", size: 2, data: "{}" }, layers: [{ mediaType: layerMediaType, digest: `sha256:${sha256(bundle)}`, size: bundle.byteLength, annotations: { "org.opencontainers.image.title": "journey-v3-contract-bundle-v2.json" } }] };
}

function mutateJson(path, mutate) {
  const value = JSON.parse(readFileSync(path, "utf8"));
  mutate(value);
  writeJson(path, value);
}

function mutateManifest(fixture, mutate) {
  mutateJson(fixture.manifestPath, mutate);
  const manifest = readFileSync(fixture.manifestPath);
  const digest = `sha256:${sha256(manifest)}`;
  mutateJson(fixture.descriptorPath, (value) => {
    value.digest = digest;
    value.reference = `${repository}@${digest}`;
    value.size = manifest.byteLength;
  });
}

function mutateBundle(fixture, mutate) {
  mutateJson(fixture.bundlePath, mutate);
  const bundle = readFileSync(fixture.bundlePath);
  mutateManifest(fixture, (value) => {
    value.layers[0].digest = `sha256:${sha256(bundle)}`;
    value.layers[0].size = bundle.byteLength;
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
