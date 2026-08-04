import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const root = path.resolve(import.meta.dirname, "../..");
const contractPath = path.join(root, "tools/realtime/seoul-topis-provider-contract.json");

test("서울 TOPIS backend 계약은 capability·secret·quota 경계를 고정한다", async () => {
  const contract = await readJson(contractPath);
  assert.equal(contract.schemaVersion, 1);
  assert.equal(contract.providerId, "seoul-topis");
  assert.equal(contract.secretBoundary.serviceKeyHandling, "backend_env_only");
  assert.equal(contract.secretBoundary.mobileBundleAllowed, false);
  assert.equal(contract.transport.mobileDirectCallAllowed, false);
  assert.equal(contract.gatewayPolicy.requestCoalescing, true);
  assert.ok(contract.gatewayPolicy.cacheTtlSeconds > 0);
  assertNoStaleServingDeclaration(contract.gatewayPolicy, contract.fallbackCodes);
  assert.deepEqual(
    contract.capabilities.map(({ id }) => id).sort(),
    ["ARRIVALS", "TRAIN_POSITIONS"],
  );
  assert.deepEqual(contract.quotaPolicy, {
    providerDefaultDailyLimit: 1000,
    runtimeDailyHardLimit: 800,
    runtimePerMinuteHardLimit: 1,
    galleryReviewStatus: "PENDING_CAPACITY_ENHANCEMENT_NOT_REQUIRED_FOR_GUARDED_USE",
    sharedQuotaStore: "realtime_provider_call_quota_state",
    productionUseAllowed: true,
  });
});

test("서울 TOPIS production evidence는 fixture hash와 runtime fallback을 고정한다", async () => {
  const evidence = await readJson(path.join(root, "tools/realtime/seoul-topis-production-evidence.json"));
  assert.equal(evidence.status, "PASS");
  assert.equal(evidence.runtime.requestCoalescing, true);
  assertNoStaleServingDeclaration(evidence.runtime, []);
  assert.equal(evidence.fallback.outOfProviderScopeEtaSource, "PLANNED");
  assert.equal(evidence.releaseClaim.commercialRealtimeRouteClaimAllowed, false);

  const expected = new Map([
    ["tools/realtime/fixtures/seoul-topis-arrivals.sample.json", "faf66484f765de250a3a64958aeaa7012a1f204d0c144128d399ba2b47afe2e7"],
    ["tools/realtime/fixtures/seoul-topis-train-positions.sample.json", "e69692b28c41a501c3b9883b7b36b66e157c0da5c98508502c96e44b3b9110bf"],
  ]);
  for (const sample of evidence.liveSamples) {
    assert.equal(sample.sha256, expected.get(sample.path));
    assert.equal(await sha256File(path.join(root, sample.path)), sample.sha256);
    expected.delete(sample.path);
  }
  assert.equal(expected.size, 0);
});

test("서울 TOPIS sanitized fixtures는 DTO 필수 필드와 credential 경계를 보존한다", async () => {
  const contract = await readJson(contractPath);
  const fixtures = [
    ["ARRIVALS", "seoul-topis-arrivals.sample.json", "realtimeArrivalList"],
    ["TRAIN_POSITIONS", "seoul-topis-train-positions.sample.json", "realtimePositionList"],
  ];
  for (const [capabilityId, fileName, fieldName] of fixtures) {
    const fixture = await readJson(path.join(root, "tools/realtime/fixtures", fileName));
    const items = fixture.payload[fieldName];
    assert.ok(Array.isArray(items) && items.length > 0);
    for (const field of capabilityById(contract, capabilityId).responseDto.requiredItemFields) {
      assert.equal(typeof items[0][field], "string");
      assert.notEqual(items[0][field].trim(), "");
    }
    const serialized = JSON.stringify(fixture);
    assert.doesNotMatch(serialized, /serviceKey=(?!\[서비스키값\])[^&\s"]+/i);
    assert.doesNotMatch(serialized, /swopenapi\.seoul\.go\.kr\/api\/subway\/(?!\[서비스키값\]\/)(?!\{serviceKey\}\/)[^/\s"]+\/json\//i);
  }
});

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

async function sha256File(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex");
}

function capabilityById(contract, capabilityId) {
  const capability = contract.capabilities.find(({ id }) => id === capabilityId);
  assert.ok(capability, `missing capability: ${capabilityId}`);
  return capability;
}

function assertNoStaleServingDeclaration(gatewayPolicy, fallbackCodes) {
  assert.equal("staleCacheTtlSeconds" in gatewayPolicy, false);
  assert.equal("serveStaleOn" in gatewayPolicy, false);
  assert.equal(fallbackCodes.includes("STALE_CACHE"), false);
}
