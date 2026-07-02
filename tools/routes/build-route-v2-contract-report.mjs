#!/usr/bin/env node
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const report = await main(process.argv.slice(2));
    console.log(JSON.stringify(report, null, 2));
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

export async function main(argv) {
  const args = parseArgs(argv);
  if (!args.input) {
    throw new Error("usage: build-route-v2-contract-report.mjs --input <responses.json> [--output <report.json>]");
  }

  const input = JSON.parse(await readFile(args.input, "utf8"));
  const report = buildContractReport(input);
  if (args.output) {
    await mkdir(path.dirname(args.output), { recursive: true });
    await writeFile(args.output, `${JSON.stringify(report, null, 2)}\n`);
  }
  return report;
}

export function buildContractReport(input) {
  if (input.schemaVersion !== 1) throw new Error("route v2 contract input schemaVersion must be 1");
  if (!Array.isArray(input.samples)) throw new Error("route v2 contract input samples must be an array");

  const foundSamples = input.samples.filter((sample) => !isNotFound(sample));
  const foundItineraryCounts = foundSamples.map((sample) => itinerariesOf(sample).length);
  return {
    schemaVersion: 1,
    sampleSize: input.samples.length,
    multiTransferSupported: input.capabilities?.multiTransferSupported === true || hasMultiTransfer(foundSamples),
    outOfStationTransferSupported: input.capabilities?.outOfStationTransferSupported === true
      || hasOutOfStationTransfer(foundSamples),
    alternativeItinerariesMinObserved: foundItineraryCounts.length === 0 ? 0 : Math.min(...foundItineraryCounts),
    wrongTransferCount: wrongTransferCount(foundSamples),
    wrongLineSequence: wrongLineSequence(foundSamples),
    routeNotFoundRate: input.samples.length === 0
      ? 0
      : (input.samples.length - foundSamples.length) / input.samples.length,
    releaseBlockersSatisfied: input.capabilities?.releaseBlockersSatisfied ?? [],
  };
}

function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (!arg.startsWith("--")) throw new Error(`unknown argument: ${arg}`);
    const key = arg.slice(2);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`missing value for --${key}`);
    parsed[key] = value;
    index += 1;
  }
  return parsed;
}

function isNotFound(sample) {
  return sample.notFound === true
    || sample.response?.status === 404
    || sample.response?.data?.status === "ROUTE_NOT_FOUND"
    || itinerariesOf(sample).length === 0;
}

function itinerariesOf(sample) {
  const itineraries = sample.response?.data?.itineraries ?? sample.response?.itineraries;
  if (!Array.isArray(itineraries)) return [];
  return itineraries;
}

function hasMultiTransfer(samples) {
  return samples.some((sample) => itinerariesOf(sample).some((itinerary) => number(itinerary.transferCount) >= 2));
}

function hasOutOfStationTransfer(samples) {
  return samples.some((sample) => itinerariesOf(sample).some((itinerary) => {
    const steps = itinerary.legs ?? itinerary.steps ?? [];
    return steps.some((step) => isOutOfStationTransferStep(step));
  }));
}

function wrongTransferCount(samples) {
  return samples.reduce((count, sample) => {
    const expected = sample.expected?.transferCounts;
    if (!Array.isArray(expected)) return count;
    return count + expected.filter((transferCount, index) => (
      number(itinerariesOf(sample)[index]?.transferCount) !== number(transferCount)
    )).length;
  }, 0);
}

function wrongLineSequence(samples) {
  return samples.reduce((count, sample) => {
    const expected = sample.expected?.lineSequences;
    if (!Array.isArray(expected)) return count;
    return count + expected.filter((lineSequence, index) => {
      const actual = lineSequenceOf(itinerariesOf(sample)[index]);
      return JSON.stringify(actual) !== JSON.stringify(lineSequence);
    }).length;
  }, 0);
}

function lineSequenceOf(itinerary) {
  const steps = itinerary?.legs ?? itinerary?.steps ?? [];
  return steps
    .filter((step) => isRideStep(step))
    .map((step) => step.lineId)
    .filter(Boolean);
}

function isRideStep(step) {
  return ["RIDE", "ride"].includes(step.legType ?? step.type ?? step.stepType);
}

function isOutOfStationTransferStep(step) {
  return ["OUT_OF_STATION_TRANSFER", "out_of_station_transfer"].includes(step.legType ?? step.type ?? step.stepType);
}

function number(value) {
  return Number.isFinite(Number(value)) ? Number(value) : 0;
}
