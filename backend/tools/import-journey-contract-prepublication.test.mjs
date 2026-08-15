import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { deflateRawSync } from "node:zlib";
import { mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import test from "node:test";
import { buildJourneyContractLock } from "./build-journey-contract-lock.mjs";
import { importJourneyContractPrepublication } from "./import-journey-contract-prepublication.mjs";

const root = resolve(import.meta.dirname, "../..");
const script = join(root, "backend/tools/import-journey-contract-prepublication.mjs");
const receiptScript = join(root, "backend/tools/build-journey-contract-prepublication-receipt.mjs");
const metadataScript = join(root, "backend/tools/build-journey-contract-prepublication-run-metadata.mjs");
const repository = "AquilaXk/easysubway-backend"; const sourceSha = "a".repeat(40); const workflowSha = "b".repeat(40); const runId = "123"; const attempt = "1"; const pr = 252;

test("importer는 closed artifact와 caller identity가 모두 일치할 때만 lock/trust를 만든다", () => {
  mkdirSync(join(root, "backend/build"), { recursive: true }); const directory = mkdtempSync(join(root, "backend/build/journey-prepublish-import-test-"));
  try {
    const artifact = buildArtifact(directory); const caller = join(directory, "caller.json"); const lock = join(directory, "lock.json"); const trust = join(directory, "trust.json");
    writeFileSync(caller, JSON.stringify({ schemaVersion: 1, artifactKind: "journey-contract-prepublication-caller", repository, pullRequestNumber: pr, sourceSha, workflowRunId: runId, workflowRunAttempt: attempt, artifactName: artifact.name, archiveSha256: sha(readFileSync(artifact.zip)) }));
    run(artifact.zip, caller, lock, trust);
    assert.equal(JSON.parse(readFileSync(lock)).producer.gitSha, sourceSha);
    assert.equal(JSON.parse(readFileSync(trust)).artifactTrustAnchors["journey-contract-prepublication-run-metadata.json"], sha(readFileSync(join(artifact.directory, "journey-contract-prepublication-run-metadata.json"))));
    const rebuilt = join(directory, "rebuilt-lock.json");
    buildJourneyContractLock(["--artifact-directory", artifact.directory, "--output", rebuilt], { trustAnchor: trust });
    assert.equal(JSON.parse(readFileSync(rebuilt)).payload.sha256, JSON.parse(readFileSync(lock)).payload.sha256);
    const wrong = join(directory, "wrong.json"); writeFileSync(wrong, JSON.stringify({ ...JSON.parse(readFileSync(caller)), sourceSha: "d".repeat(40) }));
    assert.throws(() => run(artifact.zip, wrong, join(directory, "rejected-lock.json"), join(directory, "rejected-trust.json")), /match|invalid/i);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("prepublication receipt와 run metadata는 v1 self-hash canonical bytes를 만든다", () => {
  mkdirSync(join(root, "backend/build"), { recursive: true }); const directory = mkdtempSync(join(root, "backend/build/journey-prepublish-receipt-test-"));
  try {
    const artifact = buildArtifact(directory); const receipt = join(directory, "built-receipt.json"); const metadata = join(directory, "built-metadata.json"); const manifest = readFileSync(join(artifact.directory, "journey-v3-contract-bundle-v2-manifest.json"));
    execFileSync(process.execPath, [receiptScript, "--bundle", join(artifact.directory, "journey-v3-contract-bundle-v2.json"), "--manifest", join(artifact.directory, "journey-v3-contract-bundle-v2-manifest.json"), "--descriptor", join(artifact.directory, "journey-v3-contract-bundle-v2-descriptor.json"), "--source-sha", sourceSha, "--workflow-sha", workflowSha, "--run-id", runId, "--attempt", attempt, "--pr", String(pr), "--output", receipt], { stdio: "pipe" });
    execFileSync(process.execPath, [metadataScript, "--pr", String(pr), "--source-sha", sourceSha, "--workflow-sha", workflowSha, "--run-id", runId, "--attempt", attempt, "--bundle-sha", sha(readFileSync(join(artifact.directory, "journey-v3-contract-bundle-v2.json"))), "--manifest-digest", `sha256:${sha(manifest)}`, "--receipt", receipt, "--output", metadata], { stdio: "pipe" });
    const value = JSON.parse(readFileSync(metadata)); const { runMetadataSha256, ...payload } = value;
    assert.equal(runMetadataSha256, sha(Buffer.from(JSON.stringify(payload))));
    assert.deepEqual(readFileSync(metadata), Buffer.from(`${JSON.stringify(value, null, 2)}\n`));
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("importer는 bit-3 descriptor ZIP만 central identity와 descriptor가 일치할 때 받는다", () => {
  mkdirSync(join(root, "backend/build"), { recursive: true }); const directory = mkdtempSync(join(root, "backend/build/journey-prepublish-bit3-test-"));
  try {
    const artifact = buildArtifact(directory, { bit3: true }); const caller = callerMetadata(directory, artifact); const lock = join(directory, "lock.json"), trust = join(directory, "trust.json");
    run(artifact.zip, caller, lock, trust);
    const malformed = Buffer.from(readFileSync(artifact.zip)); const marker = malformed.indexOf(Buffer.from([0x50, 0x4b, 0x07, 0x08])); assert.notEqual(marker, -1); malformed[marker + 4] ^= 1;
    const broken = join(directory, "broken.zip"); writeFileSync(broken, malformed); const rehashed = callerMetadata(directory, { ...artifact, zip: broken });
    assert.throws(() => run(broken, rehashed, join(directory, "bad-lock.json"), join(directory, "bad-trust.json")), /descriptor|checksum|invalid/i);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("importer는 caller-bound rehashed ZIP도 bounded deflate expansion과 local drift를 거부한다", () => {
  mkdirSync(join(root, "backend/build"), { recursive: true }); const directory = mkdtempSync(join(root, "backend/build/journey-prepublish-zip-bound-test-"));
  try {
    const artifact = buildArtifact(directory); const expansion = join(directory, "expansion.zip");
    const entries = artifact.files; entries.set("evidence-ledger.sha256", Buffer.alloc(4 * 1024 * 1024 + 1, 0x61)); writeFileSync(expansion, zipStored(entries, { deflate: "evidence-ledger.sha256", uncompressedOverride: 4 * 1024 * 1024 }));
    assert.throws(() => run(expansion, callerMetadata(directory, { ...artifact, zip: expansion }), join(directory, "expanded-lock.json"), join(directory, "expanded-trust.json")), /payload|invalid/i);
    const localDrift = Buffer.from(readFileSync(artifact.zip)); localDrift.writeUInt32LE(0, 14); const drift = join(directory, "local-drift.zip"); writeFileSync(drift, localDrift);
    assert.throws(() => run(drift, callerMetadata(directory, { ...artifact, zip: drift }), join(directory, "drift-lock.json"), join(directory, "drift-trust.json")), /local entry/i);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("importer pair publish는 second rename failure와 foreign first replacement에서 prestate를 보존한다", () => {
  mkdirSync(join(root, "backend/build"), { recursive: true }); const directory = mkdtempSync(join(root, "backend/build/journey-prepublish-pair-test-"));
  try {
    const artifact = buildArtifact(directory), caller = callerMetadata(directory, artifact), lock = join(directory, "lock.json"), trust = join(directory, "trust.json");
    writeFileSync(lock, "old-lock\n", { mode: 0o600 }); writeFileSync(trust, "old-trust\n", { mode: 0o600 });
    assert.throws(() => importJourneyContractPrepublication(argumentsFor(artifact.zip, caller, lock, trust), { renameSecond: () => { throw new Error("second rename failed"); } }), /second rename failed/);
    assert.equal(readFileSync(lock, "utf8"), "old-lock\n"); assert.equal(readFileSync(trust, "utf8"), "old-trust\n");
    const foreign = join(directory, "foreign.json");
    assert.throws(() => importJourneyContractPrepublication(argumentsFor(artifact.zip, caller, lock, trust), { beforeSecondRename: () => { writeFileSync(foreign, "foreign-lock\n", { mode: 0o600 }); renameSync(foreign, lock); } }), /ownership changed/);
    assert.equal(readFileSync(lock, "utf8"), "foreign-lock\n"); assert.equal(readFileSync(trust, "utf8"), "old-trust\n");
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

function buildArtifact(parent, zipOptions = {}) {
  const directory = join(parent, "artifact"); mkdirSync(directory); const name = `journey-contract-prepublication-pr-${pr}-${sourceSha}-run-${runId}-attempt-${attempt}`;
  const resources = [["journey-v3-error-catalog", "journey-v3-error-catalog.json"], ["journey-v3-error-disposition", "journey-v3-error-disposition.json"], ["journey-v3-session-integrity", "journey-v3-session-integrity.json"], ["journey-v3-openapi", "journey-v3.openapi.yaml"]].map(([id, file], index) => { const bytes = Buffer.from(`resource-${index}`); return { id, path: `contracts/api/${file}`, owner: repository, mediaType: file.endsWith("yaml") ? "application/yaml" : "application/json", sha256: sha(bytes), contentBase64: bytes.toString("base64") }; });
  const bundle = Buffer.from(JSON.stringify({ schemaVersion: 2, bundleVersion: "2.0.0", component: "backend", producerRepository: repository, producerSha: sourceSha, resources })); const bundleSha = sha(bundle);
  const manifest = Buffer.from(JSON.stringify({ schemaVersion: 2, mediaType: "application/vnd.oci.image.manifest.v1+json", artifactType: "application/vnd.easysubway.journey.contract-bundle.v2", config: { mediaType: "application/vnd.oci.empty.v1+json", digest: "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", size: 2, data: "e30=" }, layers: [{ mediaType: "application/vnd.easysubway.journey.contract-bundle.v2+json", digest: `sha256:${bundleSha}`, size: bundle.length, annotations: { "org.opencontainers.image.title": "journey-v3-contract-bundle-v2.json" } }] })); const manifestDigest = `sha256:${sha(manifest)}`;
  const descriptor = Buffer.from(JSON.stringify({ reference: `ghcr.io/aquilaxk/easysubway-backend-contracts@${manifestDigest}`, mediaType: "application/vnd.oci.image.manifest.v1+json", digest: manifestDigest, size: manifest.length }));
  const publication = { pullRequestNumber: pr, baseRef: "main", sourceRepository: repository, workflowRepository: repository, workflowSha, workflowRunId: runId, workflowRunAttempt: attempt, artifactName: name };
  const receipt = Buffer.from(JSON.stringify({ schemaVersion: 2, component: "backend", bundleVersion: "2.0.0", producer: { repository, gitSha: sourceSha }, artifact: { repository: "ghcr.io/aquilaxk/easysubway-backend-contracts", manifestDigest, artifactType: "application/vnd.easysubway.journey.contract-bundle.v2" }, payload: { fileName: "journey-v3-contract-bundle-v2.json", mediaType: "application/vnd.easysubway.journey.contract-bundle.v2+json", sha256: bundleSha }, publication }));
  const payload = { schemaVersion: 1, artifactKind: "journey-contract-prepublication-run", repository, pullRequestNumber: pr, sourceSha, baseRef: "main", sourceRepository: repository, workflowRepository: repository, workflowSha, workflowRunId: runId, workflowRunAttempt: attempt, artifactName: name, bundleSha256: bundleSha, manifestDigest, receiptSha256: sha(receipt) }; const metadata = Buffer.from(`${JSON.stringify({ ...payload, runMetadataSha256: sha(Buffer.from(JSON.stringify(payload))) }, null, 2)}\n`);
  const files = new Map([["journey-v3-contract-bundle-v2.json", bundle], ["journey-v3-contract-bundle-v2-manifest.json", manifest], ["journey-v3-contract-bundle-v2-descriptor.json", descriptor], ["journey-v3-contract-bundle-v2-receipt.json", receipt], ["journey-contract-prepublication-run-metadata.json", metadata]]); for (const [file, bytes] of files) writeFileSync(join(directory, file), bytes); const ledger = Buffer.from(`${[...files].sort(([a], [b]) => a.localeCompare(b)).map(([file, bytes]) => `${sha(bytes)}  ${file}`).join("\n")}\n`); files.set("evidence-ledger.sha256", ledger); writeFileSync(join(directory, "evidence-ledger.sha256"), ledger); const zip = join(parent, "artifact.zip"); writeFileSync(zip, zipStored(files, zipOptions)); return { directory, zip, name, files };
}
function callerMetadata(directory, artifact) { const caller = join(directory, `caller-${Math.random().toString(16).slice(2)}.json`); writeFileSync(caller, JSON.stringify({ schemaVersion: 1, artifactKind: "journey-contract-prepublication-caller", repository, pullRequestNumber: pr, sourceSha, workflowRunId: runId, workflowRunAttempt: attempt, artifactName: artifact.name, archiveSha256: sha(readFileSync(artifact.zip)) })); return caller; }
function argumentsFor(artifact, caller, lock, trust) { return ["--artifact-zip", artifact, "--caller-metadata", caller, "--lock-output", lock, "--trust-output", trust]; }
function run(artifact, caller, lock, trust) { execFileSync(process.execPath, [script, ...argumentsFor(artifact, caller, lock, trust)], { stdio: "pipe" }); }
function sha(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
function crc32(bytes) { let crc = 0xffffffff; for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); } return (crc ^ 0xffffffff) >>> 0; }
function zipStored(files, { bit3 = false, deflate, uncompressedOverride } = {}) { const locals = []; const centrals = []; let offset = 0; for (const [name, data] of [...files].sort(([a], [b]) => a.localeCompare(b))) { const encoded = Buffer.from(name), compressedData = deflate === name ? deflateRawSync(data) : data, method = deflate === name ? 8 : 0, uncompressed = deflate === name && uncompressedOverride !== undefined ? uncompressedOverride : data.length, flags = bit3 ? 8 : 0, descriptor = bit3 ? Buffer.alloc(16) : Buffer.alloc(0), local = Buffer.alloc(30 + encoded.length + compressedData.length); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(flags, 6); local.writeUInt16LE(method, 8); if (!bit3) { local.writeUInt32LE(crc32(data), 14); local.writeUInt32LE(compressedData.length, 18); local.writeUInt32LE(uncompressed, 22); } local.writeUInt16LE(encoded.length, 26); encoded.copy(local, 30); compressedData.copy(local, 30 + encoded.length); if (bit3) { descriptor.writeUInt32LE(0x08074b50, 0); descriptor.writeUInt32LE(crc32(data), 4); descriptor.writeUInt32LE(compressedData.length, 8); descriptor.writeUInt32LE(uncompressed, 12); } locals.push(local, descriptor); const central = Buffer.alloc(46 + encoded.length); central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(flags, 8); central.writeUInt16LE(method, 10); central.writeUInt32LE(crc32(data), 16); central.writeUInt32LE(compressedData.length, 20); central.writeUInt32LE(uncompressed, 24); central.writeUInt16LE(encoded.length, 28); central.writeUInt32LE(offset, 42); encoded.copy(central, 46); centrals.push(central); offset += local.length + descriptor.length; } const central = Buffer.concat(centrals); const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.size, 8); end.writeUInt16LE(files.size, 10); end.writeUInt32LE(central.length, 12); end.writeUInt32LE(offset, 16); return Buffer.concat([...locals, central, end]); }
