#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const report = await main(process.argv.slice(2));
    console.log(JSON.stringify(report, null, 2));
    process.exit(report.failures.length > 0 ? 1 : 0);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

async function main(argv) {
  const args = parseArgs(argv);
  const gatePath = args.gate ?? args._[0];
  if (!gatePath) throw new Error("usage: check-route-commercialization-gate.mjs --gate <gate.json> --accuracy <report.json> --accessibility <report.json> --coverage <report.json> --contract <report.json>");

  const gate = await readJson(gatePath);
  const reportPaths = {
    accuracy: args.accuracy ?? gate.requiredReports?.accuracy,
    accessibility: args.accessibility ?? gate.requiredReports?.accessibility,
    coverage: args.coverage ?? gate.requiredReports?.coverage,
    contract: args.contract ?? gate.requiredReports?.contract,
  };
  const failures = validateGate(gate);
  const reports = {};
  for (const [key, reportPath] of Object.entries(reportPaths)) {
    if (!reportPath) {
      failures.push(`missing required report path: ${key}`);
      continue;
    }
    try {
      reports[key] = await readJson(reportPath);
    } catch (error) {
      failures.push(`missing required report: ${key}`);
    }
  }

  if (reports.accuracy) validateAccuracy(gate, reports.accuracy, failures);
  if (reports.accessibility) validateAccessibility(gate, reports.accessibility, failures);
  if (reports.coverage) validateCoverage(gate, reports.coverage, failures);
  if (reports.contract) validateContract(gate, reports.contract, failures);

  return {
    schemaVersion: 1,
    gate: "route-commercialization",
    status: failures.length > 0 ? "FAIL" : "PASS",
    checkedReports: Object.keys(reports).sort((left, right) => left.localeCompare(right)),
    failures,
  };
}

function parseArgs(argv) {
  const parsed = { _: [] };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (!arg.startsWith("--")) {
      parsed._.push(arg);
      continue;
    }
    const key = arg.slice(2);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`missing value for --${key}`);
    parsed[key] = value;
    index += 1;
  }
  return parsed;
}

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

function validateGate(gate) {
  const failures = [];
  if (gate.schemaVersion !== 1) failures.push("gate schemaVersion must be 1");
  if (gate.releaseGate !== "route-commercialization") failures.push("gate releaseGate must be route-commercialization");
  if (gate.releaseBlockerPolicy !== true) failures.push("gate releaseBlockerPolicy must be true");
  for (const key of ["accuracy", "accessibility", "coverage", "contract"]) {
    if (typeof gate.requiredReports?.[key] !== "string") failures.push(`gate requiredReports.${key} must be set`);
  }
  return failures;
}

function validateAccuracy(gate, report, failures) {
  if (report.schemaVersion !== 1) failures.push("route accuracy report schemaVersion must be 1");
  if (Array.isArray(report.failures) && report.failures.length > 0) failures.push("route accuracy report contains failures");

  const sampleSizeMin = gate.routeEtaAccuracy.sampleSizeMin;
  const sources = report.sampleSourceCounts ?? {};
  if (number(report.sampleSize) < sampleSizeMin) failures.push(`routeEtaAccuracy sampleSize is below ${sampleSizeMin}`);
  const commercialSampleSize = Number.isFinite(Number(report.productionSampleSize))
    ? number(report.productionSampleSize)
    : Math.max(0, Math.min(number(sources.realtimeProvider), number(sources.manualObservation)) - number(sources.staleRealtime));
  if (commercialSampleSize < sampleSizeMin) failures.push(`routeEtaAccuracy production sampleSize is below ${sampleSizeMin}`);
  if (number(sources.fixture) >= sampleSizeMin && commercialSampleSize < sampleSizeMin) {
    failures.push("routeEtaAccuracy fixture-only samples cannot satisfy production gate");
  }
  if (number(sources.staleRealtime) > 0) failures.push("routeEtaAccuracy stale realtime samples cannot count as fresh provider samples");

  const singleRide = report.metrics?.singleRide ?? {};
  const transfer = report.metrics?.transfer ?? {};
  max(singleRide.p50ErrorSeconds, gate.routeEtaAccuracy.singleRideP50ErrorSecondsMax, "singleRide P50 ETA error", failures);
  max(singleRide.p90ErrorSeconds, gate.routeEtaAccuracy.singleRideP90ErrorSecondsMax, "singleRide P90 ETA error", failures);
  max(transfer.p50ErrorSeconds, gate.routeEtaAccuracy.transferP50ErrorSecondsMax, "transfer P50 ETA error", failures);
  max(transfer.p90ErrorSeconds, gate.routeEtaAccuracy.transferP90ErrorSecondsMax, "transfer P90 ETA error", failures);
}

function validateAccessibility(gate, report, failures) {
  if (report.schemaVersion !== 1) failures.push("accessibility report schemaVersion must be 1");
  if (number(report.strictStepFreeKnownStairFalsePositiveCount) > gate.accessibility.strictStepFreeKnownStairFalsePositiveAllowed) {
    failures.push("accessibility strict step-free false positive count exceeds 0");
  }
  if (!gate.accessibility.generatedConnectorAsVerifiedAllowed && number(report.generatedConnectorVerifiedAccessibilityCount) > 0) {
    failures.push("accessibility generated connector verified count exceeds 0");
  }
  if (gate.accessibility.unknownAccessibilityMustBeLabeled && report.unknownAccessibilityLabeled !== true) {
    failures.push("accessibility unknown accessibility must be labeled");
  }
}

function validateCoverage(gate, report, failures) {
  if (report.schemaVersion !== 1) failures.push("coverage report schemaVersion must be 1");
  if (number(report.supportedStationLinePairs) < gate.realtimeCoverage.supportedStationLinePairsMin) {
    failures.push(`realtimeCoverage supported station-line pairs below ${gate.realtimeCoverage.supportedStationLinePairsMin}`);
  }
  max(report.providerFreshnessSecondsMaxObserved, gate.realtimeCoverage.providerFreshnessSecondsMax, "realtimeCoverage provider freshness seconds", failures);
  if (gate.realtimeCoverage.staleFallbackRequired && report.staleFallbackRequired !== true) {
    failures.push("realtimeCoverage stale fallback must be required");
  }
}

function validateContract(gate, report, failures) {
  if (report.schemaVersion !== 1) failures.push("route contract report schemaVersion must be 1");
  if (gate.routing.multiTransferSupported && report.multiTransferSupported !== true) failures.push("routing multi-transfer support is required");
  if (gate.routing.outOfStationTransferSupported && report.outOfStationTransferSupported !== true) failures.push("routing out-of-station transfer support is required");
  if (number(report.alternativeItinerariesMinObserved) < gate.routing.alternativeItinerariesMin) {
    failures.push(`routing alternative itineraries below ${gate.routing.alternativeItinerariesMin}`);
  }
  if (number(report.wrongTransferCount) > gate.routeQuality.wrongTransferCountAllowed) failures.push("routeQuality wrong transfer count exceeds 0");
  if (number(report.wrongLineSequence) > gate.routeQuality.wrongLineSequenceAllowed) failures.push("routeQuality wrong line sequence exceeds 0");
  max(report.routeNotFoundRate, gate.routeQuality.routeNotFoundRateMax, "routeQuality route not found rate", failures);
  if (gate.routing.outOfStationTransferSupported) {
    const satisfied = new Set(report.releaseBlockersSatisfied ?? []);
    for (const blocker of gate.outOfStationTransferReleaseBlockers) {
      if (!satisfied.has(blocker)) failures.push(`routing ${blocker} blocker must be satisfied before out-of-station transfer release claim`);
    }
  }
}

function max(actual, limit, label, failures) {
  if (number(actual) > limit) failures.push(`${label} exceeds ${limit}`);
}

function number(value) {
  return Number.isFinite(Number(value)) ? Number(value) : 0;
}
