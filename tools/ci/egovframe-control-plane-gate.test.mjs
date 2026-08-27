import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const gate = JSON.parse(
  await readFile(
    new URL("../../backend/quality/egovframe-control-plane-gate.json", import.meta.url),
    "utf8"
  )
);

test("reports eGovFrame utilization from adopted module state", () => {
  const definition = gate.utilizationDefinition;
  const adoptedModules = definition.applicableModules.filter(
    (moduleName) => gate.pocDecision[moduleName]?.status === definition.adoptedStatus
  );
  const percent = Number(
    ((adoptedModules.length / definition.applicableModules.length) * 100).toFixed(2)
  );

  assert.deepEqual(definition.current, {
    adoptedModules: adoptedModules.length,
    applicableModules: definition.applicableModules.length,
    percent
  });
  assert.equal(gate.pocDecision.fdlLogging.status, "classpath_verified_control_plane_only");
  assert.equal(adoptedModules.includes("fdlLogging"), false);
});
