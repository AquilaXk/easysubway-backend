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
  const mappedPairs = pairs.filter((row) => row.supportsArrivals === true && row.mappingStatus === "MAPPED");
  const mappingFailures = pairs.filter((row) => row.mappingStatus !== "MAPPED");
  const realtimeSamples = samples.filter((row) => row.labeledAsRealtime === true);
  const staleAsFresh = realtimeSamples.filter((row) => row.freshnessStatus !== "FRESH");
  const providerIds = uniqueKeys([...pairs, ...samples], "providerId");
  const regions = uniqueKeys([...pairs, ...samples], "region");

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
  };
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
