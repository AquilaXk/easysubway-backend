import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { copyFileSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, renameSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { buildJourneyContractLock } from "./build-journey-contract-lock.mjs";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const builder = join(repositoryRoot, "backend/tools/build-journey-contract-lock.mjs");
const trackedLock = join(repositoryRoot, "backend/journey-contracts.lock.json");
const producerSha = "1c25e586270f0e40b5fcad32820ff9e9e3ff985f";
const manifestDigest = "6d3b428a6e069739b98d040f6d10c5e20af10725d8656aeaaad190d5bf9fa3b1";
const payloadDigest = "1bdffede5aa577411d77a6c8ec4f18de8ea25c61b54f227e985386b81b65625f";
const receiptDigest = "dcb93a99c86f9a7790e33ceebc8c9392bb65178db1c0d2b6c0eeea5b8e75a6cd";
const names = ["backend-component-manifest.json", "evidence-ledger.sha256", "image-index.json", "image-inspect.json", "journey-v3-contract-bundle-v2-descriptor.json", "journey-v3-contract-bundle-v2-manifest.json", "journey-v3-contract-bundle-v2-receipt.json", "journey-v3-contract-bundle-v2.json", "provenance.json", "release-metadata.txt", "sbom.json"];

test("self-consistent fabricated artifact는 exact release trust anchor와 다르면 output 없이 거부한다", () => {
  const directory = temporaryDirectory("test");
  try {
    const artifact = createArtifact(join(directory, "artifact"));
    const output = join(directory, "journey-contracts.lock.json");
    fail(artifact, output);
    assert.equal(exists(output), false);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("test seam은 synthetic admitted anchors 아래 exact tracked lock을 생성한다", () => {
  const directory = temporaryDirectory("positive");
  try {
    const artifact = createArtifact(join(directory, "artifact"));
    const output = join(directory, "journey-contracts.lock.json");
    buildJourneyContractLock(["--artifact-directory", artifact, "--output", output], { trustAnchors: anchorsFor(artifact) });
    assert.deepEqual(JSON.parse(readFileSync(output, "utf8")), JSON.parse(readFileSync(trackedLock, "utf8")));
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("test seam은 temp 생성 뒤 output ancestor 교체를 거부하고 redirect하지 않는다", () => {
  const directory = temporaryDirectory("ancestor-swap");
  try {
    const artifact = createArtifact(join(directory, "artifact"));
    const parent = join(directory, "output-parent");
    mkdirSync(parent);
    const output = join(parent, "journey-contracts.lock.json");
    assert.throws(() => buildJourneyContractLock(["--artifact-directory", artifact, "--output", output], { trustAnchors: anchorsFor(artifact), beforeRename: () => { renameSync(parent, `${parent}-old`); mkdirSync(parent); } }), /ancestor changed/);
    assert.equal(exists(output), false);
    assert.deepEqual(readdirSync(parent), []);
    const quarantine = readdirSync(`${parent}-old`);
    assert.equal(quarantine.length, 1);
    assert.match(quarantine[0], /^journey-contracts\.lock\.json\.tmp-[0-9a-f-]{36}$/);
    assert.equal(statSync(join(`${parent}-old`, quarantine[0])).mode & 0o777, 0o600);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("test seam은 temp open 전 output ancestor 교체를 temp 없이 거부한다", () => {
  const directory = temporaryDirectory("pre-temp-swap");
  try {
    const artifact = createArtifact(join(directory, "artifact"));
    const parent = join(directory, "output-parent");
    mkdirSync(parent);
    const output = join(parent, "journey-contracts.lock.json");
    assert.throws(() => buildJourneyContractLock(["--artifact-directory", artifact, "--output", output], { trustAnchors: anchorsFor(artifact), beforeTempOpen: () => { renameSync(parent, `${parent}-old`); mkdirSync(parent); } }), /ancestor changed/);
    assert.equal(exists(output), false);
    assert.deepEqual(readdirSync(parent), []);
    assert.deepEqual(readdirSync(`${parent}-old`), []);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("validation 전에 output parent가 바뀌면 write 없이 거부한다", () => {
  const directory = temporaryDirectory("validation-swap");
  try {
    const artifact = createArtifact(join(directory, "artifact")); const parent = join(directory, "parent"); mkdirSync(parent); const output = join(parent, "lock.json");
    assert.throws(() => buildJourneyContractLock(["--artifact-directory", artifact, "--output", output], { trustAnchors: anchorsFor(artifact), beforeValidation: () => { renameSync(parent, `${parent}-old`); mkdirSync(parent); } }), /ancestor changed/);
    assert.equal(exists(output), false); assert.deepEqual(readdirSync(parent), []); assert.deepEqual(readdirSync(`${parent}-old`), []);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("closed temp pathname regular/symlink substitution은 final output과 external target을 만들지 않는다", () => {
  const directory = temporaryDirectory("temp-substitute");
  try {
    for (const symlink of [false, true]) {
      const artifact = createArtifact(join(directory, `artifact-${symlink}`));
      const parent = join(directory, `output-${symlink}`); mkdirSync(parent);
      const output = join(parent, "journey-contracts.lock.json"); const external = join(directory, `external-${symlink}`); let substitution; writeFileSync(external, "unchanged\n");
      assert.throws(() => buildJourneyContractLock(["--artifact-directory", artifact, "--output", output], { trustAnchors: anchorsFor(artifact), beforeRename: (temporary) => { substitution = temporary; rmSync(temporary); if (symlink) symlinkSync(external, temporary); else writeFileSync(temporary, "replacement\n"); } }), /temporary output identity/);
      assert.equal(exists(output), false); assert.equal(readFileSync(external, "utf8"), "unchanged\n"); assert.equal(symlink ? lstatSync(substitution).isSymbolicLink() : readFileSync(substitution, "utf8") === "replacement\n", true);
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("open temp pathname regular/symlink substitution은 descriptor identity로 거부한다", () => {
  const directory = temporaryDirectory("open-temp-substitute");
  try {
    for (const symlink of [false, true]) {
      const artifact = createArtifact(join(directory, `artifact-${symlink}`)); const parent = join(directory, `output-${symlink}`); mkdirSync(parent);
      const output = join(parent, "journey-contracts.lock.json"); const external = join(directory, `external-${symlink}`); let substitution; writeFileSync(external, "unchanged\n");
      assert.throws(() => buildJourneyContractLock(["--artifact-directory", artifact, "--output", output], { trustAnchors: anchorsFor(artifact), beforePathIdentity: (temporary) => { substitution = temporary; rmSync(temporary); if (symlink) symlinkSync(external, temporary); else writeFileSync(temporary, "replacement\n"); } }), /temporary output identity/);
      assert.equal(exists(output), false); assert.equal(readFileSync(external, "utf8"), "unchanged\n"); assert.equal(symlink ? lstatSync(substitution).isSymbolicLink() : readFileSync(substitution, "utf8") === "replacement\n", true);
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("create-new publish는 existing output bytes를 덮어쓰지 않는다", () => {
  const directory = temporaryDirectory("existing-output");
  try {
    const artifact = createArtifact(join(directory, "artifact")); const output = join(directory, "output.json"); writeFileSync(output, "existing\n");
    assert.throws(() => buildJourneyContractLock(["--artifact-directory", artifact, "--output", output], { trustAnchors: anchorsFor(artifact) }));
    assert.equal(readFileSync(output, "utf8"), "existing\n");
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("temp collision은 foreign bytes를 삭제하지 않는다", () => {
  const directory = temporaryDirectory("temp-collision");
  try {
    const artifact = createArtifact(join(directory, "artifact")); const output = join(directory, "output.json"); let collision;
    assert.throws(() => buildJourneyContractLock(["--artifact-directory", artifact, "--output", output], { trustAnchors: anchorsFor(artifact), beforeTempOpen: (temporary) => { collision = temporary; writeFileSync(temporary, "foreign\n"); } }));
    assert.equal(readFileSync(collision, "utf8"), "foreign\n"); assert.equal(exists(output), false);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("# ? %를 포함한 copied CLI path도 entrypoint를 실행한다", () => {
  const directory = temporaryDirectory("cli-special");
  try {
    const special = join(directory, "#?%"); mkdirSync(special); const copied = join(special, "build-journey-contract-lock.mjs"); copyFileSync(builder, copied);
    const result = spawnSync(process.execPath, [copied, "--artifact-directory", join(directory, "missing"), "--output", join(directory, "output.json")], { encoding: "utf8" });
    assert.equal(result.status, 1); assert.match(result.stderr, /^build-journey-contract-lock:/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("missing/extra/duplicate/reordered/unsafe inventory와 identity drift는 output mutation 없이 거부한다", () => {
  const directory = temporaryDirectory("mutate");
  try {
    const output = join(directory, "journey-contracts.lock.json");
    const mutations = [
      (path) => rmSync(join(path, "sbom.json")),
      (path) => writeFileSync(join(path, "unexpected.json"), "{}\n"),
      (path) => writeFileSync(join(path, "evidence-ledger.sha256"), `${readFileSync(join(path, "evidence-ledger.sha256"), "utf8").trimEnd()}\n${"0".repeat(64)}  sbom.json\n`),
      (path) => replaceJson(path, "journey-v3-contract-bundle-v2.json", (value) => { [value.resources[0], value.resources[1]] = [value.resources[1], value.resources[0]]; }),
      (path) => writeFileSync(join(path, "evidence-ledger.sha256"), `${"0".repeat(64)}  ../unsafe\n`),
      (path) => replaceJson(path, "backend-component-manifest.json", (value) => { value.component = "wrong"; }),
      (path) => replaceJson(path, "journey-v3-contract-bundle-v2-receipt.json", (value) => { value.producer.repository = "AquilaXk/wrong"; }),
      (path) => replaceJson(path, "journey-v3-contract-bundle-v2-receipt.json", (value) => { value.producer.gitSha = "0".repeat(40); }),
      (path) => replaceJson(path, "journey-v3-contract-bundle-v2-receipt.json", (value) => { value.component = "wrong"; }),
      (path) => replaceJson(path, "journey-v3-contract-bundle-v2-receipt.json", (value) => { value.bundleVersion = "3.0.0"; }),
      (path) => replaceJson(path, "journey-v3-contract-bundle-v2-receipt.json", (value) => { value.artifact.repository = "ghcr.io/wrong"; }),
      (path) => replaceJson(path, "journey-v3-contract-bundle-v2-receipt.json", (value) => { value.payload.sha256 = "0".repeat(64); }),
      (path) => replaceJson(path, "journey-v3-contract-bundle-v2.json", (value) => { value.resources[2].sha256 = "0".repeat(64); }),
    ];
    for (const [index, mutate] of mutations.entries()) {
      const artifact = createArtifact(join(directory, `artifact-${index}`));
      mutate(artifact);
      refreshLedgerIfPossible(artifact);
      fail(artifact, output);
      assert.equal(exists(output), false);
    }
    const artifact = createArtifact(join(directory, "tampered"));
    writeFileSync(join(artifact, "journey-v3-contract-bundle-v2.json"), "{}\n");
    fail(artifact, output);
    writeFileSync(output, "unchanged\n");
    fail(artifact, output);
    assert.equal(readFileSync(output, "utf8"), "unchanged\n");
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("symlink/non-regular artifact and output overlap/symlink은 redirect 없이 거부한다", () => {
  const directory = temporaryDirectory("safety");
  try {
    const artifact = createArtifact(join(directory, "artifact"));
    const bundle = join(artifact, "journey-v3-contract-bundle-v2.json");
    rmSync(bundle);
    symlinkSync(join(repositoryRoot, "contracts/api/journey-v3-contract-digests.json"), bundle);
    fail(artifact, join(directory, "output.json"));
    const cleanArtifact = createArtifact(join(directory, "clean"));
    fail(cleanArtifact, join(cleanArtifact, "output.json"));
    const alias = join(directory, "artifact-alias");
    symlinkSync(cleanArtifact, alias);
    fail(alias, join(directory, "alias-output.json"));
    const target = join(directory, "target.json");
    writeFileSync(target, "unchanged\n");
    const output = join(directory, "output.json");
    symlinkSync(target, output);
    fail(cleanArtifact, output);
    assert.equal(readFileSync(target, "utf8"), "unchanged\n");
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

function createArtifact(directory) {
  mkdirSync(directory, { recursive: true });
  const resources = [
    ["journey-v3-error-catalog", "contracts/api/journey-v3-error-catalog.json", "application/json", "journey-v3-error-catalog.json"],
    ["journey-v3-error-disposition", "contracts/api/journey-v3-error-disposition.json", "application/json", "journey-v3-error-disposition.json"],
    ["journey-v3-session-integrity", "contracts/api/journey-v3-session-integrity.json", "application/json", "journey-v3-session-integrity.json"],
    ["journey-v3-openapi", "contracts/api/journey-v3.openapi.yaml", "application/yaml", "journey-v3.openapi.yaml"],
  ].map(([id, path, mediaType, source]) => {
    const bytes = readFileSync(join(repositoryRoot, "contracts/api", source));
    return { id, path, owner: "AquilaXk/easysubway-backend", mediaType, sha256: sha256(bytes), contentBase64: bytes.toString("base64") };
  });
  const bundle = Buffer.from(`${JSON.stringify({ schemaVersion: 2, bundleVersion: "2.0.0", component: "backend", producerRepository: "AquilaXk/easysubway-backend", producerSha, resources })}\n`);
  assert.equal(sha256(bundle), payloadDigest);
  const manifest = Buffer.from(JSON.stringify({ schemaVersion: 2, mediaType: "application/vnd.oci.image.manifest.v1+json", artifactType: "application/vnd.easysubway.journey.contract-bundle.v2", config: { mediaType: "application/vnd.oci.empty.v1+json", digest: "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", size: 2, data: "e30=" }, layers: [{ mediaType: "application/vnd.easysubway.journey.contract-bundle.v2+json", digest: `sha256:${payloadDigest}`, size: bundle.byteLength, annotations: { "org.opencontainers.image.title": "journey-v3-contract-bundle-v2.json" } }], annotations: { "org.opencontainers.image.created": "2026-08-11T11:27:04Z" } }));
  assert.equal(sha256(manifest), manifestDigest);
  const receipt = Buffer.from(`${JSON.stringify({ schemaVersion: 1, component: "backend", bundleVersion: "2.0.0", producer: { repository: "AquilaXk/easysubway-backend", gitSha: producerSha }, artifact: { repository: "ghcr.io/aquilaxk/easysubway-backend-contracts", manifestDigest: `sha256:${manifestDigest}`, artifactType: "application/vnd.easysubway.journey.contract-bundle.v2" }, payload: { fileName: "journey-v3-contract-bundle-v2.json", mediaType: "application/vnd.easysubway.journey.contract-bundle.v2+json", sha256: payloadDigest } })}\n`);
  assert.equal(sha256(receipt), receiptDigest);
  const descriptor = Buffer.from(JSON.stringify({ reference: `ghcr.io/aquilaxk/easysubway-backend-contracts@sha256:${manifestDigest}`, mediaType: "application/vnd.oci.image.manifest.v1+json", digest: `sha256:${manifestDigest}`, size: manifest.byteLength, content: JSON.parse(manifest) }));
  const files = new Map([["image-index.json", "{}\n"], ["image-inspect.json", "{}\n"], ["provenance.json", "{}\n"], ["release-metadata.txt", "release\n"], ["sbom.json", "{}\n"], ["journey-v3-contract-bundle-v2.json", bundle], ["journey-v3-contract-bundle-v2-descriptor.json", descriptor], ["journey-v3-contract-bundle-v2-manifest.json", manifest], ["journey-v3-contract-bundle-v2-receipt.json", receipt]]);
  const ledger = `${[...files].map(([name, bytes]) => `${sha256(bytes)}  ${name}`).join("\n")}\n`;
  files.set("evidence-ledger.sha256", ledger);
  files.set("backend-component-manifest.json", JSON.stringify({ schemaVersion: 1, component: "backend", repository: "AquilaXk/easysubway-backend", gitSha: producerSha, artifactIdentity: { imageDigest: "sha256:27e4c76a1e6e00c27c80368348f5fe0b53a5fc9c451d8c19d4f113883cafa284", apiContractVersion: "1.0.0" }, contractVersion: "1.0.0", evidenceSha256: sha256(Buffer.from(ledger)), issueRefs: ["AquilaXk/easysubway-backend#17"] }));
  for (const name of names) writeFileSync(join(directory, name), files.get(name));
  return directory;
}

function replaceJson(artifact, name, mutate) { const path = join(artifact, name); const value = JSON.parse(readFileSync(path, "utf8")); mutate(value); writeFileSync(path, JSON.stringify(value)); }
function refreshLedgerIfPossible(artifact) {
  const ledger = join(artifact, "evidence-ledger.sha256");
  const rows = readFileSync(ledger, "utf8").trimEnd().split("\n");
  if (rows.length !== 9 || rows.some((row) => !/^[a-f0-9]{64}  [A-Za-z0-9._-]+$/.test(row) || !exists(join(artifact, row.slice(66))))) return;
  writeFileSync(ledger, `${rows.map((row) => { const name = row.slice(66); return `${sha256(readFileSync(join(artifact, name)))}  ${name}`; }).join("\n")}\n`);
  const component = join(artifact, "backend-component-manifest.json");
  const value = JSON.parse(readFileSync(component, "utf8"));
  value.evidenceSha256 = sha256(readFileSync(ledger));
  writeFileSync(component, JSON.stringify(value));
}
function run(artifact, output) { const result = spawnSync(process.execPath, [builder, "--artifact-directory", artifact, "--output", output], { encoding: "utf8" }); assert.equal(result.status, 0, result.stderr); }
function fail(artifact, output) { const result = spawnSync(process.execPath, [builder, "--artifact-directory", artifact, "--output", output], { encoding: "utf8" }); assert.notEqual(result.status, 0, result.stderr); }
function exists(path) { try { readFileSync(path); return true; } catch { return false; } }
function sha256(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
function anchorsFor(artifact) { return Object.fromEntries(["evidence-ledger.sha256", "backend-component-manifest.json", "journey-v3-contract-bundle-v2-descriptor.json", "journey-v3-contract-bundle-v2-manifest.json", "journey-v3-contract-bundle-v2-receipt.json", "journey-v3-contract-bundle-v2.json"].map((name) => [name, sha256(readFileSync(join(artifact, name)))])); }
function temporaryDirectory(name) { return mkdtempSync(join(repositoryRoot, `backend/build/.journey-contract-lock-${name}-`)); }
