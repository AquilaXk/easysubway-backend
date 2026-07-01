import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { loadFixtures, runAll, runRangeRaptor, runTimeDependentDijkstra } from "./prototype-route-v2.mjs";

const fixtures = await loadFixtures();

for (const query of fixtures.queries) {
  test(`route v2 prototypes match fixture: ${query.id}`, () => {
    assertPrototype(query, runRangeRaptor(fixtures, query)[0] ?? null, "raptor");
    assertPrototype(query, runTimeDependentDijkstra(fixtures, query)[0] ?? null, "time-dependent Dijkstra");
  });
}

test("route v2 prototypes return Pareto alternatives with reconstructed paths", () => {
  const query = fixtures.queries.find((candidate) => candidate.id === "pareto_arrival_vs_transfer");
  assert.ok(query, "missing fixture query: pareto_arrival_vs_transfer");
  const alternatives = runTimeDependentDijkstra(fixtures, query);

  assert.equal(alternatives.length, 2);
  assert.equal(alternatives[0].arrival, "09:25");
  assert.equal(alternatives[0].transferCount, 1);
  assert.equal(alternatives[1].arrival, "09:30");
  assert.equal(alternatives[1].transferCount, 0);
  assert.equal(alternatives[0].path[0].from, "pareto_a");
  assert.equal(alternatives[0].path.at(-1).to, "pareto_b");
});

test("route v2 CLI report keeps full Pareto alternatives", () => {
  const result = runAll(fixtures).results.find((candidate) => candidate.queryId === "pareto_arrival_vs_transfer");

  assert.ok(result, "missing runAll result: pareto_arrival_vs_transfer");
  assert.equal(result.raptor.length, 2);
  assert.equal(result.timeDependentDijkstra.length, 2);
});

test("route algorithm ADR fixes backend and mobile responsibilities", async () => {
  const adr = JSON.parse(await readFile(new URL("./route-algorithm-v2-adr.json", import.meta.url), "utf8"));

  assert.equal(adr.decision.includes("Range RAPTOR"), true);
  assert.equal(adr.mobileRole.includes("not live high-quality routing"), true);
  assert.ok(adr.accessGraphDijkstraRole.includes("offline static fallback"));
  assert.equal(adr.v2Rules.paretoCandidateLimit, 3);
  assert.equal(adr.verification, "node --test tools/routes/*.test.mjs");
});

function assertPrototype(query, result, name) {
  if (query.expectedArrival === null) {
    assert.equal(result, null, `${name} should not find a boardable itinerary`);
    return;
  }

  assert.equal(result.arrival, query.expectedArrival, `${name} arrival`);
  if (query.expectedDurationSeconds !== undefined) {
    assert.equal(result.durationSeconds, query.expectedDurationSeconds, `${name} duration`);
  }
  assert.equal(result.transferCount, query.expectedTransfers, `${name} transfer count`);
  assert.deepEqual(result.tripIds, query.expectedTripIds, `${name} trip ids`);
  assert.ok(result.path.length > 0, `${name} reconstructs path`);
  const rideSteps = result.path.filter((step) => step.type === "ride");
  assertExpected(query, "expectedServicePatterns", rideSteps.map((step) => step.servicePattern), name);
  assertExpected(query, "expectedHeadsigns", rideSteps.map((step) => step.headsign), name);
  assertExpected(query, "expectedDirections", rideSteps.map((step) => step.directionId), name);
  assertExpected(query, "expectedDestinationStationIds", rideSteps.map((step) => step.destinationStationId), name);
  assertExpected(query, "expectedStopPatterns", rideSteps.map((step) => step.stopPattern), name);
  assertExpected(query, "expectedRealtimeMatchLevels", rideSteps.map((step) => step.realtimeMatchLevel), name);
}

function assertExpected(query, field, actual, name) {
  if (query[field] === undefined) return;
  assert.deepEqual(actual, query[field], `${name} ${field}`);
}
