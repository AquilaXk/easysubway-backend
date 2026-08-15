import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";

test("prepublication workflow는 trusted main과 exact same-repository PR source만 publish한다", () => {
  const workflow = readFileSync(resolve(import.meta.dirname, "../../.github/workflows/journey-contract-prepublication.yml"), "utf8");
  assert.match(workflow, /github\.ref == 'refs\/heads\/main'/);
  assert.match(workflow, /\.state == "open" and \.base\.ref == "main"/);
  assert.match(workflow, /\.head\.repo\.full_name == "AquilaXk\/easysubway-backend"/);
  assert.match(workflow, /\.head\.repo\.fork == false/);
  assert.match(workflow, /git -C "\$\{RUNNER_TEMP\}\/source" fetch --depth=1 --no-tags origin "\$\{SOURCE_SHA\}"/);
  assert.doesNotMatch(workflow, /checkout --detach FETCH_HEAD/);
  assert.match(workflow, /build-journey-contract-prepublication-bundle\.mjs/);
  assert.match(workflow, /prepublish-pr-\$\{PR\}-head-\$\{SOURCE_SHA\}-run-\$\{GITHUB_RUN_ID\}-attempt-\$\{GITHUB_RUN_ATTEMPT\}/);
  assert.match(workflow, /users\/AquilaXk\/packages\/container\/easysubway-backend-contracts\/versions\?per_page=100/);
  assert.match(workflow, /verify-journey-contract-transport-tag-absent\.mjs/);
  assert.match(workflow, /source-pr-before-push\.json/);
  assert.match(workflow, /oras push --format json --export-manifest/);
  assert.match(workflow, /\(cd backend\/build\/journey-contract-prepublication && oras push --format json --export-manifest journey-v3-contract-bundle-v2-manifest\.json/);
  assert.match(workflow, /"journey-v3-contract-bundle-v2\.json:application\/vnd\.easysubway\.journey\.contract-bundle\.v2\+json"/);
  assert.match(workflow, /oras pull "\$\{repository\}@\$\{manifest_digest\}"/);
  assert.match(workflow, /\$\{RUNNER_TEMP\}\/pullback\/journey-v3-contract-bundle-v2\.json/);
  assert.match(workflow, /journey-contract-prepublication-run-metadata\.json/);
  assert.match(workflow, /actions\/upload-artifact@bbbca2ddaa5d8feaa63e36b76fdaad77386f024f/);
});
