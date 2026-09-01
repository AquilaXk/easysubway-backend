import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const adr = JSON.parse(
  await readFile(new URL("./route-algorithm-v2-adr.json", import.meta.url), "utf8"),
);

test("route algorithm ADR identifies only the active point capability", () => {
  assert.equal(adr.schemaVersion, 2);
  assert.equal(adr.status, "active-production");
  assert.equal(adr.algorithmSuiteId, "EASYSUBWAY_RAPTOR_SUITE_V2");
  assert.deepEqual(adr.pointQuery, {
    status: "active",
    algorithm: "MARKED_SINGLE_DEPARTURE_RAPTOR",
    supportedModes: ["NOW", "DEPART_AT"],
  });
  assert.doesNotMatch(JSON.stringify(adr.pointQuery), /range raptor/i);
});

test("route algorithm ADR keeps temporal profile capability inactive until PR 312", () => {
  assert.deepEqual(adr.profileQuery, {
    status: "inactive",
    activationRequirement: "Backend PR #312 is terminal",
    supportedModes: ["DEPART_BETWEEN", "ARRIVE_BY", "LAST_CONNECTION"],
  });
});

test("route algorithm ADR forbids semantic fallback and current Route V2 prototype claims", () => {
  assert.equal(adr.semanticFallbackAllowed, false);
  assert.equal(adr.mobileStaticRouteCalculationContribution, false);
  assert.equal(adr.routeV2PrototypeCurrentContribution, false);
  assert.equal(adr.verification, "node --test tools/routes/route-algorithm-v2-adr.test.mjs");
});
