import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

const root = resolve(import.meta.dirname, "../..");
const script = join(root, "backend/tools/build-journey-contract-prepublication-receipt.mjs");
const repository = "ghcr.io/aquilaxk/easysubway-backend-contracts";
const sourceSha = "a".repeat(40);
const transportTag = `prepublish-pr-253-head-${sourceSha}-run-123-attempt-2`;
const artifactType = "application/vnd.easysubway.journey.contract-bundle.v2";

test("prepublication receipt는 ORAS 1.3.3 여섯 키 tag-push descriptor만 수락한다", () => {
  const directory = mkdtempSync(join(tmpdir(), "journey-prepublication-receipt-"));
  try {
    const bundle = join(directory, "bundle.json");
    const manifest = join(directory, "manifest.json");
    const descriptor = join(directory, "descriptor.json");
    writeFileSync(bundle, "{}\n");
    writeFileSync(manifest, "{\"schemaVersion\":2}\n");
    const manifestBytes = readFileSync(manifest);
    const digest = `sha256:${sha(manifestBytes)}`;
    const valid = {
      reference: `${repository}@${digest}`,
      mediaType: "application/vnd.oci.image.manifest.v1+json",
      digest,
      size: manifestBytes.length,
      artifactType,
      referenceAsTags: [`${repository}:${transportTag}`],
    };

    writeFileSync(descriptor, JSON.stringify(valid));
    const output = join(directory, "receipt.json");
    run(bundle, manifest, descriptor, output);
    assert.equal(JSON.parse(readFileSync(output, "utf8")).artifact.manifestDigest, digest);

    const invalid = [
      ...Object.keys(valid).map((key) => [`missing ${key}`, (value) => delete value[key]]),
      ["extra key", (value) => { value.extra = true; }],
      ["wrong reference", (value) => { value.reference = `${repository}@sha256:${"0".repeat(64)}`; }],
      ["wrong media type", (value) => { value.mediaType = "application/json"; }],
      ["wrong digest", (value) => { value.digest = `sha256:${"0".repeat(64)}`; }],
      ["wrong size", (value) => { value.size += 1; }],
      ["wrong artifact type", (value) => { value.artifactType = "application/json"; }],
      ["wrong transport tag", (value) => { value.referenceAsTags = [`${repository}:other`]; }],
      ["multiple transport tags", (value) => { value.referenceAsTags.push(`${repository}:other`); }],
    ];
    for (const [name, mutate] of invalid) {
      const candidate = structuredClone(valid);
      mutate(candidate);
      writeFileSync(descriptor, JSON.stringify(candidate));
      const rejected = join(directory, `${name}.json`);
      assert.throws(() => run(bundle, manifest, descriptor, rejected), /receipt input is invalid/, name);
      assert.equal(existsSync(rejected), false, `${name}: rejected input must not write a receipt`);
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

function run(bundle, manifest, descriptor, output) {
  try {
    execFileSync(process.execPath, [script, "--bundle", bundle, "--manifest", manifest, "--descriptor", descriptor, "--source-sha", sourceSha, "--workflow-sha", "b".repeat(40), "--run-id", "123", "--attempt", "2", "--pr", "253", "--output", output], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    throw new Error(error.stderr || error.message);
  }
}

function sha(value) { return createHash("sha256").update(value).digest("hex"); }
