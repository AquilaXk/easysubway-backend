import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { validateQuotaEvidence } from "../datapack/lib/quota-evidence.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const contractPath = path.join(root, "tools/realtime/seoul-topis-provider-contract.json");
const arrivalsFixturePath = path.join(root, "tools/realtime/fixtures/seoul-topis-arrivals.sample.json");
const trainPositionsFixturePath = path.join(root, "tools/realtime/fixtures/seoul-topis-train-positions.sample.json");

test("서울 TOPIS realtime provider 계약은 capability와 key 경계를 분리한다", async () => {
  const contract = await readJson(contractPath);
  assert.equal(contract.schemaVersion, 1);
  assert.equal(contract.providerId, "seoul-topis");
  assert.equal(contract.secretBoundary.serviceKeyHandling, "backend_env_only");
  assert.equal(contract.secretBoundary.mobileBundleAllowed, false);
  assert.equal(contract.secretBoundary.fixtureAllowed, false);
  assert.equal(contract.transport.mobileDirectCallAllowed, false);
  assert.equal(contract.gatewayPolicy.requestCoalescing, true);
  assert.ok(contract.gatewayPolicy.cacheTtlSeconds > 0);
  assert.ok(contract.gatewayPolicy.staleCacheTtlSeconds > contract.gatewayPolicy.cacheTtlSeconds);
  assert.deepEqual(
    contract.capabilities.map((capability) => capability.id).sort(),
    ["ARRIVALS", "TRAIN_POSITIONS"],
  );

  const arrivals = capabilityById(contract, "ARRIVALS");
  assert.match(arrivals.providerEndpoint, /realtimeStationArrival/);
  assert.equal(arrivals.freshness.providerTimestampField, "recptnDt");
  assert.ok(arrivals.requestKey.includes("stationQueryName"));
  assert.ok(arrivals.responseDto.requiredItemFields.includes("barvlDt"));

  const trainPositions = capabilityById(contract, "TRAIN_POSITIONS");
  assert.match(trainPositions.providerEndpoint, /realtimePosition/);
  assert.ok(trainPositions.requestKey.includes("lineName"));
  assert.ok(trainPositions.responseDto.requiredItemFields.includes("trainSttus"));
  assert.equal(
    contract.officialSources.find((source) => source.capability === "TRAIN_POSITIONS").sourceInventoryStatus,
    "production-approved",
  );
});

test("서울 TOPIS는 공식 기본 quota 안의 hard cap으로 guarded production을 승인한다", async () => {
  const contract = await readJson(contractPath);
  assert.equal(contract.contractStatus, "production-guarded-default-quota");
  assert.deepEqual(contract.quotaPolicy, {
    providerDefaultDailyLimit: 1000,
    runtimeDailyHardLimit: 800,
    runtimePerMinuteHardLimit: 1,
    galleryReviewStatus: "PENDING_CAPACITY_ENHANCEMENT_NOT_REQUIRED_FOR_GUARDED_USE",
    sharedQuotaStore: "realtime_provider_call_quota_state",
    productionUseAllowed: true,
  });

  const candidates = await readJson(path.join(root, "tools/datapack/source-candidates.json"));
  const topisCandidates = candidates.candidates.filter((candidate) => candidate.id.startsWith("seoul-topis-realtime-"));
  assert.equal(topisCandidates.length, 2);
  for (const candidate of topisCandidates) {
    assert.deepEqual(candidate.evidence.missingEvidence, []);
    assert.deepEqual(candidate.evidence.adminReview.quotaEvidence, {
      portal: "서울 열린데이터광장",
      defaultDailyLimit: 1000,
      runtimeDailyHardLimit: 800,
      runtimePerMinuteHardLimit: 1,
      sharedQuotaStore: "realtime_provider_call_quota_state",
      unlockStatus: "guarded_default_quota_gallery_review_pending",
      productionUseAllowed: true,
    });
    assert.deepEqual(candidate.capabilities.realtime, {
      status: "SUPPORTED",
      productionUseAllowed: true,
      liveEtaEligible: true,
      rateLimitStatus: "GUARDED_DEFAULT_DAILY_LIMIT",
      coverageStatus: "SUPPORTED_WITHIN_PROVIDER_SCOPE",
      updateFrequency: "provider realtime API; guarded by 1/minute and 800/day runtime hard limits",
      unsupportedNotes: "서울 TOPIS 제공 범위 밖은 PLANNED로 강등하며 gallery 심사는 기본 quota 운영과 독립적인 capacity enhancement다",
    });
  }

  const inventoryIds = new Set([
    "seoul-realtime-arrival-station-info",
    "seoul-topis-realtime-train-position",
  ]);
  const inventoryPaths = [
    "tools/datapack/source-inventory.json",
    "apps/mobile/assets/datapacks/source-inventory.json",
  ];
  for (const inventoryPath of inventoryPaths) {
    const inventory = await readJson(path.join(root, inventoryPath));
    const topisSources = inventory.sources.filter((source) => inventoryIds.has(source.id));
    assert.equal(topisSources.length, 2);
    for (const source of topisSources) {
      assert.equal(source.capabilities.realtime.status, "SUPPORTED");
      assert.equal(source.capabilities.realtime.productionUseAllowed, true);
      assert.equal(source.capabilities.realtime.liveEtaEligible, true);
      assert.equal(source.admissionEvidence.issue, 1416);
      assert.equal(source.admissionEvidence.quotaEvidence.defaultDailyLimit, 1000);
      assert.equal(source.admissionEvidence.quotaEvidence.runtimeDailyHardLimit, 800);
      assert.equal(source.admissionEvidence.quotaEvidence.runtimePerMinuteHardLimit, 1);
      assert.equal(source.admissionEvidence.quotaEvidence.sharedQuotaStore, "realtime_provider_call_quota_state");
      assert.equal(source.admissionEvidence.quotaEvidence.productionUseAllowed, true);
    }
  }
});

test("quota evidence는 runtime 일일·분당 hard limit을 함께 요구한다", () => {
  const quotaEvidence = {
    portal: "서울 열린데이터광장",
    defaultDailyLimit: 1000,
    runtimeDailyHardLimit: 800,
    unlockStatus: "guarded_default_quota_gallery_review_pending",
    productionUseAllowed: true,
  };

  assert.throws(
    () => validateQuotaEvidence(quotaEvidence, "quotaEvidence"),
    /runtimeDailyHardLimit and runtimePerMinuteHardLimit together/,
  );
});

test("quota evidence는 shared store가 있으면 runtime hard limit을 요구한다", () => {
  assert.throws(
    () => validateQuotaEvidence({
      portal: "서울 열린데이터광장",
      defaultDailyLimit: 1000,
      sharedQuotaStore: "realtime_provider_call_quota_state",
      unlockStatus: "guarded_default_quota_gallery_review_pending",
      productionUseAllowed: true,
    }, "quotaEvidence"),
    /sharedQuotaStore requires runtimeDailyHardLimit and runtimePerMinuteHardLimit/,
  );
});

test("#1416 production evidence는 quota·freshness·archive·fallback을 PASS로 묶는다", async () => {
  const evidence = await readJson(path.join(root, "tools/realtime/seoul-topis-production-evidence.json"));
  assert.equal(evidence.schemaVersion, 1);
  assert.equal(evidence.artifactKind, "seoul-topis-guarded-production-evidence");
  assert.equal(evidence.issue, 1416);
  assert.equal(evidence.status, "PASS");
  assert.equal(evidence.officialEvidence.termsUrl, "https://data.seoul.go.kr/etc/accessTerms.do");
  assert.equal(evidence.officialEvidence.quotaGuideUrl, "https://data.seoul.go.kr/together/guide/useGuide.do");
  assert.deepEqual(evidence.quota, {
    providerDefaultDailyLimit: 1000,
    runtimeDailyHardLimit: 800,
    runtimePerMinuteHardLimit: 1,
    galleryReviewStatus: "PENDING_CAPACITY_ENHANCEMENT_NOT_REQUIRED_FOR_GUARDED_USE",
  });
  assert.equal(evidence.runtime.providerFreshnessSeconds, 90);
  assert.equal(evidence.runtime.cacheTtlSeconds, 20);
  assert.equal(evidence.runtime.staleCacheTtlSeconds, 120);
  assert.equal(evidence.runtime.requestCoalescing, true);
  assert.equal(evidence.archive.retentionDays, 30);
  assert.equal(evidence.archive.purgeSchedule, "0 20 3 * * *");
  assert.equal(evidence.archive.purgeZone, "UTC");
  assert.equal(evidence.archive.extraProviderCallsForArchive, 0);
  assert.equal(evidence.archive.table, "realtime_arrival_observations");
  assert.equal(evidence.fallback.outOfProviderScopeEtaSource, "PLANNED");
  assert.equal(evidence.releaseClaim.commercialRealtimeRouteClaimAllowed, false);

  const expectedSamples = new Map([
    ["tools/realtime/fixtures/seoul-topis-arrivals.sample.json", "faf66484f765de250a3a64958aeaa7012a1f204d0c144128d399ba2b47afe2e7"],
    ["tools/realtime/fixtures/seoul-topis-train-positions.sample.json", "e69692b28c41a501c3b9883b7b36b66e157c0da5c98508502c96e44b3b9110bf"],
  ]);
  for (const sample of evidence.liveSamples) {
    assert.equal(sample.sha256, expectedSamples.get(sample.path));
    assert.equal(await sha256File(path.join(root, sample.path)), sample.sha256);
    const fixture = await readJson(path.join(root, sample.path));
    const items = sample.capability === "ARRIVALS"
      ? fixture.payload.realtimeArrivalList
      : fixture.payload.realtimePositionList;
    assert.equal(sample.rowCount, items.length);
    expectedSamples.delete(sample.path);
  }
  assert.equal(expectedSamples.size, 0);
});

test("서울 TOPIS sanitized fixtures는 capability별 DTO 필수 필드와 timestamp를 보존한다", async () => {
  const contract = await readJson(contractPath);
  const arrivals = await readJson(arrivalsFixturePath);
  const trainPositions = await readJson(trainPositionsFixturePath);

  assertNoCredentialLeak(arrivals);
  assertNoCredentialLeak(trainPositions);

  const arrivalItems = parseItems(arrivals, "realtimeArrivalList");
  const trainPositionItems = parseItems(trainPositions, "realtimePositionList");
  assertRequiredFields(arrivalItems[0], capabilityById(contract, "ARRIVALS").responseDto.requiredItemFields);
  assertRequiredFields(
    trainPositionItems[0],
    capabilityById(contract, "TRAIN_POSITIONS").responseDto.requiredItemFields,
  );
  assert.ok(toProviderDate(arrivalItems[0].recptnDt) < new Date(arrivals.receivedAt));
  assert.ok(toProviderDate(trainPositionItems[0].recptnDt) < new Date(trainPositions.receivedAt));
});

test("서울 TOPIS source 후보는 실시간 provider key를 backend 전용으로만 허용한다", async () => {
  const candidates = await readJson(path.join(root, "tools/datapack/source-candidates.json"));
  const topisCandidates = candidates.candidates.filter((candidate) => candidate.id.startsWith("seoul-topis-realtime-"));

  assert.deepEqual(
    topisCandidates.map((candidate) => candidate.id).sort(),
    ["seoul-topis-realtime-station-arrival", "seoul-topis-realtime-train-position"],
  );
  for (const candidate of topisCandidates) {
    assert.equal(candidate.serviceKeyHandling, "backend_secret_only");
    assert.equal(candidate.mobileEmbeddingAllowed, false);
    assert.equal(candidate.dataRetentionPolicy, "provider_does_not_offer_past_realtime_data");
    assert.match(candidate.evidence.sampleUrl, /\[서비스키값\]/);
    assertNoCredentialLeak(candidate);
  }
});

test("모바일 소스는 서울 TOPIS host와 service key env를 직접 포함하지 않는다", async () => {
  const mobileFiles = await listFiles(path.join(root, "apps/mobile"));
  const sourceFiles = mobileFiles.filter((filePath) => /\.(dart|kt|swift|xml|gradle|plist)$/.test(filePath));

  assert.ok(sourceFiles.length > 0, "mobile source files must be scanned");
  for (const filePath of sourceFiles) {
    const source = await readFile(filePath, "utf8");
    const relativePath = path.relative(root, filePath);
    assert.doesNotMatch(source, /EASYSUBWAY_SEOUL_TOPIS_SERVICE_KEY/, `${relativePath} must not embed TOPIS service key env`);
    assert.doesNotMatch(source, /swopenapi\.seoul\.go\.kr/, `${relativePath} must not call TOPIS directly`);
  }
});

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

async function sha256File(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex");
}

async function listFiles(directoryPath) {
  const entries = await readdir(directoryPath, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    if (entry.name === ".dart_tool" || entry.name === "build") {
      continue;
    }
    const entryPath = path.join(directoryPath, entry.name);
    if (entry.isDirectory()) {
      files.push(...await listFiles(entryPath));
    } else if (entry.isFile()) {
      files.push(entryPath);
    }
  }
  return files;
}

function capabilityById(contract, capabilityId) {
  const capability = contract.capabilities.find((entry) => entry.id === capabilityId);
  assert.ok(capability, `missing capability: ${capabilityId}`);
  return capability;
}

function parseItems(fixture, fieldName) {
  assert.equal(fixture.payload.errorMessage.code, "INFO-000");
  const items = fixture.payload[fieldName];
  assert.ok(Array.isArray(items));
  assert.ok(items.length > 0);
  return items;
}

function assertRequiredFields(item, fields) {
  for (const field of fields) {
    assert.equal(typeof item[field], "string", `${field} must be preserved as a provider string`);
    assert.notEqual(item[field].trim(), "", `${field} must not be blank`);
  }
}

function toProviderDate(value) {
  assert.match(value, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  return new Date(`${value.replace(" ", "T")}+09:00`);
}

function assertNoCredentialLeak(value) {
  const text = JSON.stringify(value);
  assert.doesNotMatch(text, /serviceKey=(?!\[서비스키값\])[^&\s"]+/i);
  assert.doesNotMatch(
    text,
    /swopenapi\.seoul\.go\.kr\/api\/subway\/(?!\[서비스키값\]\/)(?!\{serviceKey\}\/)[^/\s"]+\/json\//i,
  );
}
