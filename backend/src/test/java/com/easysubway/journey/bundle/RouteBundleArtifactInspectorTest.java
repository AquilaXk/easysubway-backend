package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteBundleArtifactInspectorTest {
	private static final String TOPOLOGY = "payload/topology.sqlite.zst";
	private static final String TIMETABLE = "payload/timetable.sqlite.zst";
	private static final String ACCESSIBILITY = "payload/accessibility.sqlite.zst";
	private static final String FARE = "payload/fare.sqlite.zst";

	@Test
	void inspectsExactFourPayloadsWithoutCreatingACandidate() {
		var payloads = payloads();
		var inspection = RouteBundleArtifactInspector.inspect(manifest(payloads), payloads);

		assertThat(inspection).isInstanceOf(RouteBundlePayloadInspection.class);
		assertThat(inspection.identity().bundleId()).isEqualTo("capital-v1");
		assertThat(inspection.manifestSha256()).isEqualTo(sha(manifest(payloads)));
		assertThat(inspection.payloadSha256()).isEqualTo(inventorySha(payloads));
	}

	@Test
	void rejectsMalformedJsonWithItsExactReason() {
		var payloads = payloads();
		assertReason(payloads, "{\"manifestVersion\":1,".getBytes(StandardCharsets.UTF_8), RouteBundleInspectionException.Reason.MANIFEST_UTF8_OR_JSON_INVALID);
	}

	@Test
	void rejectsActualInvalidUtf8WithItsExactReason() {
		var payloads = payloads();
		var invalidUtf8 = new byte[] { '{', '"', 'a', '"', ':', '"', (byte) 0xC3, '"', '}' };
		assertReason(payloads, invalidUtf8, RouteBundleInspectionException.Reason.MANIFEST_UTF8_OR_JSON_INVALID);
	}

	@Test
	void rejectsUtf16AndUtf32JsonRawBytesWithItsExactReason() {
		var payloads = payloads();
		var json = new String(manifest(payloads), StandardCharsets.UTF_8);
		assertReason(payloads, json.getBytes(StandardCharsets.UTF_16LE), RouteBundleInspectionException.Reason.MANIFEST_UTF8_OR_JSON_INVALID);
		assertReason(payloads, json.getBytes(java.nio.charset.Charset.forName("UTF-32LE")), RouteBundleInspectionException.Reason.MANIFEST_UTF8_OR_JSON_INVALID);
	}

	@Test
	void rejectsDuplicateTopLevelFieldWithItsExactReason() {
		var payloads = payloads();
		assertReason(payloads, "{\"manifestVersion\":1,\"manifestVersion\":1}".getBytes(StandardCharsets.UTF_8), RouteBundleInspectionException.Reason.MANIFEST_DUPLICATE_FIELD);
	}

	@Test
	void rejectsUnknownTopLevelFieldWithItsExactReason() {
		var payloads = payloads();
		assertReason(payloads, append(manifest(payloads), ",\"unknown\":true"), RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
	}

	@Test
	void rejectsMissingTopLevelFieldWithItsExactReason() {
		var payloads = payloads();
		var signature = ",\"signature\":{\"algorithm\":\"rsa-sha256-server-route-bundle-v1\",\"value\":\"AQID\"}";
		assertReason(payloads, new String(manifest(payloads), StandardCharsets.UTF_8).replace(signature, "").getBytes(StandardCharsets.UTF_8), RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
	}

	@Test
	void rejectsScalarSchemaAndNestedExtraFields() {
		var payloads = payloads();
		for (var manifest : new byte[][] {
			manifest(payloads, "\"releaseSequence\":1", "\"releaseSequence\":1.5"),
			manifest(payloads, "\"schemaCompatibility\":{\"backendMin\":3,\"backendMax\":3}", "\"schemaCompatibility\":{\"backendMin\":3,\"backendMax\":3,\"future\":true}"),
			manifest(payloads, "\"signature\":{\"algorithm\":\"rsa-sha256-server-route-bundle-v1\",\"value\":\"AQID\"}", "\"signature\":{\"algorithm\":\"wrong\",\"value\":\"AQID\"}")
		}) {
			assertThatThrownBy(() -> RouteBundleArtifactInspector.inspect(manifest, payloads))
				.isInstanceOf(RouteBundleInspectionException.class)
				.extracting(error -> ((RouteBundleInspectionException) error).reason())
				.isEqualTo(RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
		}
	}

	@Test
	void rejectsMissingOrExtraPayloadPaths() {
		var missing = payloads();
		var missingManifest = manifest(missing);
		missing.remove(FARE);
		var extra = payloads();
		var extraManifest = manifest(extra);
		extra.put("payload/unknown.sqlite.zst", new byte[] { 1 });
		for (var fixture : List.of(Map.entry(missingManifest, missing), Map.entry(extraManifest, extra))) {
			assertThatThrownBy(() -> RouteBundleArtifactInspector.inspect(fixture.getKey(), fixture.getValue()))
				.isInstanceOf(RouteBundleInspectionException.class)
				.extracting(error -> ((RouteBundleInspectionException) error).reason())
				.isEqualTo(RouteBundleInspectionException.Reason.PAYLOAD_PATH_SET_MISMATCH);
		}
	}

	@Test
	void rejectsZeroByteComponentAsPayloadPathSetMismatch() {
		for (var componentPath : List.of(TOPOLOGY, TIMETABLE, ACCESSIBILITY, FARE)) {
			var payloads = payloads();
			var manifest = manifest(payloads);
			payloads.put(componentPath, new byte[0]);
			assertReason(payloads, manifest, RouteBundleInspectionException.Reason.PAYLOAD_PATH_SET_MISMATCH);
		}
	}

	@Test
	void rejectsEachComponentMutationWithItsExactReason() {
		var expected = Map.of(
			TOPOLOGY, RouteBundleInspectionException.Reason.TOPOLOGY_DIGEST_MISMATCH,
			TIMETABLE, RouteBundleInspectionException.Reason.TIMETABLE_DIGEST_MISMATCH,
			ACCESSIBILITY, RouteBundleInspectionException.Reason.ACCESSIBILITY_DIGEST_MISMATCH,
			FARE, RouteBundleInspectionException.Reason.FARE_DIGEST_MISMATCH);
		for (var entry : expected.entrySet()) {
			var payloads = payloads();
			var manifest = manifest(payloads);
			payloads.get(entry.getKey())[0]++;
			assertThatThrownBy(() -> RouteBundleArtifactInspector.inspect(manifest, payloads))
				.isInstanceOf(RouteBundleInspectionException.class)
				.extracting(error -> ((RouteBundleInspectionException) error).reason()).isEqualTo(entry.getValue());
		}
	}

	@Test
	void rejectsInventoryMismatchAndDoesNotMutateInputs() {
		var payloads = payloads();
		var original = payloads.get(TOPOLOGY).clone();
		assertThatThrownBy(() -> RouteBundleArtifactInspector.inspect(manifest(payloads, "\"payloadSha256\":\"" + inventorySha(payloads) + "\"", "\"payloadSha256\":\"" + "f".repeat(64) + "\""), payloads))
			.isInstanceOf(RouteBundleInspectionException.class)
			.extracting(error -> ((RouteBundleInspectionException) error).reason())
			.isEqualTo(RouteBundleInspectionException.Reason.PAYLOAD_INVENTORY_DIGEST_MISMATCH);
		assertThat(payloads.get(TOPOLOGY)).containsExactly(original);
	}

	private static Map<String, byte[]> payloads() {
		var payloads = new LinkedHashMap<String, byte[]>();
		payloads.put(TOPOLOGY, "topology".getBytes(StandardCharsets.UTF_8));
		payloads.put(TIMETABLE, "timetable".getBytes(StandardCharsets.UTF_8));
		payloads.put(ACCESSIBILITY, "accessibility".getBytes(StandardCharsets.UTF_8));
		payloads.put(FARE, "fare".getBytes(StandardCharsets.UTF_8));
		return payloads;
	}

	private static byte[] manifest(Map<String, byte[]> payloads) {
		return manifest(payloads, "", "");
	}

	private static byte[] manifest(Map<String, byte[]> payloads, String before, String after) {
		return ("{\"manifestVersion\":1,\"artifactKind\":\"server-route-bundle\",\"bundleId\":\"capital-v1\",\"releaseSequence\":1,"
			+ "\"stationSetSha256\":\"" + "0".repeat(64) + "\",\"payloadSha256\":\"" + inventorySha(payloads) + "\","
			+ "\"topologySha256\":\"" + sha(payloads.get(TOPOLOGY)) + "\",\"timetableSha256\":\"" + sha(payloads.get(TIMETABLE)) + "\","
			+ "\"accessibilitySha256\":\"" + sha(payloads.get(ACCESSIBILITY)) + "\",\"fareSha256\":\"" + sha(payloads.get(FARE)) + "\","
			+ "\"provenanceSha256\":\"" + "1".repeat(64) + "\",\"compatibilitySha256\":\"" + "2".repeat(64) + "\","
			+ "\"serviceTimezone\":\"Asia/Seoul\",\"activeFrom\":\"2026-08-09T09:00:00.000+09:00\",\"freshUntil\":\"2026-08-10T09:00:00.000+09:00\","
			+ "\"schemaCompatibility\":{\"backendMin\":3,\"backendMax\":3},\"keyId\":\"route-bundle-key\","
			+ "\"signature\":{\"algorithm\":\"rsa-sha256-server-route-bundle-v1\",\"value\":\"AQID\"}}")
			.replace(before, after)
			.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] append(byte[] json, String field) {
		var source = new String(json, StandardCharsets.UTF_8);
		return (source.substring(0, source.length() - 1) + field + "}").getBytes(StandardCharsets.UTF_8);
	}

	private static void assertReason(Map<String, byte[]> payloads, byte[] manifest, RouteBundleInspectionException.Reason reason) {
		assertThatThrownBy(() -> RouteBundleArtifactInspector.inspect(manifest, payloads))
			.isInstanceOf(RouteBundleInspectionException.class)
			.extracting(error -> ((RouteBundleInspectionException) error).reason())
			.isEqualTo(reason);
	}

	private static String inventorySha(Map<String, byte[]> payloads) {
		var inventory = "[" + payloads.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry ->
			"{\"path\":\"" + entry.getKey() + "\",\"sha256\":\"" + sha(entry.getValue()) + "\",\"sizeBytes\":" + entry.getValue().length + "}")
			.reduce((left, right) -> left + "," + right).orElseThrow() + "]";
		return sha(inventory.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}
}
