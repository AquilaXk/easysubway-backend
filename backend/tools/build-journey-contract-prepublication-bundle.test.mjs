import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
import { JOURNEY_CONTRACT_RESOURCES } from "./journey-contract-resources.mjs";

const root = resolve(import.meta.dirname, "../..");
const script = join(root, "backend/tools/build-journey-contract-prepublication-bundle.mjs");

test("prepublication producer는 trusted source의 네 fixed resource만 byte-deterministic bundle로 만든다", () => {
  const source = mkdtempSync(join(tmpdir(), "journey-prepublish-source-"));
  mkdirSync(join(root, "backend/build"), { recursive: true });
  const output = mkdtempSync(join(root, "backend/build/journey-prepublish-bundle-test-"));
  try {
    cpSync(join(root, "contracts/api"), join(source, "contracts/api"), { recursive: true });
    execFileSync("git", ["-C", source, "init"], { stdio: "pipe" }); execFileSync("git", ["-C", source, "config", "user.email", "test@example.invalid"], { stdio: "pipe" }); execFileSync("git", ["-C", source, "config", "user.name", "Test"], { stdio: "pipe" }); execFileSync("git", ["-C", source, "add", "contracts/api"], { stdio: "pipe" }); execFileSync("git", ["-C", source, "commit", "-m", "source"], { stdio: "pipe" }); const sha = execFileSync("git", ["-C", source, "rev-parse", "HEAD"], { encoding: "utf8" }).trim(); writeFileSync(join(source, ".git/FETCH_HEAD"), `${sha}\t\t\n`);
    const first = join(output, "first.json"); const second = join(output, "second.json");
    run(source, first); run(source, second);
    assert.deepEqual(readFileSync(first), readFileSync(second));
    const bundle = JSON.parse(readFileSync(first));
    assert.equal(bundle.producerRepository, "AquilaXk/easysubway-backend");
    assert.equal(bundle.producerSha, sha);
    assert.deepEqual(bundle.resources.map(({ id, path, mediaType }) => ({ id, path, mediaType })),
      JOURNEY_CONTRACT_RESOURCES);
    writeFileSync(join(source, "contracts/api/journey-v3.openapi.yaml"), "worktree race must not affect blob bytes\n");
    run(source, join(output, "after-worktree-change.json")); assert.deepEqual(readFileSync(first), readFileSync(join(output, "after-worktree-change.json")));
  } finally { rmSync(source, { recursive: true, force: true }); rmSync(output, { recursive: true, force: true }); }
});

function run(source, output) { const sha = execFileSync("git", ["-C", source, "rev-parse", "FETCH_HEAD"], { encoding: "utf8" }).trim(); execFileSync(process.execPath, [script, "--git-directory", source, "--source-sha", sha, "--output", output], { stdio: "pipe" }); }
