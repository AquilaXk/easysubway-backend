import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { buildRealtimeProviderCoverageReport, main } from "./build-realtime-provider-coverage-report.mjs";

test("builds realtime provider coverage report with freshness and mapping metrics", () => {
  const report = buildRealtimeProviderCoverageReport({
    scope: { id: "capital_pilot_android_v1", supportedStationLinePairsMin: 2 },
    staleFallbackRequired: true,
    stationLinePairs: [
      { providerId: "seoul-topis", region: "capital", stationId: "station-sangnoksu", lineId: "seoul-4", supportsArrivals: true, mappingStatus: "MAPPED" },
      { providerId: "seoul-topis", region: "capital", stationId: "station-sadang", lineId: "seoul-4", supportsArrivals: true, mappingStatus: "MAPPED" },
      { providerId: "busan-openapi", region: "busan", stationId: "station-busan", lineId: "busan-1", supportsArrivals: false, mappingStatus: "UNSUPPORTED_REGION", failureReason: "UNSUPPORTED_REGION" },
    ],
    samples: [
      { providerId: "seoul-topis", region: "capital", providerFreshnessSeconds: 12, freshnessStatus: "FRESH", labeledAsRealtime: true },
      { providerId: "busan-openapi", region: "busan", providerFreshnessSeconds: 120, freshnessStatus: "STALE", labeledAsRealtime: false },
    ],
    unsupportedRegions: [{ region: "busan", reason: "실시간 미지원" }],
  });

  assert.equal(report.schemaVersion, 1);
  assert.equal(report.supportedStationLinePairs, 2);
  assert.equal(report.providerFreshnessSecondsMaxObserved, 12);
  assert.deepEqual(report.freshness, { freshCount: 1, staleCount: 1, staleAsFreshCount: 0 });
  assert.deepEqual(report.mapping, {
    attemptedRows: 3,
    failedRows: 1,
    failureRate: 1 / 3,
    failuresByReason: { UNSUPPORTED_REGION: 1 },
  });
  assert.deepEqual(report.byProvider, [
    {
      providerId: "busan-openapi",
      supportedStationLinePairs: 0,
      mappingFailedRows: 1,
      mappingFailuresByReason: { UNSUPPORTED_REGION: 1 },
      freshCount: 0,
      staleCount: 1,
    },
    {
      providerId: "seoul-topis",
      supportedStationLinePairs: 2,
      mappingFailedRows: 0,
      mappingFailuresByReason: {},
      freshCount: 1,
      staleCount: 0,
    },
  ]);
  assert.deepEqual(report.byRegion, [
    {
      region: "busan",
      supportedStationLinePairs: 0,
      mappingFailedRows: 1,
      mappingFailuresByReason: { UNSUPPORTED_REGION: 1 },
      freshCount: 0,
      staleCount: 1,
    },
    {
      region: "capital",
      supportedStationLinePairs: 2,
      mappingFailedRows: 0,
      mappingFailuresByReason: {},
      freshCount: 1,
      staleCount: 0,
    },
  ]);
  assert.deepEqual(report.unsupportedRegions, [{ region: "busan", reason: "실시간 미지원" }]);
});

test("writes realtime provider coverage report json", async () => {
  const dir = await mkdtemp(path.join(tmpdir(), "realtime-provider-coverage-"));
  const input = path.join(dir, "coverage-input.json");
  const output = path.join(dir, "realtime-provider-coverage-report.json");
  await writeFile(input, JSON.stringify({ staleFallbackRequired: true, stationLinePairs: [], samples: [] }));

  await main(["--input", input, "--output", output]);

  const report = JSON.parse(await readFile(output, "utf8"));
  assert.equal(report.schemaVersion, 1);
  assert.equal(report.mapping.failureRate, 0);
});
