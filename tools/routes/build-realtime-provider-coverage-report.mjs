#!/usr/bin/env node
import { readFile, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}

export async function main(argv) {
  const args = parseArgs(argv);
  if (!args.input) throw new Error("usage: build-realtime-provider-coverage-report.mjs --input <coverage-input.json> [--output <report.json>]");
  const input = JSON.parse(await readFile(args.input, "utf8"));
  const report = buildRealtimeProviderCoverageReport(input);
  const json = `${JSON.stringify(report, null, 2)}\n`;
  if (args.output) {
    await writeFile(args.output, json);
  } else {
    process.stdout.write(json);
  }
  return report;
}

export function buildRealtimeProviderCoverageReport(input) {
  const pairs = Array.isArray(input.stationLinePairs) ? input.stationLinePairs : [];
  const samples = Array.isArray(input.samples) ? input.samples : [];
  const coverageRequirements = normalizeCoverageRequirements(input.coverageRequirements);
  const mappedPairs = pairs.filter((row) => row.supportsArrivals === true && row.mappingStatus === "MAPPED");
  const mappingFailures = pairs.filter((row) => row.mappingStatus !== "MAPPED");
  const realtimeSamples = samples.filter((row) => row.labeledAsRealtime === true);
  const staleAsFresh = realtimeSamples.filter((row) => row.freshnessStatus !== "FRESH");
  const providerIds = uniqueKeys([...pairs, ...samples], "providerId");
  const regions = uniqueKeys([...pairs, ...samples], "region");
  const supportedCount = coverageRequirements.filter(({ state }) => state === "SUPPORTED").length;
  const explicitlyUnsupportedCount = coverageRequirements
    .filter(({ state }) => state === "EXPLICITLY_UNSUPPORTED_WITH_EVIDENCE").length;
  const missingCount = coverageRequirements.filter(({ state }) => state === "MISSING").length;
  const requirementCount = coverageRequirements.length;
  const nationwideRealtimeSupportAllowed = requirementCount > 0 && supportedCount === requirementCount;

  return {
    schemaVersion: 1,
    scope: input.scope ?? {},
    supportedStationLinePairs: uniquePairCount(mappedPairs),
    providerFreshnessSecondsMaxObserved: max(realtimeSamples.map((row) => row.providerFreshnessSeconds)),
    staleFallbackRequired: input.staleFallbackRequired === true,
    freshness: {
      freshCount: samples.filter((row) => row.freshnessStatus === "FRESH").length,
      staleCount: samples.filter((row) => row.freshnessStatus === "STALE").length,
      staleAsFreshCount: staleAsFresh.length,
    },
    mapping: {
      attemptedRows: pairs.length,
      failedRows: mappingFailures.length,
      failureRate: ratio(mappingFailures.length, pairs.length),
      failuresByReason: countBy(mappingFailures, "failureReason"),
    },
    byProvider: providerIds.map((providerId) => aggregateScope(providerId, "providerId", pairs, samples)),
    byRegion: regions.map((region) => aggregateScope(region, "region", pairs, samples)),
    unsupportedRegions: Array.isArray(input.unsupportedRegions) ? input.unsupportedRegions : [],
    coverageResolution: {
      requirementCount,
      supportedCount,
      explicitlyUnsupportedCount,
      missingCount,
      supportedRatio: ratio(supportedCount, requirementCount),
      terminalResolutionRatio: ratio(supportedCount + explicitlyUnsupportedCount, requirementCount),
    },
    capabilityMetadata: coverageRequirements.map((requirement) => ({
      regionId: requirement.regionId,
      operatorId: requirement.operatorId,
      lineId: requirement.lineId,
      sourceDomain: requirement.sourceDomain,
      state: requirement.state,
      fallback: requirement.fallback,
      effectiveCapability: requirement.state === "SUPPORTED"
        ? "REALTIME"
        : requirement.state === "EXPLICITLY_UNSUPPORTED_WITH_EVIDENCE"
          ? requirement.fallback
          : "UNKNOWN",
      ...(requirement.userMessageKo ? { userMessageKo: requirement.userMessageKo } : {}),
    })),
    claimGate: {
      nationwideRealtimeSupportAllowed,
      reasonCode: nationwideRealtimeSupportAllowed
        ? "ALL_REALTIME_REQUIREMENTS_SUPPORTED"
        : requirementCount === 0
          ? "NO_REALTIME_REQUIREMENTS"
          : "REALTIME_REQUIREMENTS_NOT_ALL_SUPPORTED",
    },
  };
}

function normalizeCoverageRequirements(value) {
  if (value === undefined) return [];
  if (!Array.isArray(value)) throw new Error("coverageRequirements must be an array");
  const states = new Set(["SUPPORTED", "EXPLICITLY_UNSUPPORTED_WITH_EVIDENCE", "MISSING"]);
  const seen = new Set();
  return value.map((requirement, index) => {
    if (!requirement || typeof requirement !== "object" || Array.isArray(requirement)) {
      throw new Error(`coverageRequirements[${index}] must be an object`);
    }
    const normalized = {};
    for (const field of ["regionId", "operatorId", "lineId", "sourceDomain", "state", "fallback"]) {
      normalized[field] = requiredString(requirement[field], `coverageRequirements[${index}].${field}`);
    }
    if (normalized.sourceDomain !== "realtime_arrivals") {
      throw new Error("realtime coverage report only accepts realtime_arrivals requirements");
    }
    if (!states.has(normalized.state)) throw new Error(`coverageRequirements[${index}].state is invalid`);
    if (normalized.state === "EXPLICITLY_UNSUPPORTED_WITH_EVIDENCE" && normalized.fallback === "REALTIME") {
      throw new Error("unsupported coverage requirement must not use REALTIME fallback");
    }
    const key = [normalized.regionId, normalized.operatorId, normalized.lineId, normalized.sourceDomain].join(":");
    if (seen.has(key)) throw new Error(`duplicate coverage requirement: ${key}`);
    seen.add(key);
    if (typeof requirement.userMessageKo === "string" && requirement.userMessageKo.trim() !== "") {
      normalized.userMessageKo = requirement.userMessageKo;
    }
    return normalized;
  });
}

function requiredString(value, label) {
  if (typeof value !== "string" || value.trim() === "") throw new Error(`${label} is required`);
  return value;
}

function uniqueKeys(rows, field) {
  return [...new Set(rows.map((row) => row[field]).filter((value) => value != null && value !== ""))]
    .sort((left, right) => `${left}`.localeCompare(`${right}`));
}

function aggregateScope(key, field, pairs, samples) {
  const scopedPairs = pairs.filter((row) => row[field] === key);
  const scopedSamples = samples.filter((row) => row[field] === key);
  const mappedPairs = scopedPairs.filter((row) => row.supportsArrivals === true && row.mappingStatus === "MAPPED");
  const mappingFailures = scopedPairs.filter((row) => row.mappingStatus !== "MAPPED");
  return {
    [field]: key,
    supportedStationLinePairs: uniquePairCount(mappedPairs),
    mappingFailedRows: mappingFailures.length,
    mappingFailuresByReason: countBy(mappingFailures, "failureReason"),
    freshCount: scopedSamples.filter((row) => row.freshnessStatus === "FRESH").length,
    staleCount: scopedSamples.filter((row) => row.freshnessStatus === "STALE").length,
  };
}

function uniquePairCount(rows) {
  return new Set(rows.map((row) => `${row.stationId}\0${row.lineId}`)).size;
}

function countBy(rows, field) {
  const counts = {};
  for (const row of rows) {
    const key = row[field] ?? "UNKNOWN";
    counts[key] = (counts[key] ?? 0) + 1;
  }
  return counts;
}

function max(values) {
  const finite = values.map(Number).filter(Number.isFinite);
  return finite.length === 0 ? 0 : Math.max(...finite);
}

function ratio(count, total) {
  return total === 0 ? 0 : count / total;
}

function parseArgs(argv) {
  const pairs = [];
  while (argv.length > 0) {
    const [key, value] = argv.splice(0, 2);
    if (!key?.startsWith("--")) throw new Error(`unexpected argument: ${key}`);
    if (!value || value.startsWith("--")) throw new Error(`missing value for ${key}`);
    pairs.push([key.slice(2), value]);
  }
  return Object.fromEntries(pairs);
}
