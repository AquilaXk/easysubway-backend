import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(import.meta.dirname, "../..");
const script = join(repositoryRoot, "backend/tools/build-component-manifest.mjs");
const gitSha = "a".repeat(40);
const imageDigest = `sha256:${"b".repeat(64)}`;

test("build-component-manifest는 immutable backend identity를 결정적으로 발행한다", () => {
  const fixture = createFixture();
  try {
    run(fixture);
    assert.deepEqual(JSON.parse(readFileSync(fixture.output, "utf8")), {
      schemaVersion: 1,
      component: "backend",
      repository: "AquilaXk/easysubway",
      gitSha,
      artifactIdentity: { imageDigest, apiContractVersion: "1.0.0" },
      contractVersion: "1.0.0",
      evidenceSha256: createHash("sha256").update("release evidence\n").digest("hex"),
      issueRefs: ["AquilaXk/easysubway#2697"],
    });
    const first = readFileSync(fixture.output);
    run({ ...fixture, output: join(fixture.directory, "second.json") });
    assert.deepEqual(readFileSync(join(fixture.directory, "second.json")), first);
  } finally {
    fixture.cleanup();
  }
});

test("build-component-manifest는 mutable 또는 malformed digest를 거부한다", () => {
  const fixture = createFixture();
  try {
    for (const digest of ["ghcr.io/aquilaxk/easysubway-backend:latest", "sha256:latest", `sha256:${"B".repeat(64)}`, "sha256:abc"]) {
      assert.throws(() => run(fixture, ["--image-digest", digest]), /digest/i);
    }
  } finally {
    fixture.cleanup();
  }
});

test("build-component-manifest는 qualified issue ref와 lowercase git SHA를 요구한다", () => {
  const fixture = createFixture();
  try {
    assert.throws(() => run(fixture, ["--issue-ref", "2697"]), /issue ref/i);
    assert.throws(() => run(fixture, ["--git-sha", "A".repeat(40)]), /git sha/i);
    assert.throws(() => run(fixture, ["--git-sha", "a".repeat(39)]), /git sha/i);
  } finally {
    fixture.cleanup();
  }
});

test("build-component-manifest는 unreadable evidence와 duplicate 또는 extra CLI args를 거부한다", () => {
  const fixture = createFixture();
  try {
    assert.throws(() => run(fixture, ["--evidence", join(fixture.directory, "missing.txt")]), /evidence/i);
    assert.throws(() => run(fixture, [], ["--repository", "AquilaXk/easysubway"]), /duplicate/i);
    assert.throws(() => run(fixture, [], ["--unexpected", "value"]), /unknown|arguments/i);
  } finally {
    fixture.cleanup();
  }
});

test("build-component-manifest는 symlink output과 없는 output parent를 거부한다", () => {
  const fixture = createFixture();
  try {
    const symlinkOutput = join(fixture.directory, "manifest-link.json");
    symlinkSync(fixture.evidence, symlinkOutput);
    assert.throws(() => run({ ...fixture, output: symlinkOutput }), /output must not be a symlink/);
    assert.throws(
      () => run({ ...fixture, output: join(fixture.directory, "missing", "manifest.json") }),
      /output parent is unavailable/,
    );
  } finally {
    fixture.cleanup();
  }
});

test("backend immutable producer는 no-push preflight와 digest evidence ledger를 고정한다", () => {
  const workflow = readFileSync(join(repositoryRoot, ".github/workflows/release-artifacts.yml"), "utf8");
  const preflight = workflow.slice(0, workflow.indexOf("  backend-release:"));

  assert.match(workflow, /image-preflight:[\s\S]*?if: \$\{\{ github\.event_name != 'push' \}\}/);
  assert.doesNotMatch(preflight, /--push|push=true|docker\/login-action/);
  assert.match(workflow, /backend-release:[\s\S]*?github\.event_name == 'push'[\s\S]*?github\.ref == 'refs\/heads\/main'/);
  assert.match(workflow, /type=image,name=\$\{repository\},push-by-digest=true,name-canonical=true,push=true/);
  assert.doesNotMatch(workflow, /ghcr\.io\/aquilaxk\/easysubway-backend:/);
  assert.match(workflow, /release-artifacts\/backend\/sbom\.json/);
  assert.match(workflow, /release-artifacts\/backend\/provenance\.json/);
  assert.match(workflow, /release-artifacts\/backend\/evidence-ledger\.sha256/);
  assert.match(workflow, /--evidence release-artifacts\/backend\/evidence-ledger\.sha256/);
  assert.ok(workflow.includes("--format '{{json .SBOM.SPDX}}'"));
  assert.ok(workflow.includes("--format '{{json .Provenance.SLSA}}'"));
  assert.ok(workflow.includes('subject="${repository}@${digest}"'));
  assert.ok(workflow.includes('docker buildx imagetools inspect --raw "${subject}" > release-artifacts/backend/image-index.json'));
  assert.ok(!workflow.includes(".Manifest.Digest"));
  assert.ok(workflow.includes('[[ "${platform}" != "linux/arm64" ]]'));
  assert.ok(workflow.includes("name: easysubway-backend-release-${{ github.sha }}-${{ github.run_attempt }}"));
  assert.ok(workflow.includes("backend final base image must be digest pinned"));
  assert.ok(workflow.includes(`(\n            cd release-artifacts/backend\n            for evidence in release-metadata.txt image-index.json image-inspect.json sbom.json provenance.json; do
              sha256sum "\${evidence}"
            done\n          ) > release-artifacts/backend/evidence-ledger.sha256`));
  assert.ok(workflow.includes(String.raw`s/.*"containerimage\.digest"[[:space:]]*:[[:space:]]*"(sha256:[a-f0-9]{64})".*/\1/p`));
  assert.ok(workflow.includes(String.raw`s/^imageDigest=(sha256:[a-f0-9]{64})$/\1/p`));
  assert.ok(!workflow.includes(String.raw`containerimage\\.digest`));
  assert.ok(!workflow.includes(String.raw`*/\\1/p`));
});

test("backend automerge coordinator는 current-head review와 FIFO를 fail-closed로 검증한다", () => {
  const workflow = readFileSync(join(repositoryRoot, ".github/workflows/automerge-queue.yml"), "utf8");

  assert.ok(workflow.includes('required_checks=\'["Backend CI","Dependency Vulnerability Scan / osv-scan","Automerge Review Gate"]\''));
  assert.ok(workflow.includes("  pull_request_target:\n"));
  assert.ok(!workflow.includes("  pull_request:\n"));
  assert.ok(workflow.includes("github.event_name != 'pull_request_target'"));
  assert.ok(workflow.includes("github.event_name == 'pull_request_target'"));
  assert.ok(!workflow.includes("--json number --jq '.[].number' || true"));
  assert.ok(workflow.includes("--state open --limit 1000"));
  assert.ok(!workflow.includes('|| { echo "::warning::PR #${cand} 조회 실패 — 후보에서 건너뛴다."; continue; }'));
  assert.ok(workflow.includes("--json headRefOid,mergeStateStatus,autoMergeRequest,reviews,statusCheckRollup"));
  assert.ok(workflow.includes(".autoMergeRequest != null"));
  assert.ok(workflow.includes('.commit.oid == $head_oid'));
  assert.ok(workflow.includes('.state == "COMMENTED"'));
  assert.ok(workflow.includes('.author.login == "coderabbitai"'));
  assert.ok(workflow.includes('.author.login == $repo_owner'));
  assert.ok(workflow.includes('startswith("**Actionable comments posted:")'));
  assert.ok(workflow.includes("gh api graphql --paginate --slurp"));
  assert.ok(workflow.includes('$endCursor:String'));
  assert.ok(workflow.includes("reviewThreads(first:100,after:$endCursor)"));
  assert.ok(workflow.includes("pageInfo{hasNextPage endCursor}"));
  assert.ok(workflow.includes("[.[].data.repository.pullRequest.reviewThreads.nodes[]"));
  assert.ok(workflow.includes('select(.isResolved == false)'));
  const behindGate = workflow.indexOf('if [ "${merge_state}" = "BEHIND" ]; then');
  const autoMergeReservation = workflow.indexOf('gh pr merge "${pr_number}" --repo "${REPO}" --squash --auto');
  assert.ok(autoMergeReservation >= 0 && behindGate >= 0 && behindGate < autoMergeReservation,
    "BEHIND head must be updated before auto-merge is reserved");
  assert.ok(workflow.includes("checks: write"));
  assert.ok(workflow.includes("CHECKS_TOKEN: ${{ github.token }}"));
  assert.ok(workflow.includes('GH_TOKEN="${CHECKS_TOKEN}" gh api --method POST'));
  assert.ok(workflow.includes('repos/${REPO}/check-runs'));
  assert.ok(workflow.includes("Automerge Review Gate"));
  assert.ok(workflow.includes('-f head_sha="${head_oid}"'));
  assert.ok(
    workflow.indexOf('repos/${REPO}/check-runs') < autoMergeReservation,
    "the exact-head review check must pass before auto-merge is reserved",
  );
  assert.ok(workflow.includes('--match-head-commit "${head_oid}"'));
  const disableAuto = workflow.indexOf('gh pr merge "${pr_number}" --repo "${REPO}" --disable-auto');
  assert.ok(disableAuto >= 0 && disableAuto < workflow.indexOf('gh pr edit "${pr_number}" --repo "${REPO}" --remove-label automerge'),
    "derail must disable an existing auto-merge reservation before removing the label");
  for (const conclusion of ["FAILURE", "ERROR", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE", "STALE"]) {
    assert.ok(workflow.includes(`$c == "${conclusion}"`), conclusion);
  }
});

function createFixture() {
  const directory = mkdtempSync(join(tmpdir(), "build-component-manifest-"));
  const evidence = join(directory, "release-metadata.txt");
  writeFileSync(evidence, "release evidence\n");
  return {
    directory,
    evidence,
    output: join(directory, "backend-component-manifest.json"),
    cleanup() {
      rmSync(directory, { recursive: true, force: true });
    },
  };
}

function run(fixture, replacements = [], extraArguments = []) {
  const args = [
    "--repository", "AquilaXk/easysubway",
    "--git-sha", gitSha,
    "--image-digest", imageDigest,
    "--contract-version", "1.0.0",
    "--evidence", fixture.evidence,
    "--issue-ref", "AquilaXk/easysubway#2697",
    "--output", fixture.output,
  ];
  for (let index = 0; index < replacements.length; index += 2) {
    const option = replacements[index];
    const value = replacements[index + 1];
    const position = args.indexOf(option);
    if (position === -1) args.push(option, value);
    else args[position + 1] = value;
  }
  return execFileSync(process.execPath, [script, ...args, ...extraArguments], { encoding: "utf8", stdio: "pipe" });
}
