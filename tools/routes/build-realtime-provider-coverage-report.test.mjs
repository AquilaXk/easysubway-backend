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

test("3상태 resolution은 지원률과 조사완결률을 분리하고 PLANNED를 REALTIME claim으로 세지 않는다", () => {
  const report = buildRealtimeProviderCoverageReport({
    stationLinePairs: [],
    samples: [],
    coverageRequirements: [
      {
        regionId: "capital",
        operatorId: "operator-a",
        lineId: "line-a",
        sourceDomain: "realtime_arrivals",
        state: "SUPPORTED",
        fallback: "NONE",
      },
      {
        regionId: "busan",
        operatorId: "operator-b",
        lineId: "line-b",
        sourceDomain: "realtime_arrivals",
        state: "EXPLICITLY_UNSUPPORTED_WITH_EVIDENCE",
        fallback: "PLANNED",
        userMessageKo: "이 노선은 실시간 도착 정보를 아직 제공하지 않아요.",
      },
      {
        regionId: "daejeon",
        operatorId: "operator-c",
        lineId: "line-c",
        sourceDomain: "realtime_arrivals",
        state: "MISSING",
        fallback: "UNSUPPORTED_REGION",
      },
    ],
  });

  assert.deepEqual(report.coverageResolution, {
    requirementCount: 3,
    supportedCount: 1,
    explicitlyUnsupportedCount: 1,
    missingCount: 1,
    supportedRatio: 1 / 3,
    terminalResolutionRatio: 2 / 3,
  });
  assert.deepEqual(report.resolutionGate, {
    allRequirementsResolved: false,
    reasonCode: "REALTIME_REQUIREMENTS_MISSING",
  });
  assert.equal(report.claimGate.nationwideRealtimeSupportAllowed, false);
  assert.equal(report.claimGate.reasonCode, "REALTIME_REQUIREMENTS_NOT_ALL_SUPPORTED");
  assert.deepEqual(report.capabilityMetadata.map(({ effectiveCapability }) => effectiveCapability), [
    "REALTIME",
    "PLANNED",
    "UNKNOWN",
  ]);
});

test("지원과 공식 미지원만 있으면 실시간 전국 claim과 별개로 조사는 완결된다", () => {
  const report = buildRealtimeProviderCoverageReport({
    coverageRequirements: [
      {
        regionId: "capital",
        operatorId: "seoul-metro",
        lineId: "seoul-2",
        sourceDomain: "realtime_arrivals",
        state: "SUPPORTED",
        fallback: "NONE",
      },
      {
        regionId: "busan",
        operatorId: "busan-transportation",
        lineId: "busan-1",
        sourceDomain: "realtime_arrivals",
        state: "EXPLICITLY_UNSUPPORTED_WITH_EVIDENCE",
        fallback: "UNSUPPORTED_REGION",
      },
    ],
  });

  assert.deepEqual(report.resolutionGate, {
    allRequirementsResolved: true,
    reasonCode: "ALL_REALTIME_REQUIREMENTS_RESOLVED",
  });
  assert.equal(report.claimGate.nationwideRealtimeSupportAllowed, false);
});

test("중복 requirement와 미지원 REALTIME fallback은 거부한다", () => {
  const requirement = {
    regionId: "capital",
    operatorId: "operator-a",
    lineId: "line-a",
    sourceDomain: "realtime_arrivals",
    state: "SUPPORTED",
    fallback: "NONE",
  };
  assert.throws(
    () => buildRealtimeProviderCoverageReport({
      stationLinePairs: [],
      samples: [],
      coverageRequirements: [requirement, requirement],
    }),
    /duplicate coverage requirement/,
  );
  assert.throws(
    () => buildRealtimeProviderCoverageReport({
      stationLinePairs: [],
      samples: [],
      coverageRequirements: [{
        ...requirement,
        state: "EXPLICITLY_UNSUPPORTED_WITH_EVIDENCE",
        fallback: "REALTIME",
      }],
    }),
    /unsupported coverage requirement must not use REALTIME fallback/,
  );
});
