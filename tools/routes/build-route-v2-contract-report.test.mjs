import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { buildContractReport, main } from "./build-route-v2-contract-report.mjs";

test("builds route v2 contract report from runtime responses", () => {
  const report = buildContractReport({
    schemaVersion: 1,
    capabilities: {
      multiTransferSupported: true,
      outOfStationTransferSupported: true,
      releaseBlockersSatisfied: ["D-2", "D-3", "H-1"],
    },
    samples: [
      {
        id: "pareto-runtime",
        response: routeResponse([
          itinerary(2, ["line-a", "line-b", "line-c"]),
          itinerary(1, ["line-a", "line-direct"]),
        ]),
        expected: {
          transferCounts: [2, 1],
          lineSequences: [
            ["line-a", "line-b", "line-c"],
            ["line-a", "line-direct"],
          ],
        },
      },
    ],
  });

  assert.equal(report.schemaVersion, 1);
  assert.equal(report.sampleSize, 1);
  assert.equal(report.multiTransferSupported, true);
  assert.equal(report.outOfStationTransferSupported, true);
  assert.equal(report.alternativeItinerariesMinObserved, 2);
  assert.equal(report.wrongTransferCount, 0);
  assert.equal(report.wrongLineSequence, 0);
  assert.equal(report.routeNotFoundRate, 0);
  assert.deepEqual(report.releaseBlockersSatisfied, ["D-2", "D-3", "H-1"]);
});

test("reads line sequence from route v2 legType runtime response", () => {
  const report = buildContractReport({
    schemaVersion: 1,
    samples: [
      {
        id: "controller-runtime",
        response: {
          data: {
            itineraries: [
              {
                status: "FOUND",
                transferCount: 1,
                legs: [
                  { legType: "RIDE", lineId: "line-a" },
                  { legType: "TRANSFER" },
                  { legType: "RIDE", lineId: "line-b" },
                ],
              },
            ],
          },
        },
        expected: {
          lineSequences: [["line-a", "line-b"]],
        },
      },
    ],
  });

  assert.equal(report.wrongLineSequence, 0);
});

test("writes route v2 contract report json", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "route-v2-contract-"));
  const inputPath = path.join(dir, "responses.json");
  const outputPath = path.join(dir, "route-v2-contract-report.json");
  await writeFile(inputPath, JSON.stringify({
    schemaVersion: 1,
    capabilities: {
      multiTransferSupported: true,
      outOfStationTransferSupported: false,
    },
    samples: [
      {
        id: "direct-runtime",
        response: routeResponse([itinerary(0, ["line-direct"])]),
        expected: {
          transferCounts: [0],
          lineSequences: [["line-direct"]],
        },
      },
    ],
  }));

  const report = await main(["--input", inputPath, "--output", outputPath]);
  const stored = JSON.parse(await readFile(outputPath, "utf8"));

  assert.deepEqual(stored, report);
  assert.equal(stored.alternativeItinerariesMinObserved, 1);
});

function routeResponse(itineraries) {
  return {
    data: {
      itineraries,
    },
  };
}

function itinerary(transferCount, lineIds) {
  return {
    status: "FOUND",
    transferCount,
    legs: lineIds.map((lineId) => ({
      type: "RIDE",
      lineId,
    })),
  };
}
