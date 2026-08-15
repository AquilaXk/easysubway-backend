import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";
const script = resolve(import.meta.dirname, "verify-journey-contract-transport-tag-absent.mjs"); const tag = `prepublish-pr-252-head-${"a".repeat(40)}-run-123-attempt-1`;
test("package version pagination은 exact transport tag 부재만 허용한다", () => {
  const directory = mkdtempSync(join(tmpdir(), "journey-tag-pages-"));
  try {
    const pages = join(directory, "pages.json");
    writeFileSync(pages, JSON.stringify([[{ metadata: { container: { tags: ["other"] } } }], []]));
    run(pages);
    writeFileSync(pages, JSON.stringify([[{ metadata: { container: { tags: [tag] } } }]]));
    assert.throws(() => run(pages), /already exists/);
    writeFileSync(pages, JSON.stringify({}));
    assert.throws(() => run(pages), /invalid/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
function run(pages) { execFileSync(process.execPath, [script, "--pages", pages, "--tag", tag], { stdio: "pipe" }); }
