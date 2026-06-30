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
  for (const row of rows) {
    const observed = requiredNumber(row.observed_eta_error_seconds, row, "observed_eta_error_seconds", failures);
    const max = requiredNumber(row.max_eta_error_seconds, row, "max_eta_error_seconds", failures);
    if (observed === null || max === null) continue;
    if (observed > max) failures.push(`${row.sourceFile}:${row.lineNumber} observed ETA error exceeds max`);
    errors.push(observed);
  }
  errors.sort((left, right) => left - right);
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
    coverage,
    metrics: {
      sampleSize: errors.length,
      p50ErrorSeconds: percentile(errors, 0.5),
      p90ErrorSeconds: percentile(errors, 0.9),
      maxErrorSeconds: errors.at(-1) ?? 0,
    },
    failures,
  };
}

function requiredNumber(value, row, column, failures) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    failures.push(`${row.sourceFile}:${row.lineNumber} invalid number: ${column}`);
    return null;
  }
  return number;
}

function percentile(sortedValues, percentileValue) {
  if (sortedValues.length === 0) return 0;
  const index = Math.max(0, Math.ceil(sortedValues.length * percentileValue) - 1);
  return sortedValues[index];
}
