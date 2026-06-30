import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const root = path.resolve(import.meta.dirname, "../..");

test("ETA evaluator emits the route accuracy report contract", async () => {
  const output = path.join(tmpdir(), `route-accuracy-report-${Date.now()}.json`);
  await rm(output, { force: true });

  await execFileAsync(process.execPath, [
    "tools/routes/evaluate-eta-accuracy.mjs",
    "--dataset",
    "tools/routes/golden-od",
    "--output",
    output,
  ], { cwd: root });

  const report = JSON.parse(await readFile(output, "utf8"));
  assert.equal(report.schemaVersion, 1);
  assert.equal(report.sampleSize, 100);
  assert.equal(report.metrics.sampleSize, 100);
  assert.equal(report.metrics.p50ErrorSeconds, 45);
  assert.equal(report.metrics.p90ErrorSeconds, 90);
  assert.deepEqual(report.failures, []);
  assert.equal(report.coverage.singleRide, true);
  assert.equal(report.coverage.oneTransfer, true);
  assert.equal(report.coverage.twoTransfer, true);
  assert.equal(report.coverage.express, true);
  assert.equal(report.coverage.outOfStationTransfer, true);
  assert.equal(report.coverage.strictStepFree, true);
  assert.equal(report.coverage.realtimeSupported, true);
  assert.equal(report.coverage.realtimeUnsupported, true);
});
