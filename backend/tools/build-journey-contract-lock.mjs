#!/usr/bin/env node
import { createHash } from "node:crypto";
import { randomUUID } from "node:crypto";
import { closeSync, constants, fchmodSync, fsyncSync, fstatSync, linkSync, lstatSync, openSync, readFileSync, readdirSync, realpathSync, rmSync, writeSync } from "node:fs";
import { basename, dirname, relative, resolve } from "node:path";
import { isDeepStrictEqual } from "node:util";
import { pathToFileURL } from "node:url";

const producerRepository = "AquilaXk/easysubway-backend";
const producerSha = "1c25e586270f0e40b5fcad32820ff9e9e3ff985f";
const artifactRepository = "ghcr.io/aquilaxk/easysubway-backend-contracts";
const expectedArtifact = {
  "evidence-ledger.sha256": "ffe221cd37cf770d18ffebd9bc8a166767dff53807c1d14ddfc626eb3b00e0aa",
  "backend-component-manifest.json": "cdd53d9fcc4396b4c16a72c79bf4c7ff3b00a9b70cd57a952d40aee402882188",
  "journey-v3-contract-bundle-v2-descriptor.json": "f0e8aefcc7b40142f0343a787c234bfd616b199bbc010274333abb12e579a93c",
  "journey-v3-contract-bundle-v2-manifest.json": "6d3b428a6e069739b98d040f6d10c5e20af10725d8656aeaaad190d5bf9fa3b1",
  "journey-v3-contract-bundle-v2-receipt.json": "dcb93a99c86f9a7790e33ceebc8c9392bb65178db1c0d2b6c0eeea5b8e75a6cd",
  "journey-v3-contract-bundle-v2.json": "1bdffede5aa577411d77a6c8ec4f18de8ea25c61b54f227e985386b81b65625f",
};
const artifactType = "application/vnd.easysubway.journey.contract-bundle.v2";
const payloadName = "journey-v3-contract-bundle-v2.json";
const payloadMediaType = "application/vnd.easysubway.journey.contract-bundle.v2+json";
const manifestMediaType = "application/vnd.oci.image.manifest.v1+json";
const expectedFiles = [
  "backend-component-manifest.json", "evidence-ledger.sha256", "image-index.json", "image-inspect.json", "journey-v3-contract-bundle-v2-descriptor.json",
  "journey-v3-contract-bundle-v2-manifest.json", "journey-v3-contract-bundle-v2-receipt.json", payloadName, "provenance.json", "release-metadata.txt", "sbom.json",
];
const expectedLedgerFiles = expectedFiles.filter((name) => name !== "backend-component-manifest.json" && name !== "evidence-ledger.sha256");
const expectedResources = [
  ["journey-v3-error-catalog", "contracts/api/journey-v3-error-catalog.json", "application/json"],
  ["journey-v3-error-disposition", "contracts/api/journey-v3-error-disposition.json", "application/json"],
  ["journey-v3-session-integrity", "contracts/api/journey-v3-session-integrity.json", "application/json"],
  ["journey-v3-openapi", "contracts/api/journey-v3.openapi.yaml", "application/yaml"],
];

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { buildJourneyContractLock(process.argv.slice(2)); } catch (error) {
    process.stderr.write(`build-journey-contract-lock: ${error instanceof Error ? error.message : "invalid input"}\n`);
    process.exitCode = 1;
  }
}

export function buildJourneyContractLock(values, { trustAnchors = expectedArtifact, beforeValidation, beforeTempOpen, beforePathIdentity, beforeRename } = {}) {
  const arguments_ = parseArguments(values);
  const artifactAncestors = snapshotAncestors(arguments_["artifact-directory"]);
  const outputAncestors = snapshotAncestors(dirname(arguments_.output));
  beforeValidation?.(); assertSnapshots(artifactAncestors); assertSnapshots(outputAncestors);
  const artifactDirectory = assertArtifactDirectory(arguments_["artifact-directory"]);
  const output = assertOutput(arguments_.output, artifactDirectory);
  assertSnapshots(artifactAncestors); assertSnapshots(outputAncestors);
  const files = readArtifact(artifactDirectory);
    validateTrustAnchors(files, trustAnchors);
    const ledgerSha256 = validateLedger(files);
    validateComponentManifest(parseJson(files.get("backend-component-manifest.json"), "component manifest"), ledgerSha256);
    const receipt = validateReceipt(parseJson(files.get("journey-v3-contract-bundle-v2-receipt.json"), "receipt"));
    const manifest = files.get("journey-v3-contract-bundle-v2-manifest.json");
    validateDescriptor(parseJson(files.get("journey-v3-contract-bundle-v2-descriptor.json"), "descriptor"), manifest, receipt);
    validateManifest(parseJson(manifest, "manifest"), files.get(payloadName), receipt);
    const resources = validateBundle(files.get(payloadName), receipt);
    writeAtomically(output, {
    schemaVersion: 2,
    component: receipt.component,
    bundleVersion: receipt.bundleVersion,
    producer: receipt.producer,
    artifact: receipt.artifact,
    payload: receipt.payload,
    publicationReceiptSha256: sha256(files.get("journey-v3-contract-bundle-v2-receipt.json")),
      resources,
    }, (temporary) => { beforeTempOpen?.(temporary); assertSnapshots(artifactAncestors); assertSnapshots(outputAncestors); }, beforePathIdentity, (temporary) => { beforeRename?.(temporary); assertSnapshots(artifactAncestors); assertSnapshots(outputAncestors); }, () => snapshotsMatch(artifactAncestors) && snapshotsMatch(outputAncestors));
}

function parseArguments(values) {
  if (values.length !== 4) throw new Error("artifact directory and output are required");
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const option = values[index];
    const value = values[index + 1];
    if (option !== "--artifact-directory" && option !== "--output") throw new Error(`unknown option: ${option}`);
    if (!value || value.startsWith("--") || Object.hasOwn(parsed, option)) throw new Error(`invalid option: ${option}`);
    parsed[option] = value;
  }
  if (!parsed["--artifact-directory"] || !parsed["--output"]) throw new Error("artifact directory and output are required");
  return { "artifact-directory": parsed["--artifact-directory"], output: parsed["--output"] };
}

function assertArtifactDirectory(path) {
  const directory = resolve(path);
  assertNoSymlinkAncestors(directory, "artifact directory");
  const metadata = lstatSync(directory, { throwIfNoEntry: false });
  if (!metadata || metadata.isSymbolicLink() || !metadata.isDirectory()) throw new Error("artifact directory must be a non-symlink directory");
  const names = readdirSync(directory).sort();
  if (names.length !== expectedFiles.length || names.some((name, index) => name !== [...expectedFiles].sort()[index])) throw new Error("artifact inventory is invalid");
  return directory;
}

function assertOutput(path, artifactDirectory) {
  const output = resolve(path);
  assertNoSymlinkAncestors(dirname(output), "output parent");
  if (isWithin(artifactDirectory, output) || isWithin(output, artifactDirectory)) throw new Error("artifact and output must not overlap");
  const parent = dirname(output);
  const parentMetadata = lstatSync(parent, { throwIfNoEntry: false });
  if (!parentMetadata || parentMetadata.isSymbolicLink() || !parentMetadata.isDirectory()) throw new Error("output parent must be a non-symlink directory");
  const metadata = lstatSync(output, { throwIfNoEntry: false });
  if (metadata && (metadata.isSymbolicLink() || !metadata.isFile())) throw new Error("output must be a regular file");
  if (realpathSync(dirname(output)) !== dirname(output)) throw new Error("output parent must be canonical");
  return output;
}

function isWithin(parent, candidate) {
  const path = relative(parent, candidate);
  return path === "" || (!path.startsWith("..") && !path.includes(`..${process.platform === "win32" ? "\\\\" : "/"}`));
}

function readArtifact(directory, lexicalDirectory = directory) {
  const files = new Map();
  for (const name of expectedFiles) {
    const path = resolve(directory, name);
    files.set(name, readRegularFile(path, `artifact file is invalid: ${name}`, resolve(lexicalDirectory, name)));
  }
  return files;
}

function validateTrustAnchors(files, anchors) {
  for (const [name, expected] of Object.entries(anchors)) if (sha256(files.get(name)) !== expected) throw new Error(`artifact trust anchor is invalid: ${name}`);
}

function readRegularFile(path, label, lexicalPath = path) {
  let descriptor;
  const snapshot = lstatSync(lexicalPath, { throwIfNoEntry: false });
  if (!snapshot || snapshot.isSymbolicLink() || !snapshot.isFile()) throw new Error(label);
  try { descriptor = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW); } catch { throw new Error(label); }
  try {
    const before = fstatSync(descriptor);
    if (!before.isFile() || before.dev !== snapshot.dev || before.ino !== snapshot.ino) throw new Error(label);
    const bytes = readFileSync(descriptor);
    const after = fstatSync(descriptor);
    if (before.dev !== after.dev || before.ino !== after.ino || before.size !== after.size) throw new Error(label);
    return bytes;
  } finally { closeSync(descriptor); }
}

function snapshotAncestors(path) {
  const snapshots = [];
  let current = "/";
  for (const segment of resolve(path).split("/").filter(Boolean)) {
    current = resolve(current, segment);
    const metadata = lstatSync(current);
    snapshots.push({ path: current, dev: metadata.dev, ino: metadata.ino, directory: metadata.isDirectory(), symlink: metadata.isSymbolicLink() });
  }
  return snapshots;
}
function assertSnapshots(snapshots) {
  if (!snapshotsMatch(snapshots)) throw new Error("directory ancestor changed during publish");
}
function snapshotsMatch(snapshots) {
  return snapshots.every((snapshot) => {
    const metadata = lstatSync(snapshot.path, { throwIfNoEntry: false });
    return Boolean(metadata && metadata.dev === snapshot.dev && metadata.ino === snapshot.ino && metadata.isDirectory() === snapshot.directory && metadata.isSymbolicLink() === snapshot.symlink);
  });
}

function assertNoSymlinkAncestors(path, label) {
  const segments = resolve(path).split("/").filter(Boolean);
  let current = "/";
  for (const segment of segments) {
    current = `${current}${segment}`;
    const metadata = lstatSync(current, { throwIfNoEntry: false });
    if (metadata?.isSymbolicLink()) throw new Error(`${label} must not have a symlink ancestor`);
    current += "/";
  }
}

function validateLedger(files) {
  const rows = files.get("evidence-ledger.sha256").toString("utf8").split("\n").filter(Boolean);
  if (rows.length !== expectedLedgerFiles.length) throw new Error("ledger row count is invalid");
  const seen = new Set();
  for (const row of rows) {
    const match = /^([a-f0-9]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)$/.exec(row);
    if (!match || !expectedLedgerFiles.includes(match[2]) || seen.has(match[2]) || sha256(files.get(match[2])) !== match[1]) throw new Error("ledger is invalid");
    seen.add(match[2]);
  }
  if (seen.size !== expectedLedgerFiles.length) throw new Error("ledger inventory is invalid");
  return sha256(files.get("evidence-ledger.sha256"));
}

function validateComponentManifest(value, ledgerSha256) {
  assertExactKeys(value, ["schemaVersion", "component", "repository", "gitSha", "artifactIdentity", "contractVersion", "evidenceSha256", "issueRefs"], "component manifest");
  assertExactKeys(value.artifactIdentity, ["imageDigest", "apiContractVersion"], "component artifact identity");
  if (value.schemaVersion !== 1 || value.component !== "backend" || value.repository !== producerRepository || value.gitSha !== producerSha || value.evidenceSha256 !== ledgerSha256 || !/^sha256:[a-f0-9]{64}$/.test(value.artifactIdentity.imageDigest) || !Array.isArray(value.issueRefs)) throw new Error("component manifest is invalid");
}

function validateReceipt(value) {
  assertExactKeys(value, ["schemaVersion", "component", "bundleVersion", "producer", "artifact", "payload"], "receipt");
  assertExactKeys(value.producer, ["repository", "gitSha"], "receipt producer");
  assertExactKeys(value.artifact, ["repository", "manifestDigest", "artifactType"], "receipt artifact");
  assertExactKeys(value.payload, ["fileName", "mediaType", "sha256"], "receipt payload");
  if (value.schemaVersion !== 1 || value.component !== "backend" || value.bundleVersion !== "2.0.0" || value.producer.repository !== producerRepository || value.producer.gitSha !== producerSha || value.artifact.repository !== artifactRepository || !/^sha256:[a-f0-9]{64}$/.test(value.artifact.manifestDigest) || value.artifact.artifactType !== artifactType || value.payload.fileName !== payloadName || value.payload.mediaType !== payloadMediaType || !/^[a-f0-9]{64}$/.test(value.payload.sha256)) throw new Error("receipt is invalid");
  return value;
}

function validateDescriptor(value, manifest, receipt) {
  assertExactKeys(value, ["reference", "mediaType", "digest", "size", "content"], "descriptor");
  const digest = `sha256:${sha256(manifest)}`;
  if (value.digest !== digest || value.digest !== receipt.artifact.manifestDigest || value.reference !== `${artifactRepository}@${digest}` || value.mediaType !== manifestMediaType || value.size !== manifest.byteLength || value.content === null || typeof value.content !== "object" || Array.isArray(value.content) || !isDeepStrictEqual(value.content, parseJson(manifest, "manifest"))) throw new Error("descriptor is invalid");
}

function validateManifest(value, bundle, receipt) {
  assertExactKeys(value, ["schemaVersion", "mediaType", "artifactType", "config", "layers", "annotations"], "manifest");
  assertExactKeys(value.config, ["mediaType", "digest", "size", "data"], "manifest config");
  if (value.schemaVersion !== 2 || value.mediaType !== manifestMediaType || value.artifactType !== artifactType || value.config.mediaType !== "application/vnd.oci.empty.v1+json" || value.config.digest !== "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a" || value.config.size !== 2 || value.config.data !== "e30=" || !Array.isArray(value.layers) || value.layers.length !== 1) throw new Error("manifest is invalid");
  const layer = value.layers[0];
  assertExactKeys(layer, ["mediaType", "digest", "size", "annotations"], "manifest layer");
  if (layer.mediaType !== payloadMediaType || layer.digest !== `sha256:${sha256(bundle)}` || layer.digest !== `sha256:${receipt.payload.sha256}` || layer.size !== bundle.byteLength || layer.annotations?.["org.opencontainers.image.title"] !== payloadName) throw new Error("manifest layer is invalid");
}

function validateBundle(bytes, receipt) {
  if (sha256(bytes) !== receipt.payload.sha256) throw new Error("bundle digest is invalid");
  const value = parseJson(bytes, "bundle");
  assertExactKeys(value, ["schemaVersion", "bundleVersion", "component", "producerRepository", "producerSha", "resources"], "bundle");
  if (value.schemaVersion !== 2 || value.bundleVersion !== "2.0.0" || value.component !== "backend" || value.producerRepository !== producerRepository || value.producerSha !== producerSha || !Array.isArray(value.resources) || value.resources.length !== expectedResources.length) throw new Error("bundle is invalid");
  return value.resources.map((resource, index) => {
    const [id, path, mediaType] = expectedResources[index];
    assertExactKeys(resource, ["id", "path", "owner", "mediaType", "sha256", "contentBase64"], "bundle resource");
    if (resource.id !== id || resource.path !== path || resource.owner !== producerRepository || resource.mediaType !== mediaType || !/^[a-f0-9]{64}$/.test(resource.sha256) || typeof resource.contentBase64 !== "string" || resource.contentBase64.length === 0) throw new Error("bundle resource is invalid");
    const raw = Buffer.from(resource.contentBase64, "base64");
    if (raw.toString("base64") !== resource.contentBase64 || sha256(raw) !== resource.sha256) throw new Error("bundle resource payload is invalid");
    return { id: resource.id, path: resource.path, owner: resource.owner, mediaType: resource.mediaType, sha256: resource.sha256 };
  });
}

function parseJson(bytes, label) {
  try { return JSON.parse(bytes); } catch { throw new Error(`invalid ${label} JSON`); }
}

function assertExactKeys(value, keys, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every((key) => Object.hasOwn(value, key))) throw new Error(`${label} is invalid`);
}

function sha256(bytes) { return createHash("sha256").update(bytes).digest("hex"); }

function writeAtomically(output, document, beforeTempOpen, beforePathIdentity, beforeRename, canCleanup) {
  const temporary = `${output}.tmp-${randomUUID()}`;
  let descriptor;
  let opened;
  let completed;
  try {
    beforeTempOpen?.(temporary);
    descriptor = openSync(temporary, "wx", 0o600);
    opened = fstatSync(descriptor);
    if (!opened.isFile()) throw new Error("temporary output identity is invalid");
    const bytes = Buffer.from(`${JSON.stringify(document, null, 2)}\n`);
    let offset = 0;
    while (offset < bytes.length) {
      const written = writeSync(descriptor, bytes, offset, bytes.length - offset);
      if (!Number.isSafeInteger(written) || written <= 0) throw new Error("output write is incomplete");
      offset += written;
    }
    fchmodSync(descriptor, 0o600); fsyncSync(descriptor);
    completed = fstatSync(descriptor);
    if (!completed.isFile() || completed.dev !== opened.dev || completed.ino !== opened.ino || (completed.mode & 0o777) !== 0o600 || completed.size !== bytes.length) throw new Error("temporary output identity is invalid");
    beforePathIdentity?.(temporary);
    assertTemporaryIdentity(temporary, completed);
    closeSync(descriptor); descriptor = undefined;
    beforeRename?.(temporary);
    assertTemporaryIdentity(temporary, completed);
    linkSync(temporary, output);
    assertTemporaryIdentity(output, completed);
    removeOwnedTemporary(temporary, completed);
  } catch (error) {
    let ownedForCleanup = completed;
    if (descriptor !== undefined) {
      try {
        const current = fstatSync(descriptor);
        if (typeof opened !== "undefined" && current.isFile() && current.dev === opened.dev && current.ino === opened.ino) ownedForCleanup = current;
      } catch {} finally { closeSync(descriptor); }
    }
    if (canCleanup?.() !== false && typeof ownedForCleanup !== "undefined") removeOwnedTemporary(temporary, ownedForCleanup);
    throw error;
  }
}
function removeOwnedTemporary(path, owned) {
  const current = lstatSync(path, { throwIfNoEntry: false });
  if (!current || current.isSymbolicLink() || !current.isFile() || current.dev !== owned.dev || current.ino !== owned.ino || current.size !== owned.size || (current.mode & 0o777) !== 0o600) return false;
  rmSync(path); return true;
}
function assertTemporaryIdentity(path, descriptor) {
  const current = lstatSync(path, { throwIfNoEntry: false });
  if (!current || current.isSymbolicLink() || !current.isFile() || current.dev !== descriptor.dev || current.ino !== descriptor.ino || current.size !== descriptor.size || (current.mode & 0o777) !== 0o600) throw new Error("temporary output identity is invalid");
}
