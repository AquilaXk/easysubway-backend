import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import { splitCsvLine } from "./evaluate-eta-accuracy.mjs";

const execFileAsync = promisify(execFile);
const root = path.resolve(import.meta.dirname, "../..");

test("ETA evaluator emits the route accuracy report contract", async (t) => {
  const output = path.join(tmpdir(), `route-accuracy-report-${Date.now()}.json`);
  await rm(output, { force: true });
  t.after(() => rm(output, { force: true }));

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
  assert.deepEqual(report.sampleSourceCounts, {
    fixture: 100,
    staticTimetable: 0,
    realtimeProvider: 0,
    manualObservation: 0,
    staleRealtime: 0,
  });
  assert.equal(report.metrics.sampleSize, 100);
  assert.equal(report.metrics.p50ErrorSeconds, 45);
  assert.equal(report.metrics.p90ErrorSeconds, 90);
  assert.deepEqual(report.metrics.singleRide, {
    sampleSize: 35,
    p50ErrorSeconds: 45,
    p90ErrorSeconds: 90,
    maxErrorSeconds: 90,
  });
  assert.deepEqual(report.metrics.transfer, {
    sampleSize: 65,
    p50ErrorSeconds: 90,
    p90ErrorSeconds: 120,
    maxErrorSeconds: 120,
  });
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

test("CSV parser preserves escaped quotes inside quoted fields", () => {
  assert.deepEqual(
    splitCsvLine('station-a,station-b,"note ""with quote"", and comma"'),
    ["station-a", "station-b", 'note "with quote", and comma'],
  );
});

test("ETA evaluator reports file-local lines and excludes invalid numbers from metrics", async (t) => {
  const dataset = path.join(tmpdir(), `route-accuracy-dataset-${Date.now()}`);
  const output = path.join(tmpdir(), `route-accuracy-invalid-${Date.now()}.json`);
  t.after(() => rm(dataset, { recursive: true, force: true }));
  t.after(() => rm(output, { force: true }));
  await mkdir(dataset, { recursive: true });

  const header = [
    "origin_station_id",
    "destination_station_id",
    "departure_time",
    "mobility_type",
    "constraint_mode",
    "expected_transfer_count",
    "max_eta_error_seconds",
    "observed_eta_error_seconds",
    "use_realtime",
    "notes",
  ].join(",");
  const rowsByFile = {
    "single_ride_cases.csv": "station-a,station-b,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,0,100,10,true,ok",
    "one_transfer_cases.csv": "station-a,station-c,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,1,10,20,false,too slow",
    "two_transfer_cases.csv": "station-a,station-d,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,2,100,bad,false,invalid",
    "express_cases.csv": "station-a,station-b,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,0,100,30,false,express",
    "out_of_station_transfer_cases.csv": "station-a,station-e,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,1,100,40,false,out",
    "strict_step_free_cases.csv": "station-a,station-f,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,2,100,50,false,strict",
  };
  await Promise.all(Object.entries(rowsByFile).map(([file, row]) => (
    writeFile(path.join(dataset, file), `${header}\n${row}\n`)
  )));

  await assert.rejects(
    execFileAsync(process.execPath, [
      "tools/routes/evaluate-eta-accuracy.mjs",
      "--dataset",
      dataset,
      "--output",
      output,
    ], { cwd: root }),
  );

  const report = JSON.parse(await readFile(output, "utf8"));
  assert.equal(report.metrics.sampleSize, 5);
  assert.deepEqual(report.sampleSourceCounts, {
    fixture: 0,
    staticTimetable: 5,
    realtimeProvider: 1,
    manualObservation: 0,
    staleRealtime: 0,
  });
  assert.equal(report.metrics.maxErrorSeconds, 50);
  assert.ok(report.failures.includes("one_transfer_cases.csv:2 observed ETA error exceeds max"));
  assert.ok(report.failures.includes("two_transfer_cases.csv:2 invalid number: observed_eta_error_seconds"));
});
