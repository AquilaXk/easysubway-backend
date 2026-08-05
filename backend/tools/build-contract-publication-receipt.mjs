#!/usr/bin/env node
import { createHash } from "node:crypto";
import { closeSync, lstatSync, openSync, readFileSync, renameSync, rmSync, writeSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const releaseArtifactsRoot = resolve(repositoryRoot, "release-artifacts/backend");
const repository = "ghcr.io/aquilaxk/easysubway-backend-contracts";
const producerRepository = "AquilaXk/easysubway-backend";
const artifactType = "application/vnd.easysubway.journey.contract-bundle.v2";
const layerMediaType = "application/vnd.easysubway.journey.contract-bundle.v2+json";
const emptyConfigMediaType = "application/vnd.oci.empty.v1+json";
const emptyConfigDigest = "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
const fileName = "journey-v3-contract-bundle-v2.json";
const expectedResources = [
  { id: "journey-v3-error-catalog", path: "contracts/api/journey-v3-error-catalog.json", mediaType: "application/json" },
  { id: "journey-v3-error-disposition", path: "contracts/api/journey-v3-error-disposition.json", mediaType: "application/json" },
  { id: "journey-v3-openapi", path: "contracts/api/journey-v3.openapi.yaml", mediaType: "application/yaml" },
];
const options = new Set(["--bundle", "--descriptor", "--manifest", "--repository", "--git-sha", "--output"]);

try {
  const arguments_ = parseArguments(process.argv.slice(2));
  if (arguments_.repository !== repository) throw new Error("repository is invalid");
  if (!/^[a-f0-9]{40}$/.test(arguments_["git-sha"])) throw new Error("git sha is invalid");
  if (process.env.EASYSUBWAY_RECEIPT_TEST_PARTIAL_WRITE && process.env.NODE_ENV !== "test") throw new Error("partial-write hook is test-only");
  const bundle = readRegularFile(arguments_.bundle, "bundle");
  validateBundle(bundle, arguments_["git-sha"]);
  const payloadSha256 = sha256(bundle);
  const descriptor = parseJson(readRegularFile(arguments_.descriptor, "descriptor"), "descriptor");
  const manifest = readRegularFile(arguments_.manifest, "manifest");
  const manifestDigest = validateDescriptor(descriptor, manifest);
  validateManifest(parseJson(manifest, "manifest"), payloadSha256, bundle.byteLength);
  const output = assertOutput(arguments_.output);
  writeAtomically(output, {
    schemaVersion: 1,
    component: "backend",
    bundleVersion: "2.0.0",
    producer: { repository: producerRepository, gitSha: arguments_["git-sha"] },
    artifact: { repository, manifestDigest, artifactType },
    payload: { fileName, mediaType: layerMediaType, sha256: payloadSha256 },
  });
} catch (error) {
  process.stderr.write(`build-contract-publication-receipt: ${error.message}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  if (values.length % 2 !== 0) throw new Error("arguments must be option/value pairs");
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const option = values[index];
    const value = values[index + 1];
    if (!options.has(option)) throw new Error(`unknown option: ${option}`);
    if (!value || value.startsWith("--")) throw new Error(`missing value: ${option}`);
    const name = option.slice(2);
    if (Object.hasOwn(parsed, name)) throw new Error(`duplicate option: ${option}`);
    parsed[name] = value;
  }
  if (Object.keys(parsed).length !== options.size) throw new Error("bundle, descriptor, manifest, repository, git sha, and output are required");
  return parsed;
}

function validateBundle(bytes, gitSha) {
  const bundle = parseJson(bytes, "bundle");
  assertExactKeys(bundle, ["schemaVersion", "bundleVersion", "component", "producerRepository", "producerSha", "resources"], "bundle");
  if (bundle.schemaVersion !== 2 || bundle.bundleVersion !== "2.0.0" || bundle.component !== "backend" || bundle.producerRepository !== producerRepository || bundle.producerSha !== gitSha || !Array.isArray(bundle.resources)) {
    throw new Error("bundle header is invalid");
  }
  if (bundle.resources.length !== expectedResources.length) throw new Error("bundle resources are invalid");
  bundle.resources.forEach((resource, index) => {
    const expected = expectedResources[index];
    assertExactKeys(resource, ["id", "path", "owner", "mediaType", "sha256", "contentBase64"], "bundle resource");
    if (resource.id !== expected.id || resource.path !== expected.path || resource.owner !== producerRepository || resource.mediaType !== expected.mediaType || !/^[a-f0-9]{64}$/.test(resource.sha256) || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(resource.contentBase64) || resource.contentBase64.length === 0) {
      throw new Error("bundle resources are invalid");
    }
    if (sha256(Buffer.from(resource.contentBase64, "base64")) !== resource.sha256) throw new Error("bundle resource digest is invalid");
  });
}

function validateDescriptor(descriptor, manifest) {
  if (descriptor === null || typeof descriptor !== "object" || Array.isArray(descriptor)) throw new Error("descriptor is invalid");
  const manifestDigest = descriptor.digest;
  if (!/^sha256:[a-f0-9]{64}$/.test(manifestDigest) || manifestDigest !== `sha256:${sha256(manifest)}` || descriptor.reference !== `${repository}@${manifestDigest}` || descriptor.mediaType !== "application/vnd.oci.image.manifest.v1+json" || descriptor.artifactType !== artifactType || !Number.isSafeInteger(descriptor.size) || descriptor.size !== manifest.byteLength) {
    throw new Error("descriptor manifest is invalid");
  }
  return manifestDigest;
}

function validateManifest(manifest, payloadSha256, payloadSize) {
  if (manifest === null || typeof manifest !== "object" || Array.isArray(manifest) || manifest.schemaVersion !== 2 || manifest.mediaType !== "application/vnd.oci.image.manifest.v1+json" || manifest.artifactType !== artifactType) throw new Error("manifest is invalid");
  const config = manifest.config;
  if (config === null || typeof config !== "object" || Array.isArray(config) || config.mediaType !== emptyConfigMediaType || config.digest !== emptyConfigDigest || config.size !== 2) throw new Error("manifest config is invalid");
  const layers = manifest.layers;
  if (!Array.isArray(layers) || layers.length !== 1) throw new Error("descriptor layers are invalid");
  const layer = layers[0];
  if (layer === null || typeof layer !== "object" || Array.isArray(layer) || layer.mediaType !== layerMediaType || layer.digest !== `sha256:${payloadSha256}` || !Number.isSafeInteger(layer.size) || layer.size !== payloadSize || layer.annotations?.["org.opencontainers.image.title"] !== fileName) {
    throw new Error("manifest layer is invalid");
  }
}

function assertOutput(path) {
  const output = resolve(path);
  const pathBelowRoot = relative(releaseArtifactsRoot, output);
  if (!pathBelowRoot || pathBelowRoot.startsWith("..") || pathBelowRoot.includes("../")) throw new Error("output must be below release-artifacts/backend");
  const rootMetadata = lstatSync(releaseArtifactsRoot, { throwIfNoEntry: false });
  if (!rootMetadata || rootMetadata.isSymbolicLink() || !rootMetadata.isDirectory()) throw new Error("release-artifacts/backend must be a non-symlink directory");
  const metadata = lstatSync(output, { throwIfNoEntry: false });
  if (metadata && (metadata.isSymbolicLink() || !metadata.isFile())) throw new Error("output must be a regular file");
  let current = releaseArtifactsRoot;
  for (const segment of pathBelowRoot.split("/")) {
    current = resolve(current, segment);
    if (current === output) break;
    const metadata = lstatSync(current, { throwIfNoEntry: false });
    if (!metadata || metadata.isSymbolicLink() || !metadata.isDirectory()) throw new Error("output parent must be a non-symlink directory");
  }
  return output;
}

function readRegularFile(path, label) {
  const metadata = lstatSync(path, { throwIfNoEntry: false });
  if (!metadata || metadata.isSymbolicLink() || !metadata.isFile()) throw new Error(`${label} must be a regular file`);
  return readFileSync(path);
}

function parseJson(bytes, label) {
  try {
    return JSON.parse(bytes);
  } catch {
    throw new Error(`invalid ${label} JSON`);
  }
}

function assertExactKeys(value, keys, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length !== keys.length || !keys.every((key) => Object.hasOwn(value, key))) throw new Error(`${label} is invalid`);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function writeAtomically(output, document) {
  const temporary = `${output}.tmp-${process.pid}`;
  let descriptor;
  let owned = false;
  try {
    descriptor = openSync(temporary, "wx", 0o600);
    owned = true;
    const bytes = Buffer.from(`${JSON.stringify(document)}\n`);
    if (process.env.EASYSUBWAY_RECEIPT_TEST_PARTIAL_WRITE) {
      writeSync(descriptor, bytes.subarray(0, 1));
      throw new Error("partial write test hook");
    }
    writeFully(descriptor, bytes);
    closeSync(descriptor);
    descriptor = undefined;
    renameSync(temporary, output);
  } catch (error) {
    try {
      if (descriptor !== undefined) closeSync(descriptor);
    } finally {
      if (owned) rmSync(temporary, { force: true });
    }
    throw error;
  }
}

function writeFully(descriptor, bytes) {
  let offset = 0;
  let attempts = 0;
  while (offset < bytes.length) {
    const remaining = bytes.length - offset;
    const written = writeSync(descriptor, bytes, offset, remaining);
    if (!Number.isSafeInteger(written) || written <= 0 || written > remaining || ++attempts > bytes.length) throw new Error("receipt output write is incomplete");
    offset += written;
  }
}
