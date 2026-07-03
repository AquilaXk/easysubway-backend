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

  assert.equal(query.alternativeCount, 3);
  assert.equal(alternatives.length, 2);
  assert.equal(alternatives[0].arrival, "09:25");
  assert.equal(alternatives[0].transferCount, 1);
  assert.equal(alternatives[1].arrival, "09:30");
  assert.equal(alternatives[1].transferCount, 0);
  assert.equal(alternatives[0].path[0].from, "pareto_a");
  assert.equal(alternatives[0].path.at(-1).to, "pareto_b");
});

test("route v2 prototypes honor requested alternative count", () => {
  const query = fixtures.queries.find((candidate) => candidate.id === "pareto_arrival_vs_transfer");
  assert.ok(query, "missing fixture query: pareto_arrival_vs_transfer");
  const alternatives = runTimeDependentDijkstra(fixtures, { ...query, alternativeCount: 1 });

  assert.equal(alternatives.length, 1);
  assert.equal(alternatives[0].arrival, "09:25");
});

test("route v2 fixture suite covers commercialization time-axis blockers", () => {
  const queryIds = new Set(fixtures.queries.map((query) => query.id));

  for (const id of [
    "provider_realtime_fresh_but_not_boardable_due_to_entry_slack",
    "transfer_buffer_too_short_selects_next_train",
    "pareto_arrival_vs_transfer",
    "provider_realtime_stale",
    "unmatched_realtime_express_does_not_override_planned_local",
    "strict_step_free_excludes_transfer",
  ]) {
    assert.ok(queryIds.has(id), `missing route commercialization fixture: ${id}`);
  }
});

test("route v2 time-axis fixtures reject unboardable realtime arrivals", () => {
  const query = fixtureQuery("provider_realtime_fresh_but_not_boardable_due_to_entry_slack");

  assert.equal(runRangeRaptor(fixtures, query)[0] ?? null, null);
  assert.equal(runTimeDependentDijkstra(fixtures, query)[0] ?? null, null);
});

test("route v2 time-axis fixtures keep transfer feasibility and stale realtime fallback explicit", () => {
  const transfer = fixtureQuery("transfer_buffer_too_short_selects_next_train");
  const staleRealtime = fixtureQuery("provider_realtime_stale");
  const unmatchedRealtime = fixtureQuery("unmatched_realtime_express_does_not_override_planned_local");

  assert.deepEqual(runTimeDependentDijkstra(fixtures, transfer)[0].tripIds, ["l4-express-0903", "l2-local-0945"]);
  assert.deepEqual(runTimeDependentDijkstra(fixtures, staleRealtime)[0].path.map((step) => step.realtimeMatchLevel), ["MATCHED_REALTIME"]);
  assert.deepEqual(runTimeDependentDijkstra(fixtures, unmatchedRealtime)[0].path.map((step) => step.realtimeMatchLevel), ["PLANNED"]);
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

function fixtureQuery(id) {
  const query = fixtures.queries.find((candidate) => candidate.id === id);
  assert.ok(query, `missing fixture query: ${id}`);
  return query;
}

function assertExpected(query, field, actual, name) {
  if (query[field] === undefined) return;
  assert.deepEqual(actual, query[field], `${name} ${field}`);
}
