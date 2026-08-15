#!/usr/bin/env node
import { createHash, randomUUID } from "node:crypto";
import { inflateRawSync } from "node:zlib";
import { closeSync, constants, fchmodSync, fsyncSync, fstatSync, lstatSync, openSync, readFileSync, renameSync, unlinkSync, writeSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const repository = "AquilaXk/easysubway-backend";
const artifactRepository = "ghcr.io/aquilaxk/easysubway-backend-contracts";
const payloadName = "journey-v3-contract-bundle-v2.json";
const artifactType = "application/vnd.easysubway.journey.contract-bundle.v2";
const payloadMediaType = "application/vnd.easysubway.journey.contract-bundle.v2+json";
const manifestMediaType = "application/vnd.oci.image.manifest.v1+json";
const emptyConfigMediaType = "application/vnd.oci.empty.v1+json";
const emptyConfigDigest = "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
const maxArchiveBytes = 8 * 1024 * 1024;
const maxCentralBytes = 64 * 1024;
const maxEntryBytes = 4 * 1024 * 1024;
const inventory = ["evidence-ledger.sha256", "journey-contract-prepublication-run-metadata.json", "journey-v3-contract-bundle-v2-descriptor.json", "journey-v3-contract-bundle-v2-manifest.json", "journey-v3-contract-bundle-v2-receipt.json", payloadName];
const resources = [["journey-v3-error-catalog", "contracts/api/journey-v3-error-catalog.json", "application/json"], ["journey-v3-error-disposition", "contracts/api/journey-v3-error-disposition.json", "application/json"], ["journey-v3-session-integrity", "contracts/api/journey-v3-session-integrity.json", "application/json"], ["journey-v3-openapi", "contracts/api/journey-v3.openapi.yaml", "application/yaml"]];

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { importJourneyContractPrepublication(process.argv.slice(2)); } catch (error) {
    process.stderr.write(`import-journey-contract-prepublication: ${error instanceof Error ? error.message : "invalid input"}\n`);
    process.exitCode = 1;
  }
}

export function importJourneyContractPrepublication(values, options = {}) {
  const args = parse(values);
  assertOutputInputsAreDistinct(args);
  const caller = json(readRegularFile(args.callerMetadata, "caller metadata"), "caller metadata");
  validateCaller(caller);
  const files = readArchive(args.artifactZip, caller.archiveSha256);
  validateLedger(files);
  const metadata = validateMetadata(files.get("journey-contract-prepublication-run-metadata.json"));
  validateCallerBinding(caller, metadata);
  const receipt = validateReceipt(files.get("journey-v3-contract-bundle-v2-receipt.json"), metadata);
  const lockedResources = validateBundle(files.get(payloadName), receipt);
  validateManifest(files.get("journey-v3-contract-bundle-v2-manifest.json"), files.get("journey-v3-contract-bundle-v2-descriptor.json"), files.get(payloadName), receipt);
  const lock = { schemaVersion: 2, component: "backend", bundleVersion: "2.0.0", producer: receipt.producer, artifact: receipt.artifact, payload: receipt.payload, publicationReceiptSha256: hash(files.get("journey-v3-contract-bundle-v2-receipt.json")), resources: lockedResources };
  const trust = { schemaVersion: 1, artifactKind: "journey-contract-publication-trust", producer: receipt.producer, artifact: receipt.artifact, payload: receipt.payload, artifactInventory: inventory, artifactTrustAnchors: Object.fromEntries(inventory.map((name) => [name, hash(files.get(name))])) };
  writePair(args.lockOutput, lock, args.trustOutput, trust, options);
}

function parse(values) {
  const options = ["--artifact-zip", "--caller-metadata", "--lock-output", "--trust-output"];
  if (values.length !== options.length * 2) throw new Error("artifact zip, caller metadata, lock output, and trust output are required");
  const found = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index], value = values[index + 1];
    if (!options.includes(key) || !value || value.startsWith("--") || Object.hasOwn(found, key)) throw new Error("invalid arguments");
    found[key] = value;
  }
  return { artifactZip: found["--artifact-zip"], callerMetadata: found["--caller-metadata"], lockOutput: found["--lock-output"], trustOutput: found["--trust-output"] };
}

function assertOutputInputsAreDistinct(args) {
  const inputs = [args.artifactZip, args.callerMetadata].map((path) => resolve(path)), outputs = [args.lockOutput, args.trustOutput].map((path) => resolve(path));
  if (outputs.some((output) => inputs.includes(output))) throw new Error("outputs must not alias inputs");
}

function readRegularFile(path, label, maxBytes) {
  const lexical = lstatSync(path, { throwIfNoEntry: false });
  if (!lexical || lexical.isSymbolicLink() || !lexical.isFile()) throw new Error(`${label} must be a regular file`);
  let descriptor;
  try { descriptor = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW); } catch { throw new Error(`${label} must be a regular file`); }
  try {
    const before = fstatSync(descriptor);
    if (!before.isFile() || before.dev !== lexical.dev || before.ino !== lexical.ino) throw new Error(`${label} changed while opening`);
    if (maxBytes !== undefined && before.size > maxBytes) throw new Error(`${label} is too large`);
    const bytes = readFileSync(descriptor);
    const after = fstatSync(descriptor);
    if (!sameIdentity(before, after) || bytes.length !== before.size) throw new Error(`${label} changed while reading`);
    return bytes;
  } finally { closeSync(descriptor); }
}

function json(bytes, label) { try { return JSON.parse(bytes); } catch { throw new Error(`${label} is invalid JSON`); } }
function keys(value, expected, label) { if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== expected.length || !expected.every((key) => Object.hasOwn(value, key))) throw new Error(`${label} is invalid`); }
function hash(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
function digest(value) { return /^[a-f0-9]{64}$/.test(value); }

function readArchive(path, expectedSha) {
  const bytes = readRegularFile(path, "artifact zip", maxArchiveBytes);
  if (bytes.length > maxArchiveBytes || hash(bytes) !== expectedSha) throw new Error("artifact zip identity is invalid");
  const eocd = findEocd(bytes);
  const count = bytes.readUInt16LE(eocd + 10), centralSize = bytes.readUInt32LE(eocd + 12), centralOffset = bytes.readUInt32LE(eocd + 16), centralEnd = centralOffset + centralSize;
  if (count !== inventory.length || centralSize > maxCentralBytes || centralOffset > eocd || centralEnd > eocd || bytes.readUInt16LE(eocd + 4) !== 0 || bytes.readUInt16LE(eocd + 6) !== 0 || bytes.readUInt16LE(eocd + 8) !== count) throw new Error("artifact zip directory is invalid");
  const files = new Map(); let offset = centralOffset; let total = 0;
  for (let index = 0; index < count; index += 1) {
    if (offset + 46 > centralEnd || bytes.readUInt32LE(offset) !== 0x02014b50) throw new Error("artifact zip central entry is invalid");
    const flags = bytes.readUInt16LE(offset + 8), method = bytes.readUInt16LE(offset + 10), crc = bytes.readUInt32LE(offset + 16), compressed = bytes.readUInt32LE(offset + 20), uncompressed = bytes.readUInt32LE(offset + 24), nameLength = bytes.readUInt16LE(offset + 28), extraLength = bytes.readUInt16LE(offset + 30), commentLength = bytes.readUInt16LE(offset + 32), disk = bytes.readUInt16LE(offset + 34), attributes = bytes.readUInt32LE(offset + 38), localOffset = bytes.readUInt32LE(offset + 42);
    const entryEnd = offset + 46 + nameLength + extraLength + commentLength;
    if (entryEnd > centralEnd) throw new Error("artifact zip central entry is invalid");
    const nameBytes = bytes.subarray(offset + 46, offset + 46 + nameLength), name = nameBytes.toString("ascii"), type = (attributes >>> 16) & 0o170000;
    if ((flags & ~0x808) !== 0 || !(method === 0 || method === 8) || !inventory.includes(name) || files.has(name) || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name) || !nameBytes.equals(Buffer.from(name, "ascii")) || disk !== 0 || !(type === 0 || type === 0o100000) || compressed > maxEntryBytes || uncompressed > maxEntryBytes || (compressed === 0 && uncompressed !== 0) || (compressed !== 0 && uncompressed / compressed > 200) || hasZip64(bytes.subarray(offset + 46 + nameLength, offset + 46 + nameLength + extraLength))) throw new Error("artifact zip entry is invalid");
    const data = local(bytes, localOffset, centralOffset, name, flags, method, compressed, uncompressed, crc);
    total += data.length;
    if (total > maxArchiveBytes) throw new Error("artifact zip is too large");
    files.set(name, data);
    offset = entryEnd;
  }
  if (offset !== centralEnd || files.size !== inventory.length) throw new Error("artifact zip inventory is invalid");
  return files;
}

function findEocd(bytes) {
  if (bytes.length < 22) throw new Error("artifact zip end record is invalid");
  for (let index = bytes.length - 22; index >= Math.max(0, bytes.length - 65557); index -= 1) if (bytes.readUInt32LE(index) === 0x06054b50 && index + 22 + bytes.readUInt16LE(index + 20) === bytes.length) return index;
  throw new Error("artifact zip end record is invalid");
}

function hasZip64(extra) {
  for (let offset = 0; offset < extra.length;) {
    if (offset + 4 > extra.length) return true;
    const id = extra.readUInt16LE(offset), length = extra.readUInt16LE(offset + 2);
    if (id === 1 || offset + 4 + length > extra.length) return true;
    offset += 4 + length;
  }
  return false;
}

function local(bytes, offset, centralOffset, name, flags, method, compressed, uncompressed, crc) {
  if (offset + 30 > centralOffset || bytes.readUInt32LE(offset) !== 0x04034b50 || bytes.readUInt16LE(offset + 6) !== flags || bytes.readUInt16LE(offset + 8) !== method) throw new Error("artifact zip local entry is invalid");
  const localCrc = bytes.readUInt32LE(offset + 14), localCompressed = bytes.readUInt32LE(offset + 18), localUncompressed = bytes.readUInt32LE(offset + 22), nameLength = bytes.readUInt16LE(offset + 26), extraLength = bytes.readUInt16LE(offset + 28), headerEnd = offset + 30 + nameLength + extraLength;
  if (headerEnd > centralOffset) throw new Error("artifact zip local entry is invalid");
  const nameBytes = bytes.subarray(offset + 30, offset + 30 + nameLength), localName = nameBytes.toString("ascii");
  if (localName !== name || !nameBytes.equals(Buffer.from(name, "ascii")) || hasZip64(bytes.subarray(offset + 30 + nameLength, headerEnd)) || headerEnd + compressed > centralOffset) throw new Error("artifact zip local entry is invalid");
  const localMatchesCentral = localCrc === crc && localCompressed === compressed && localUncompressed === uncompressed;
  if ((flags & 8) === 0 ? !localMatchesCentral : !(localMatchesCentral || (localCrc === 0 && localCompressed === 0 && localUncompressed === 0))) throw new Error("artifact zip local entry is invalid");
  const dataEnd = headerEnd + compressed;
  if ((flags & 8) !== 0) validateDataDescriptor(bytes, dataEnd, centralOffset, crc, compressed, uncompressed);
  const raw = bytes.subarray(headerEnd, dataEnd);
  let data;
  try { data = method === 0 ? raw : inflateRawSync(raw, { maxOutputLength: maxEntryBytes }); } catch { throw new Error("artifact zip entry payload is invalid"); }
  if (data.length !== uncompressed || crc32(data) !== crc) throw new Error("artifact zip entry checksum is invalid");
  return data;
}

function validateDataDescriptor(bytes, offset, centralOffset, crc, compressed, uncompressed) {
  const signature = offset + 4 <= centralOffset && bytes.readUInt32LE(offset) === 0x08074b50;
  const descriptorOffset = offset + (signature ? 4 : 0);
  if (descriptorOffset + 12 > centralOffset || bytes.readUInt32LE(descriptorOffset) !== crc || bytes.readUInt32LE(descriptorOffset + 4) !== compressed || bytes.readUInt32LE(descriptorOffset + 8) !== uncompressed) throw new Error("artifact zip data descriptor is invalid");
}

function crc32(bytes) { let crc = 0xffffffff; for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); } return (crc ^ 0xffffffff) >>> 0; }

function validateLedger(files) {
  const rows = files.get("evidence-ledger.sha256").toString("utf8").split("\n").filter(Boolean), expected = inventory.filter((name) => name !== "evidence-ledger.sha256");
  if (rows.length !== expected.length) throw new Error("ledger is invalid");
  const seen = new Set();
  for (const row of rows) {
    const match = /^([a-f0-9]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)$/.exec(row);
    if (!match || !expected.includes(match[2]) || seen.has(match[2]) || match[1] !== hash(files.get(match[2]))) throw new Error("ledger is invalid");
    seen.add(match[2]);
  }
}

function validateCaller(value) { keys(value, ["schemaVersion", "artifactKind", "repository", "pullRequestNumber", "sourceSha", "workflowRunId", "workflowRunAttempt", "artifactName", "archiveSha256"], "caller metadata"); if (value.schemaVersion !== 1 || value.artifactKind !== "journey-contract-prepublication-caller" || value.repository !== repository || !Number.isInteger(value.pullRequestNumber) || value.pullRequestNumber < 1 || !/^[a-f0-9]{40}$/.test(value.sourceSha) || !/^[1-9][0-9]*$/.test(value.workflowRunId) || !/^[1-9][0-9]*$/.test(value.workflowRunAttempt) || typeof value.artifactName !== "string" || !digest(value.archiveSha256)) throw new Error("caller metadata is invalid"); }
function validateMetadata(bytes) {
  const value = json(bytes, "run metadata"), expected = ["schemaVersion", "artifactKind", "repository", "pullRequestNumber", "sourceSha", "baseRef", "sourceRepository", "workflowRepository", "workflowSha", "workflowRunId", "workflowRunAttempt", "artifactName", "bundleSha256", "manifestDigest", "receiptSha256", "runMetadataSha256"];
  keys(value, expected, "run metadata");
  const { runMetadataSha256, ...payload } = value, canonical = Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
  if (!Buffer.from(bytes).equals(canonical) || !digest(runMetadataSha256) || hash(Buffer.from(JSON.stringify(payload))) !== runMetadataSha256 || value.schemaVersion !== 1 || value.artifactKind !== "journey-contract-prepublication-run" || value.repository !== repository || value.baseRef !== "main" || value.sourceRepository !== repository || value.workflowRepository !== repository || !Number.isInteger(value.pullRequestNumber) || value.pullRequestNumber < 1 || !/^[a-f0-9]{40}$/.test(value.sourceSha) || !/^[a-f0-9]{40}$/.test(value.workflowSha) || !/^[1-9][0-9]*$/.test(value.workflowRunId) || !/^[1-9][0-9]*$/.test(value.workflowRunAttempt) || !digest(value.bundleSha256) || !/^sha256:[a-f0-9]{64}$/.test(value.manifestDigest) || !digest(value.receiptSha256)) throw new Error("run metadata is invalid");
  const name = `journey-contract-prepublication-pr-${value.pullRequestNumber}-${value.sourceSha}-run-${value.workflowRunId}-attempt-${value.workflowRunAttempt}`;
  if (value.artifactName !== name) throw new Error("run metadata artifact name is invalid");
  return value;
}
function validateCallerBinding(caller, metadata) { for (const key of ["repository", "pullRequestNumber", "sourceSha", "workflowRunId", "workflowRunAttempt", "artifactName"]) if (caller[key] !== metadata[key]) throw new Error("caller metadata does not match artifact"); }
function validateReceipt(bytes, metadata) {
  if (hash(bytes) !== metadata.receiptSha256) throw new Error("receipt sha256 is invalid");
  const value = json(bytes, "receipt");
  keys(value, ["schemaVersion", "component", "bundleVersion", "producer", "artifact", "payload", "publication"], "receipt"); keys(value.producer, ["repository", "gitSha"], "receipt producer"); keys(value.artifact, ["repository", "manifestDigest", "artifactType"], "receipt artifact"); keys(value.payload, ["fileName", "mediaType", "sha256"], "receipt payload"); keys(value.publication, ["pullRequestNumber", "baseRef", "sourceRepository", "workflowRepository", "workflowSha", "workflowRunId", "workflowRunAttempt", "artifactName"], "receipt publication");
  if (value.schemaVersion !== 2 || value.component !== "backend" || value.bundleVersion !== "2.0.0" || value.producer.repository !== repository || value.producer.gitSha !== metadata.sourceSha || value.artifact.repository !== artifactRepository || value.artifact.manifestDigest !== metadata.manifestDigest || value.artifact.artifactType !== artifactType || value.payload.fileName !== payloadName || value.payload.mediaType !== payloadMediaType || value.payload.sha256 !== metadata.bundleSha256 || JSON.stringify(value.publication) !== JSON.stringify(Object.fromEntries(Object.keys(value.publication).map((key) => [key, metadata[key]])))) throw new Error("receipt is invalid");
  return value;
}
function validateBundle(bytes, receipt) {
  if (hash(bytes) !== receipt.payload.sha256) throw new Error("bundle sha256 is invalid");
  const value = json(bytes, "bundle"); keys(value, ["schemaVersion", "bundleVersion", "component", "producerRepository", "producerSha", "resources"], "bundle");
  if (value.schemaVersion !== 2 || value.bundleVersion !== "2.0.0" || value.component !== "backend" || value.producerRepository !== repository || value.producerSha !== receipt.producer.gitSha || !Array.isArray(value.resources) || value.resources.length !== resources.length) throw new Error("bundle is invalid");
  return value.resources.map((resource, index) => {
    const [id, path, mediaType] = resources[index]; keys(resource, ["id", "path", "owner", "mediaType", "sha256", "contentBase64"], "bundle resource");
    if (resource.id !== id || resource.path !== path || resource.owner !== repository || resource.mediaType !== mediaType || !digest(resource.sha256) || typeof resource.contentBase64 !== "string" || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(resource.contentBase64) || resource.contentBase64.length === 0 || Buffer.from(resource.contentBase64, "base64").toString("base64") !== resource.contentBase64 || hash(Buffer.from(resource.contentBase64, "base64")) !== resource.sha256) throw new Error("bundle resource is invalid");
    return { id: resource.id, path: resource.path, owner: resource.owner, mediaType: resource.mediaType, sha256: resource.sha256 };
  });
}
function validateManifest(manifestBytes, descriptorBytes, bundle, receipt) {
  const manifest = json(manifestBytes, "manifest"), descriptor = json(descriptorBytes, "descriptor"), digest = `sha256:${hash(manifestBytes)}`;
  keys(descriptor, ["reference", "mediaType", "digest", "size"], "descriptor");
  keys(manifest, ["schemaVersion", "mediaType", "artifactType", "config", "layers"], "manifest");
  keys(manifest.config, ["mediaType", "digest", "size", "data"], "manifest config");
  if (descriptor.reference !== `${artifactRepository}@${digest}` || descriptor.mediaType !== manifestMediaType || descriptor.digest !== digest || !Number.isSafeInteger(descriptor.size) || descriptor.size !== manifestBytes.length || receipt.artifact.manifestDigest !== digest || manifest.schemaVersion !== 2 || manifest.mediaType !== manifestMediaType || manifest.artifactType !== artifactType || manifest.config.mediaType !== emptyConfigMediaType || manifest.config.digest !== emptyConfigDigest || manifest.config.size !== 2 || manifest.config.data !== "e30=" || !Array.isArray(manifest.layers) || manifest.layers.length !== 1) throw new Error("manifest is invalid");
  const layer = manifest.layers[0]; keys(layer, ["mediaType", "digest", "size", "annotations"], "manifest layer"); keys(layer.annotations, ["org.opencontainers.image.title"], "manifest layer annotations");
  if (layer.mediaType !== payloadMediaType || layer.digest !== `sha256:${hash(bundle)}` || !Number.isSafeInteger(layer.size) || layer.size !== bundle.length || layer.annotations["org.opencontainers.image.title"] !== payloadName) throw new Error("manifest is invalid");
}

function writePair(lockPath, lockDocument, trustPath, trustDocument, options) {
  const lockOutput = resolve(lockPath), trustOutput = resolve(trustPath), parent = dirname(lockOutput);
  if (lockOutput === trustOutput || parent !== dirname(trustOutput)) throw new Error("outputs must be distinct siblings");
  const ancestors = snapshotAncestors(parent);
  assertAncestors(ancestors);
  const before = [snapshotFile(lockOutput, "lock output"), snapshotFile(trustOutput, "trust output")];
  const token = randomUUID(), stages = [];
  let firstPublished = false, secondPublished = false;
  try {
    stages.push(stage(lockOutput, Buffer.from(`${JSON.stringify(lockDocument, null, 2)}\n`), token, 0o600, options));
    stages.push(stage(trustOutput, Buffer.from(`${JSON.stringify(trustDocument, null, 2)}\n`), token, 0o600, options));
    options.beforeFirstRename?.();
    assertAncestors(ancestors); assertPrestate(lockOutput, before[0]); assertPrestate(trustOutput, before[1]); assertStage(stages[0]); assertStage(stages[1]);
    renameSync(stages[0].path, lockOutput); firstPublished = true;
    options.beforeSecondRename?.();
    assertAncestors(ancestors); assertOwned(lockOutput, stages[0]); assertPrestate(trustOutput, before[1]); assertStage(stages[1]);
    if (options.renameSecond) options.renameSecond(stages[1].path, trustOutput); else renameSync(stages[1].path, trustOutput);
    secondPublished = true;
    assertAncestors(ancestors); assertOwned(lockOutput, stages[0]); assertOwned(trustOutput, stages[1]); fsyncParent(parent);
  } catch (error) {
    if (secondPublished) restoreOwned(trustOutput, before[1], stages[1], token, ancestors);
    if (firstPublished) restoreOwned(lockOutput, before[0], stages[0], token, ancestors);
    throw error;
  } finally {
    for (const current of stages) removeOwnedStage(current, ancestors);
  }
}

function snapshotAncestors(path) {
  const snapshots = []; let current = "/";
  for (const segment of resolve(path).split("/").filter(Boolean)) {
    current = resolve(current, segment); const metadata = lstatSync(current, { throwIfNoEntry: false });
    if (!metadata || metadata.isSymbolicLink() || !metadata.isDirectory()) throw new Error("output parent is invalid");
    snapshots.push({ path: current, dev: metadata.dev, ino: metadata.ino });
  }
  return snapshots;
}
function assertAncestors(snapshots) { for (const snapshot of snapshots) { const current = lstatSync(snapshot.path, { throwIfNoEntry: false }); if (!current || current.isSymbolicLink() || !current.isDirectory() || current.dev !== snapshot.dev || current.ino !== snapshot.ino) throw new Error("output ancestor changed"); } }
function snapshotFile(path, label) {
  const stat = lstatSync(path, { throwIfNoEntry: false });
  if (!stat) return null;
  if (stat.isSymbolicLink() || !stat.isFile()) throw new Error(`${label} is invalid`);
  const bytes = readRegularFile(path, label), stable = lstatSync(path, { throwIfNoEntry: false });
  if (!stable || !sameIdentity(stat, stable)) throw new Error(`${label} changed while reading`);
  return { dev: stable.dev, ino: stable.ino, size: stable.size, mode: stable.mode & 0o777, bytes };
}
function assertPrestate(path, before) {
  const current = snapshotFile(path, "output");
  if (before === null ? current !== null : current === null || current.dev !== before.dev || current.ino !== before.ino || current.size !== before.size || current.mode !== before.mode || !current.bytes.equals(before.bytes)) throw new Error("output prestate changed");
}
function stage(target, bytes, token, mode = 0o600, options = {}) {
  const path = `${target}.stage-${token}`; let descriptor, opened, completed = false;
  try {
    descriptor = openSync(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, mode);
    opened = fstatSync(descriptor);
    writeFully(descriptor, bytes); options.afterStageWrite?.(path); fchmodSync(descriptor, mode); fsyncSync(descriptor);
    const identity = fstatSync(descriptor);
    completed = true;
    return { path, bytes, dev: identity.dev, ino: identity.ino, size: identity.size, mode: identity.mode & 0o777 };
  } finally {
    if (descriptor !== undefined) closeSync(descriptor);
    if (!completed && opened) removeOwnedPath(path, opened);
  }
}
function assertStage(stage_) { assertOwned(stage_.path, stage_); }
function assertOwned(path, identity) {
  const current = snapshotFile(path, "output");
  if (!current || current.dev !== identity.dev || current.ino !== identity.ino || current.size !== identity.size || current.mode !== identity.mode || !current.bytes.equals(identity.bytes)) throw new Error("output ownership changed");
}
function restoreOwned(path, before, owned, token, ancestors) {
  try {
    assertAncestors(ancestors); assertOwned(path, owned);
    if (before === null) { unlinkSync(path); fsyncParent(dirname(path)); return; }
    const rollback = stage(path, before.bytes, `rollback-${token}`, before.mode);
    try { assertAncestors(ancestors); assertOwned(path, owned); renameSync(rollback.path, path); assertOwned(path, rollback); fsyncParent(dirname(path)); } finally { removeOwnedStage(rollback, ancestors); }
  } catch { /* A foreign replacement is never overwritten during rollback. */ }
}
function removeOwnedStage(stage_, ancestors) {
  try { assertAncestors(ancestors); assertOwned(stage_.path, stage_); unlinkSync(stage_.path); } catch { /* Keep a path whose ownership cannot be proved. */ }
}
function removeOwnedPath(path, identity) {
  const current = lstatSync(path, { throwIfNoEntry: false });
  if (current && current.isFile() && current.dev === identity.dev && current.ino === identity.ino) unlinkSync(path);
}
function fsyncParent(path) { let descriptor; try { descriptor = openSync(path, constants.O_RDONLY | constants.O_DIRECTORY); fsyncSync(descriptor); } finally { if (descriptor !== undefined) closeSync(descriptor); } }
function writeFully(descriptor, bytes) { for (let offset = 0; offset < bytes.length;) { const written = writeSync(descriptor, bytes, offset, bytes.length - offset); if (!Number.isSafeInteger(written) || written <= 0 || written > bytes.length - offset) throw new Error("output write is incomplete"); offset += written; } }
function sameIdentity(a, b) { return a.dev === b.dev && a.ino === b.ino && a.size === b.size; }
