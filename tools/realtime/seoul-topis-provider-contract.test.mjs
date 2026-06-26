import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

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
    "candidate-only",
  );
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
