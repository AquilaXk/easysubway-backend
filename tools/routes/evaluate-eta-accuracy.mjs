#!/usr/bin/env node
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const requiredFiles = new Map([
  ["single_ride_cases.csv", "singleRide"],
  ["one_transfer_cases.csv", "oneTransfer"],
  ["two_transfer_cases.csv", "twoTransfer"],
  ["express_cases.csv", "express"],
  ["out_of_station_transfer_cases.csv", "outOfStationTransfer"],
  ["strict_step_free_cases.csv", "strictStepFree"],
]);
const requiredColumns = [
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
];
const referenceSources = [
  "PROVIDER_LIVE_ARRIVAL",
  "STATIC_TIMETABLE_REFERENCE",
  "MANUAL_OBSERVATION",
  "COMPETING_APP_COMPARISON",
  "FIXTURE_EXPECTED",
];

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const dataset = args.dataset ?? "tools/routes/golden-od";
  const output = args.output ?? "artifacts/route-accuracy-report.json";
  const rows = await readDataset(dataset);
  const report = buildReport(rows);

  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, `${JSON.stringify(report, null, 2)}\n`);
  if (report.failures.length > 0) {
    process.exit(1);
  }
}

function parseArgs(args) {
  const parsed = {};
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index]?.replace(/^--/, "");
    const value = args[index + 1];
    if (!key || !value) throw new Error("usage: evaluate-eta-accuracy.mjs --dataset <dir> --output <file>");
    parsed[key] = value;
  }
  return parsed;
}

async function readDataset(datasetDir) {
  const files = new Set(await readdir(datasetDir));
  const rows = [];
  for (const [file, category] of requiredFiles) {
    if (!files.has(file)) throw new Error(`missing golden OD file: ${file}`);
    const csvRows = parseCsv(await readFile(path.join(datasetDir, file), "utf8"));
    for (const row of csvRows) {
      rows.push({ ...row, category, sourceFile: file });
    }
  }
  return rows;
}

function parseCsv(source) {
  const lines = source.trim().split(/\r?\n/);
  const header = splitCsvLine(lines.shift() ?? "");
  for (const column of requiredColumns) {
    if (!header.includes(column)) throw new Error(`golden OD CSV missing column: ${column}`);
  }
  return lines.map((line, index) => ({ line, lineNumber: index + 2 })).filter(({ line }) => line).map(({ line, lineNumber }) => {
    const values = splitCsvLine(line);
    return {
      ...Object.fromEntries(header.map((column, index) => [column, values[index] ?? ""])),
      lineNumber,
    };
  });
}

export function splitCsvLine(line) {
  const values = [];
  let value = "";
  let quoted = false;
  let index = 0;
  while (index < line.length) {
    const char = line[index];
    if (char === "\"") {
      if (quoted && line[index + 1] === "\"") {
        value += "\"";
        index += 1;
      } else {
        quoted = !quoted;
      }
    } else if (char === "," && !quoted) {
      values.push(value);
      value = "";
    } else {
      value += char;
    }
    index += 1;
  }
  values.push(value);
  return values;
}

function buildReport(rows) {
  const failures = [];
  const errors = [];
  const singleRideErrors = [];
  const transferErrors = [];
  const quality = {
    routeNotFound: 0,
    wrongTransferCount: 0,
    wrongLineSequence: 0,
    strictStepFreeFalsePositive: 0,
    etaSourceMismatch: 0,
    realtimeFallbackMismatch: 0,
    providerStaleMisuse: 0,
  };
  for (const row of rows) {
    collectQualityFailures(row, failures, quality);
    const observed = requiredNumber(row.observed_eta_error_seconds, row, "observed_eta_error_seconds", failures);
    const max = requiredNumber(row.max_eta_error_seconds, row, "max_eta_error_seconds", failures);
    if (observed === null || max === null) continue;
    if (observed > max) {
      addFailure(failures, row, "ETA_ERROR", "observed ETA error exceeds max", { maxEtaErrorSeconds: max }, { observedEtaErrorSeconds: observed });
    }
    errors.push(observed);
    if (Number(row.expected_transfer_count) === 0) {
      singleRideErrors.push(observed);
    } else {
      transferErrors.push(observed);
    }
  }
  errors.sort((left, right) => left - right);
  singleRideErrors.sort((left, right) => left - right);
  transferErrors.sort((left, right) => left - right);
  const coverage = {
    singleRide: rows.some((row) => row.category === "singleRide"),
    oneTransfer: rows.some((row) => row.category === "oneTransfer"),
    twoTransfer: rows.some((row) => row.category === "twoTransfer"),
    express: rows.some((row) => row.category === "express"),
    outOfStationTransfer: rows.some((row) => row.category === "outOfStationTransfer"),
    strictStepFree: rows.some((row) => row.category === "strictStepFree"),
    realtimeSupported: rows.some((row) => row.use_realtime === "true"),
    realtimeUnsupported: rows.some((row) => row.use_realtime === "false"),
  };
  for (const [key, covered] of Object.entries(coverage)) {
    if (!covered) failures.push(`missing required coverage: ${key}`);
  }
  if (rows.length < 100) failures.push("golden OD sampleSize must be at least 100");

  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    sampleSize: rows.length,
    sampleSourceCounts: countSampleSources(rows),
    referenceSourceCounts: countReferenceSources(rows),
    productionSampleSize: rows.filter(isProductionSample).length,
    nonProductionSampleSize: rows.filter((row) => !isProductionSample(row)).length,
    coverage,
    metrics: {
      sampleSize: errors.length,
      p50ErrorSeconds: percentile(errors, 0.5),
      p90ErrorSeconds: percentile(errors, 0.9),
      maxErrorSeconds: errors.at(-1) ?? 0,
      singleRide: metricBlock(singleRideErrors),
      transfer: metricBlock(transferErrors),
      routeNotFoundRate: rate(quality.routeNotFound, rows.length),
      wrongTransferCountRate: rate(quality.wrongTransferCount, rows.length),
      wrongLineSequenceRate: rate(quality.wrongLineSequence, rows.length),
      strictStepFreeFalsePositiveCount: quality.strictStepFreeFalsePositive,
      etaSourceMismatchCount: quality.etaSourceMismatch,
      realtimeFallbackMismatchCount: quality.realtimeFallbackMismatch,
      providerStaleMisuseCount: quality.providerStaleMisuse,
    },
    failures,
  };
}

function countSampleSources(rows) {
  const counts = {
    fixture: 0,
    staticTimetable: 0,
    realtimeProvider: 0,
    manualObservation: 0,
    staleRealtime: 0,
  };
  for (const row of rows) {
    const note = row.notes.toLowerCase();
    if (note.includes("fixture")) {
      counts.fixture += 1;
      continue;
    }
    if (note.includes("manual")) counts.manualObservation += 1;
    if (note.includes("stale")) counts.staleRealtime += 1;
    if (row.use_realtime === "true") {
      counts.realtimeProvider += 1;
    } else {
      counts.staticTimetable += 1;
    }
  }
  return counts;
}

function countReferenceSources(rows) {
  const counts = Object.fromEntries(referenceSources.map((source) => [source, 0]));
  for (const row of rows) {
    counts[referenceSource(row)] += 1;
  }
  return counts;
}

function referenceSource(row) {
  if (referenceSources.includes(row.reference_source)) return row.reference_source;
  const note = row.notes.toLowerCase();
  if (note.includes("fixture")) return "FIXTURE_EXPECTED";
  if (note.includes("manual")) return "MANUAL_OBSERVATION";
  if (row.use_realtime === "true") return "PROVIDER_LIVE_ARRIVAL";
  return "STATIC_TIMETABLE_REFERENCE";
}

function isProductionSample(row) {
  const source = referenceSource(row);
  if (source === "MANUAL_OBSERVATION" || source === "COMPETING_APP_COMPARISON") return true;
  return source === "PROVIDER_LIVE_ARRIVAL" && !isStale(row);
}

function collectQualityFailures(row, failures, quality) {
  if (row.actual_route_found === "false") {
    quality.routeNotFound += 1;
    addFailure(failures, row, "ROUTE_NOT_FOUND", "route was not found", { found: true }, { found: false });
  }
  if (hasValue(row.actual_transfer_count) && Number(row.actual_transfer_count) !== Number(row.expected_transfer_count)) {
    quality.wrongTransferCount += 1;
    addFailure(failures, row, "WRONG_TRANSFER_COUNT", "wrong transfer count", { transferCount: Number(row.expected_transfer_count) }, { transferCount: Number(row.actual_transfer_count) });
  }
  if (hasValue(row.expected_line_sequence) && hasValue(row.actual_line_sequence) && row.expected_line_sequence !== row.actual_line_sequence) {
    quality.wrongLineSequence += 1;
    addFailure(failures, row, "WRONG_LINE_SEQUENCE", "wrong line sequence", { lineSequence: row.expected_line_sequence }, { lineSequence: row.actual_line_sequence });
  }
  if (isStrictStepFree(row) && containsUnsafeStrictStepFreeEdge(row.actual_edge_types)) {
    quality.strictStepFreeFalsePositive += 1;
    addFailure(failures, row, "ACCESSIBILITY_FALSE_POSITIVE", "strict step-free route contains unsafe edge", { stepFree: true }, { edgeTypes: row.actual_edge_types });
  }
  if (hasValue(row.expected_eta_source) && hasValue(row.actual_eta_source) && row.expected_eta_source !== row.actual_eta_source) {
    quality.etaSourceMismatch += 1;
    addFailure(failures, row, "ETA_SOURCE_MISMATCH", "ETA source mismatch", { etaSource: row.expected_eta_source }, { etaSource: row.actual_eta_source });
  }
  if (row.realtime_expected === "true" && hasValue(row.actual_eta_source) && row.actual_eta_source !== "REALTIME") {
    quality.realtimeFallbackMismatch += 1;
    addFailure(failures, row, "REALTIME_FALLBACK_MISMATCH", "realtime expected but actual ETA is not realtime", { etaSource: "REALTIME" }, { etaSource: row.actual_eta_source });
  }
  if (row.actual_eta_source === "REALTIME" && isStale(row)) {
    quality.providerStaleMisuse += 1;
    addFailure(failures, row, "PROVIDER_STALE_MISUSE", "stale provider sample used as realtime ETA", { providerFreshnessStatus: "FRESH" }, { providerFreshnessStatus: row.provider_freshness_status || "STALE" });
  }
}

function isStrictStepFree(row) {
  return row.constraint_mode === "STRICT_STEP_FREE" || row.strict_step_free_expected_status === "STEP_FREE";
}

function containsUnsafeStrictStepFreeEdge(edgeTypes = "") {
  const unsafeTypes = new Set([
    "STAIR",
    "ESCALATOR",
    "ESCALATOR_ONLY",
    "UNKNOWN",
    "UNKNOWN_EDGE",
    "UNKNOWN_EXIT",
    "UNKNOWN_FACILITY",
    "UNVERIFIED",
    "UNVERIFIED_EDGE",
    "UNVERIFIED_EXIT",
    "UNVERIFIED_FACILITY",
  ]);
  return edgeTypes.split("|").some((type) => unsafeTypes.has(type));
}

function isStale(row) {
  return row.provider_freshness_status === "STALE" || row.notes.toLowerCase().includes("stale");
}

function hasValue(value) {
  return value !== undefined && value !== "";
}

function metricBlock(sortedErrors) {
  return {
    sampleSize: sortedErrors.length,
    p50ErrorSeconds: percentile(sortedErrors, 0.5),
    p90ErrorSeconds: percentile(sortedErrors, 0.9),
    maxErrorSeconds: sortedErrors.at(-1) ?? 0,
  };
}

function requiredNumber(value, row, column, failures) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    addFailure(failures, row, "INVALID_NUMBER", `invalid number: ${column}`, { numeric: true }, { value });
    return null;
  }
  return number;
}

function addFailure(failures, row, type, message, expected, actual) {
  failures.push({
    caseId: row.case_id || `${row.sourceFile}:${row.lineNumber}`,
    type,
    message,
    sourceFile: row.sourceFile,
    lineNumber: row.lineNumber,
    expected,
    actual,
  });
}

function rate(count, total) {
  return total === 0 ? 0 : count / total;
}

function percentile(sortedValues, percentileValue) {
  if (sortedValues.length === 0) return 0;
  const index = Math.max(0, Math.ceil(sortedValues.length * percentileValue) - 1);
  return sortedValues[index];
}
