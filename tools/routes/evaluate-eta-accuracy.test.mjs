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
  assert.deepEqual(report.referenceSourceCounts, {
    PROVIDER_LIVE_ARRIVAL: 0,
    STATIC_TIMETABLE_REFERENCE: 0,
    MANUAL_OBSERVATION: 0,
    COMPETING_APP_COMPARISON: 0,
    FIXTURE_EXPECTED: 100,
  });
  assert.equal(report.productionSampleSize, 0);
  assert.equal(report.nonProductionSampleSize, 100);
  assert.equal(report.metrics.sampleSize, 100);
  assert.equal(report.metrics.p50ErrorSeconds, 45);
  assert.equal(report.metrics.p90ErrorSeconds, 90);
  assert.equal(report.metrics.routeNotFoundRate, 0);
  assert.equal(report.metrics.wrongTransferCountRate, 0);
  assert.equal(report.metrics.wrongLineSequenceRate, 0);
  assert.equal(report.metrics.strictStepFreeFalsePositiveCount, 0);
  assert.equal(report.metrics.etaSourceMismatchCount, 0);
  assert.equal(report.metrics.realtimeFallbackMismatchCount, 0);
  assert.equal(report.metrics.providerStaleMisuseCount, 0);
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
  assert.ok(report.failures.some((failure) => failure.message === "observed ETA error exceeds max"));
  assert.ok(report.failures.some((failure) => failure.message === "invalid number: observed_eta_error_seconds"));
});

test("ETA evaluator emits structured production-safe failures", async (t) => {
  const dataset = path.join(tmpdir(), `route-accuracy-production-${Date.now()}`);
  const output = path.join(tmpdir(), `route-accuracy-production-${Date.now()}.json`);
  t.after(() => rm(dataset, { recursive: true, force: true }));
  t.after(() => rm(output, { force: true }));
  await mkdir(dataset, { recursive: true });

  const header = [
    "case_id",
    "origin_station_id",
    "destination_station_id",
    "departure_time",
    "mobility_type",
    "constraint_mode",
    "realtime_expected",
    "reference_source",
    "reference_collected_at",
    "reference_confidence",
    "realtime_provider_id",
    "realtime_supported",
    "planned_only_allowed",
    "provider_freshness_status",
    "expected_eta_source",
    "actual_eta_source",
    "expected_line_sequence",
    "actual_line_sequence",
    "expected_transfer_count",
    "actual_transfer_count",
    "max_eta_error_seconds",
    "observed_eta_error_seconds",
    "use_realtime",
    "actual_route_found",
    "strict_step_free_expected_status",
    "actual_edge_types",
    "notes",
  ].join(",");
  const rowsByFile = {
    "single_ride_cases.csv": [
      "case-live,station-a,station-b,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,true,PROVIDER_LIVE_ARRIVAL,2026-06-30T08:59:30+09:00,HIGH,seoul-topis,true,false,FRESH,REALTIME,REALTIME,line-4,line-4,0,0,100,20,true,true,STEP_FREE,RIDE,live ok",
      "case-fallback,station-a,station-h,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,true,PROVIDER_LIVE_ARRIVAL,2026-06-30T08:59:40+09:00,HIGH,seoul-topis,true,false,FRESH,REALTIME,STATIC_LOCAL,line-4,line-4,0,0,100,25,true,true,STEP_FREE,RIDE,provider fallback",
    ].join("\n"),
    "one_transfer_cases.csv": "case-stale,station-a,station-c,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,true,PROVIDER_LIVE_ARRIVAL,2026-06-30T08:50:00+09:00,LOW,seoul-topis,true,false,STALE,REALTIME,REALTIME,line-4|line-2,line-4|line-2,1,1,100,30,true,true,STEP_FREE,RIDE|TRANSFER,stale live",
    "two_transfer_cases.csv": "case-transfer,station-a,station-d,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,false,STATIC_TIMETABLE_REFERENCE,2026-06-30T08:55:00+09:00,MEDIUM,,false,true,FRESH,PLANNED,STATIC_LOCAL,line-1|line-2,line-1|line-3,2,1,100,40,false,true,STEP_FREE,RIDE|TRANSFER,wrong transfer",
    "express_cases.csv": [
      "case-manual,station-a,station-e,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,false,MANUAL_OBSERVATION,2026-06-30T09:30:00+09:00,HIGH,,false,true,FRESH,PLANNED,PLANNED,line-9,line-9,0,0,100,50,false,true,STEP_FREE,RIDE,manual",
      "case-not-found,station-a,station-i,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,false,MANUAL_OBSERVATION,2026-06-30T09:30:00+09:00,HIGH,,false,true,FRESH,PLANNED,PLANNED,line-9,line-9,0,0,100,55,false,false,STEP_FREE,RIDE,route missing",
    ].join("\n"),
    "out_of_station_transfer_cases.csv": "case-competing,station-a,station-f,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,false,COMPETING_APP_COMPARISON,2026-06-30T09:30:00+09:00,MEDIUM,,false,true,FRESH,PLANNED,PLANNED,line-1|line-8,line-1|line-8,1,1,100,60,false,true,STEP_FREE,RIDE|WALK,competing",
    "strict_step_free_cases.csv": [
      "case-stair,station-a,station-g,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,false,FIXTURE_EXPECTED,2026-06-30T09:30:00+09:00,LOW,,false,true,FRESH,PLANNED,PLANNED,line-1,line-1,0,0,100,70,false,true,STEP_FREE,RIDE|STAIR,fixture stair",
      "case-unknown-exit,station-a,station-j,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,false,FIXTURE_EXPECTED,2026-06-30T09:30:00+09:00,LOW,,false,true,FRESH,PLANNED,PLANNED,line-1,line-1,0,0,100,75,false,true,STEP_FREE,RIDE|UNKNOWN_EXIT,fixture unknown exit",
      "case-unverified-edge,station-a,station-k,2026-06-30T09:00:00+09:00,WHEELCHAIR,STRICT_STEP_FREE,false,FIXTURE_EXPECTED,2026-06-30T09:30:00+09:00,LOW,,false,true,FRESH,PLANNED,PLANNED,line-1,line-1,0,0,100,80,false,true,STEP_FREE,RIDE|UNVERIFIED_EDGE,fixture unverified edge",
    ].join("\n"),
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
  assert.deepEqual(report.referenceSourceCounts, {
    PROVIDER_LIVE_ARRIVAL: 3,
    STATIC_TIMETABLE_REFERENCE: 1,
    MANUAL_OBSERVATION: 2,
    COMPETING_APP_COMPARISON: 1,
    FIXTURE_EXPECTED: 3,
  });
  assert.equal(report.productionSampleSize, 5);
  assert.equal(report.nonProductionSampleSize, 5);
  assert.equal(report.metrics.routeNotFoundRate, 1 / 10);
  assert.equal(report.metrics.wrongTransferCountRate, 1 / 10);
  assert.equal(report.metrics.wrongLineSequenceRate, 1 / 10);
  assert.equal(report.metrics.strictStepFreeFalsePositiveCount, 3);
  assert.equal(report.metrics.etaSourceMismatchCount, 2);
  assert.equal(report.metrics.realtimeFallbackMismatchCount, 1);
  assert.equal(report.metrics.providerStaleMisuseCount, 1);
  assert.ok(report.failures.some((failure) => (
    failure.caseId === "case-fallback" && failure.type === "REALTIME_FALLBACK_MISMATCH"
  )));
  assert.ok(report.failures.some((failure) => (
    failure.caseId === "case-not-found" && failure.type === "ROUTE_NOT_FOUND"
  )));
  assert.ok(report.failures.some((failure) => (
    failure.caseId === "case-stale" && failure.type === "PROVIDER_STALE_MISUSE"
  )));
  assert.ok(report.failures.some((failure) => (
    failure.caseId === "case-transfer" && failure.type === "WRONG_TRANSFER_COUNT"
  )));
  assert.ok(report.failures.some((failure) => (
    failure.caseId === "case-stair" && failure.type === "ACCESSIBILITY_FALSE_POSITIVE"
  )));
  assert.ok(report.failures.some((failure) => (
    failure.caseId === "case-unknown-exit" && failure.type === "ACCESSIBILITY_FALSE_POSITIVE"
  )));
  assert.ok(report.failures.some((failure) => (
    failure.caseId === "case-unverified-edge" && failure.type === "ACCESSIBILITY_FALSE_POSITIVE"
  )));
});
