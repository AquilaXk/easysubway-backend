import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const root = process.cwd();

test("route commercialization gate passes with production-ready reports", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
        offlinePlanned: { sampleSize: 100, p50ErrorSeconds: 50, p90ErrorSeconds: 120 },
        onlineRealtime: { sampleSize: 120, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  const { stdout } = await execChecker(fixture);
  const report = JSON.parse(stdout);

  assert.equal(report.status, "PASS");
  assert.deepEqual(report.failures, []);
});

test("route commercialization gate fails closed when strict step-free false positive count is missing", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
        offlinePlanned: { sampleSize: 100, p50ErrorSeconds: 50, p90ErrorSeconds: 120 },
        onlineRealtime: { sampleSize: 120, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("accessibility strict step-free false positive count must be reported"));
      return true;
    },
  );
});

test("route commercialization gate fails closed for fixture-only or unsafe route reports", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 99,
      sampleSourceCounts: {
        fixture: 100,
        staticTimetable: 0,
        realtimeProvider: 0,
        manualObservation: 0,
        staleRealtime: 0,
      },
      productionSampleSize: 0,
      metrics: {
        singleRide: { sampleSize: 50, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 50, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 1,
      generatedConnectorVerifiedAccessibilityCount: 1,
      unknownAccessibilityLabeled: false,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 50,
      providerFreshnessSecondsMaxObserved: 120,
      staleFallbackRequired: false,
      freshness: { staleAsFreshCount: 1 },
      mapping: { failureRate: 0.1 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 1,
      strictRouteNotFound: {
        total: 100,
        notFoundCount: 5,
        rate: 0.05,
        byReasonCode: { GENERATED_CONNECTOR_UNVERIFIED: 5 },
      },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 1,
      wrongTransferCount: 1,
      wrongLineSequence: 1,
      routeNotFoundRate: 0.05,
      releaseBlockersSatisfied: ["D-2"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("routeEtaAccuracy sampleSize is below 100"));
      assert.ok(report.failures.includes("routeEtaAccuracy production sampleSize is below 100"));
      assert.ok(report.failures.includes("accessibility strict step-free false positive count exceeds 0"));
      assert.ok(report.failures.includes("accessibility generated connector verified count exceeds 0"));
      assert.ok(report.failures.includes("realtimeCoverage stale-as-fresh count exceeds 0"));
      assert.ok(report.failures.includes("route graph generated connector verified count exceeds 0"));
      assert.ok(report.failures.includes("route graph strict route not found rate exceeds 0.02"));
      assert.ok(report.failures.includes("routing alternative itineraries below 2"));
      assert.ok(!report.failures.includes("routing D-3 blocker must be satisfied before out-of-station transfer release claim"));
      return true;
    },
  );
});

test("route commercialization gate keeps legacy production sample fallback", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 100,
        staleRealtime: 20,
      },
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
        offlinePlanned: { sampleSize: 100, p50ErrorSeconds: 50, p90ErrorSeconds: 120 },
        onlineRealtime: { sampleSize: 120, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: true,
      outOfStationTransferSupported: true,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("routeEtaAccuracy production sampleSize is below 100"));
      assert.ok(report.failures.includes("routeEtaAccuracy stale realtime samples cannot count as fresh provider samples"));
      return true;
    },
  );
});

test("route commercialization gate derives realtime pair minimum from coverage scope", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
        offlinePlanned: { sampleSize: 100, p50ErrorSeconds: 50, p90ErrorSeconds: 120 },
        onlineRealtime: { sampleSize: 120, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      scope: {
        id: "capital_pilot_android_v1",
        supportedStationLinePairsMin: 2,
      },
      supportedStationLinePairs: 2,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  const { stdout } = await execChecker(fixture);
  const report = JSON.parse(stdout);

  assert.equal(report.status, "PASS");
  assert.deepEqual(report.failures, []);
});

test("route commercialization gate fails on unclassified ETA deviations", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
        unclassifiedEtaDeviationCount: 1,
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("routeEtaAccuracy unclassified ETA deviation count exceeds 0"));
      return true;
    },
  );
});

test("route commercialization gate requires explicit ETA source buckets", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      actualEtaSourceCounts: null,
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("routeEtaAccuracy actual ETA source counts must be reported"));
      return true;
    },
  );
});

test("route commercialization gate requires static, planned, realtime, and fallback source labels", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      actualEtaSourceCounts: {
        REALTIME: 120,
        PLANNED: 0,
        STATIC_LOCAL: 0,
        FALLBACK: 0,
      },
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("routeEtaAccuracy actual ETA source count missing: STATIC_BACKEND_ESTIMATE"));
      return true;
    },
  );
});

test("route commercialization gate requires offline planned and online realtime metrics", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
        offlinePlanned: { sampleSize: 0, p50ErrorSeconds: 0, p90ErrorSeconds: 0 },
        onlineRealtime: { sampleSize: 0, p50ErrorSeconds: 0, p90ErrorSeconds: 0 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("routeEtaAccuracy offline PLANNED sampleSize is below 100"));
      assert.ok(report.failures.includes("routeEtaAccuracy online REALTIME sampleSize is below 100"));
      return true;
    },
  );
});

test("route commercialization gate requires runtime traceability summary", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      actualEtaSourceCounts: {
        REALTIME: 120,
        PLANNED: 0,
        STATIC_BACKEND_ESTIMATE: 0,
        STATIC_LOCAL: 0,
        FALLBACK: 0,
      },
      productionSampleSize: 120,
      runtimeTraceability: {
        productionRowCount: 119,
        tracedProductionRowCount: 118,
        missingRequiredFieldCount: 1,
        realtimeAnchorMissingCount: 1,
        stratificationMissingCount: 1,
        unclassifiedBudgetExceededCount: 1,
      },
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failureRate: 0 },
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("routeEtaAccuracy runtime traceability production row count must match production sampleSize"));
      assert.ok(report.failures.includes("routeEtaAccuracy runtime traceability required fields missing"));
      assert.ok(report.failures.includes("routeEtaAccuracy realtime runtime anchor fields missing"));
      assert.ok(report.failures.includes("routeEtaAccuracy runtime stratification fields missing"));
      assert.ok(report.failures.includes("routeEtaAccuracy runtime budget exceeded rows must include deviation reason code"));
      return true;
    },
  );
});

test("route commercialization gate requires realtime coverage provider and region reason breakdowns", async () => {
  const fixture = await writeFixtureSet({
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
      freshness: { staleAsFreshCount: 0 },
      mapping: { failedRows: 1, failureRate: 0.1, failuresByReason: { UNKNOWN: 1 } },
      byProvider: [],
      byRegion: [{ region: "capital", supportedStationLinePairs: 150, mappingFailedRows: 1, staleCount: 0, mappingFailuresByReason: { UNKNOWN: 1 } }],
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: false,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("realtimeCoverage mapping failures must not use UNKNOWN reason code"));
      assert.ok(report.failures.includes("realtimeCoverage provider breakdown must be reported"));
      assert.ok(report.failures.includes("realtimeCoverage region mapping failures must not use UNKNOWN reason code"));
      return true;
    },
  );
});

test("route commercialization gate requires out-of-station allowlist evidence", async () => {
  const fixture = await writeFixtureSet({
    gateOverride: {
      routing: {
        outOfStationTransferSupported: true,
        outOfStationTransferAllowlistSource: "catalog_metadata:route.outOfStationTransfer.allowlist",
      },
    },
    accuracy: {
      schemaVersion: 1,
      sampleSize: 120,
      sampleSourceCounts: {
        fixture: 0,
        staticTimetable: 0,
        realtimeProvider: 120,
        manualObservation: 120,
        staleRealtime: 0,
      },
      productionSampleSize: 120,
      metrics: {
        singleRide: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
        transfer: { sampleSize: 60, p50ErrorSeconds: 90, p90ErrorSeconds: 240 },
      },
      failures: [],
    },
    accessibility: {
      schemaVersion: 1,
      strictStepFreeKnownStairFalsePositiveCount: 0,
      generatedConnectorVerifiedAccessibilityCount: 0,
      unknownAccessibilityLabeled: true,
    },
    coverage: {
      schemaVersion: 1,
      supportedStationLinePairs: 150,
      providerFreshnessSecondsMaxObserved: 80,
      staleFallbackRequired: true,
    },
    routeGraphCoverage: {
      schemaVersion: 1,
      generatedConnectorVerifiedAccessibilityCount: 0,
      strictRouteNotFound: { total: 100, notFoundCount: 1, rate: 0.01, byReasonCode: {} },
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: true,
      alternativeItinerariesMinObserved: 2,
      wrongTransferCount: 0,
      wrongLineSequence: 0,
      routeNotFoundRate: 0.01,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
  });

  await assert.rejects(
    execChecker(fixture),
    (error) => {
      const report = JSON.parse(error.stdout);
      assert.equal(report.status, "FAIL");
      assert.ok(report.failures.includes("routing out-of-station transfer allowlist source mismatch"));
      assert.ok(report.failures.includes("routing out-of-station transfer allowlist must contain at least one pair"));
      return true;
    },
  );
});

test("route commercialization gate sorts checked reports with an explicit comparator", async () => {
  const source = await readFile("tools/routes/check-route-commercialization-gate.mjs", "utf8");

  assert.match(source, /Object\.keys\(reports\)\.sort\(\([^)]*\) => [^)]*localeCompare/);
});

async function writeFixtureSet(reports) {
  const dir = await mkdtemp(path.join(tmpdir(), "route-commercialization-gate-"));
  const gatePath = path.join(dir, "route-commercialization-gate.json");
  const gate = JSON.parse(await readFile("apps/mobile/release/route-commercialization-gate.json", "utf8"));
  const gateOverride = reports.gateOverride ?? {};
  await writeFile(gatePath, `${JSON.stringify({
    ...gate,
    ...gateOverride,
    routing: {
      ...gate.routing,
      ...(gateOverride.routing ?? {}),
    },
  }, null, 2)}\n`);
  const files = {
    gate: gatePath,
    accuracy: path.join(dir, "route-accuracy-report.json"),
    accessibility: path.join(dir, "route-accessibility-regression-report.json"),
    coverage: path.join(dir, "realtime-provider-coverage-report.json"),
    routeGraphCoverage: path.join(dir, "route-graph-coverage-report.json"),
    contract: path.join(dir, "route-v2-contract-report.json"),
  };
  const normalizedReports = {
    ...reports,
    accuracy: normalizeAccuracyReport(reports.accuracy),
    coverage: normalizeCoverageReport(reports.coverage),
  };
  await Promise.all(Object.entries(normalizedReports)
    .filter(([key]) => key !== "gateOverride")
    .map(([key, report]) => writeFile(files[key], `${JSON.stringify(report, null, 2)}\n`)));
  return files;
}

function normalizeAccuracyReport(report) {
  const productionSampleSize = Number.isFinite(Number(report.productionSampleSize))
    ? Number(report.productionSampleSize)
    : 0;
  const normalized = {
    ...report,
    runtimeTraceability: report.runtimeTraceability ?? {
      productionRowCount: productionSampleSize,
      tracedProductionRowCount: productionSampleSize,
      missingRequiredFieldCount: 0,
      realtimeAnchorMissingCount: 0,
      stratificationMissingCount: 0,
      unclassifiedBudgetExceededCount: 0,
    },
    metrics: {
      offlinePlanned: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
      onlineRealtime: { sampleSize: 60, p50ErrorSeconds: 45, p90ErrorSeconds: 100 },
      ...report.metrics,
    },
  };
  if (Object.hasOwn(report, "actualEtaSourceCounts")) return normalized;
  return {
    ...normalized,
    actualEtaSourceCounts: {
      REALTIME: 0,
      PLANNED: 0,
      STATIC_BACKEND_ESTIMATE: 0,
      STATIC_LOCAL: 0,
      FALLBACK: 0,
    },
  };
}

function normalizeCoverageReport(report) {
  return {
    ...report,
    freshness: {
      staleCount: 0,
      ...report.freshness,
    },
    mapping: {
      failedRows: 0,
      failuresByReason: {},
      ...report.mapping,
    },
    byProvider: report.byProvider ?? [
      {
        providerId: "seoul-topis",
        supportedStationLinePairs: report.supportedStationLinePairs ?? 0,
        mappingFailedRows: report.mapping?.failedRows ?? 0,
        mappingFailuresByReason: report.mapping?.failuresByReason ?? {},
        staleCount: report.freshness?.staleCount ?? 0,
      },
    ],
    byRegion: report.byRegion ?? [
      {
        region: "capital",
        supportedStationLinePairs: report.supportedStationLinePairs ?? 0,
        mappingFailedRows: report.mapping?.failedRows ?? 0,
        mappingFailuresByReason: report.mapping?.failuresByReason ?? {},
        staleCount: report.freshness?.staleCount ?? 0,
      },
    ],
  };
}

function execChecker(files) {
  return execFileAsync(process.execPath, [
    "tools/routes/check-route-commercialization-gate.mjs",
    "--gate",
    files.gate,
    "--accuracy",
    files.accuracy,
    "--accessibility",
    files.accessibility,
    "--coverage",
    files.coverage,
    "--routeGraphCoverage",
    files.routeGraphCoverage,
    "--contract",
    files.contract,
  ], { cwd: root });
}
