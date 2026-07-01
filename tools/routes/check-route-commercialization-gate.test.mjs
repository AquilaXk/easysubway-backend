import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, writeFile } from "node:fs/promises";
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

  const { stdout } = await execChecker(fixture);
  const report = JSON.parse(stdout);

  assert.equal(report.status, "PASS");
  assert.deepEqual(report.failures, []);
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
    },
    contract: {
      schemaVersion: 1,
      multiTransferSupported: false,
      outOfStationTransferSupported: true,
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
      assert.ok(report.failures.includes("routing D-3 blocker must be satisfied before out-of-station transfer release claim"));
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

async function writeFixtureSet(reports) {
  const dir = await mkdtemp(path.join(tmpdir(), "route-commercialization-gate-"));
  const files = {
    gate: path.join(root, "apps/mobile/release/route-commercialization-gate.json"),
    accuracy: path.join(dir, "route-accuracy-report.json"),
    accessibility: path.join(dir, "route-accessibility-regression-report.json"),
    coverage: path.join(dir, "realtime-provider-coverage-report.json"),
    contract: path.join(dir, "route-v2-contract-report.json"),
  };
  await Promise.all(Object.entries(reports).map(([key, report]) => writeFile(files[key], `${JSON.stringify(report, null, 2)}\n`)));
  return files;
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
    "--contract",
    files.contract,
  ], { cwd: root });
}
