package com.easysubway.journey.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RouteBundleConsumerHandoffParserTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String ACTIVATION_REQUEST = "sha256:" + "e".repeat(64);

	@Test
	void exactCanonicalDataHandoffParsesWithoutCreatingCandidateAuthority() {
		var fixture = fixture();

		var handoff = RouteBundleConsumerHandoffParser.parse(fixture.bytes(), ACTIVATION_REQUEST);

		assertThat(handoff.repositoryGitSha()).isEqualTo("9".repeat(40));
		assertThat(handoff.identity().bundleId()).isEqualTo("server-route-bundle-20990101");
		assertThat(handoff.identity().releaseSequence()).isEqualTo(7);
		assertThat(handoff.identity().payloadSha256()).isEqualTo(fixture.payloadSha256());
		assertThat(handoff.sourceSnapshotSetHash()).isEqualTo("8".repeat(64));
		assertThat(handoff.admissionEvidence()).isEqualTo(new RouteBundleAdmissionEvidence(
			fixture.manifestSha256(),
			"sha256:" + "b".repeat(64),
			"sha256:" + "d".repeat(64),
			"sha256:" + fixture.receiptRawSha256(),
			ACTIVATION_REQUEST));
		assertThat(handoff.locator().publicBaseUrl()).isEqualTo(
			"https://objectstorage.ap-seoul-1.oraclecloud.com/n/testnamespace/b/easysubway-route-bundles/o");
		assertThat(handoff.objects()).extracting(RouteBundleConsumerHandoff.PublishedObject::path)
			.containsExactly(
				"compatibility.json",
				"manifest.json",
				"manifest.signing-input.json",
				"payload/accessibility.sqlite.zst",
				"payload/fare.sqlite.zst",
				"payload/timetable.sqlite.zst",
				"payload/topology.sqlite.zst",
				"provenance.json");
		assertThat(handoff.release().result()).isEqualTo("GO");
		assertThat(handoff.prePublicationFinalSha256()).isEqualTo("a".repeat(64));
		assertThat(handoff.platformServerRouteBundleDigest()).isEqualTo("sha256:" + fixture.manifestSha256());
		assertThat(handoff.handoffSha256()).isEqualTo(fixture.handoffSha256());
		assertThat(handoff).isNotInstanceOf(VerifiedRouteBundleCandidate.class);
	}

	@Test
	void exactCanonicalPublicationDescriptorDerivesBackendOwnedAdmission() {
		var fixture = descriptorFixture();

		var descriptor = RouteBundleConsumerHandoffParser.parsePublicationDescriptor(
			fixture.bytes(), ACTIVATION_REQUEST);

		assertThat(descriptor.repositoryGitSha()).isEqualTo("9".repeat(40));
		assertThat(descriptor.identity().bundleId()).isEqualTo("server-route-bundle-20990101");
		assertThat(descriptor.sourceSnapshotSetHash()).isEqualTo("8".repeat(64));
		assertThat(descriptor.admissionEvidence()).isEqualTo(new RouteBundleAdmissionEvidence(
			fixture.manifestSha256(),
			"sha256:" + "b".repeat(64),
			"sha256:" + "d".repeat(64),
			"sha256:" + fixture.receiptRawSha256(),
			ACTIVATION_REQUEST));
		assertThat(descriptor.locator().objectPrefix())
			.isEqualTo("server-route-bundles/v1/" + fixture.manifestSha256() + "/");
		assertThat(descriptor.objects())
			.extracting(RouteBundlePublicationDescriptor.PublishedObject::path)
			.containsExactly(
				"compatibility.json",
				"manifest.json",
				"manifest.signing-input.json",
				"payload/accessibility.sqlite.zst",
				"payload/fare.sqlite.zst",
				"payload/timetable.sqlite.zst",
				"payload/topology.sqlite.zst",
				"provenance.json");
		assertThat(descriptor.release().result()).isEqualTo("GO");
		assertThat(descriptor.descriptorSha256()).isEqualTo(fixture.descriptorSha256());
		assertThat(descriptor).isNotInstanceOf(VerifiedRouteBundleCandidate.class);
	}

	@Test
	void publicationDescriptorRejectsLegacyAuthorityAndIdentityDriftWithoutV1Fallback() throws Exception {
		var descriptor = descriptorFixture();

		assertDescriptorReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			fixture().bytes(),
			ACTIVATION_REQUEST);

		var legacyAuthority = descriptor.node().deepCopy();
		legacyAuthority.putObject("backendAdmission").put("manifestSha256", descriptor.manifestSha256());
		rebindDescriptor(legacyAuthority);
		assertDescriptorReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(legacyAuthority),
			ACTIVATION_REQUEST);

		var producerDrift = descriptor.node().deepCopy();
		((ObjectNode) producerDrift.path("producer")).put("gitSha", "f".repeat(40));
		rebindDescriptor(producerDrift);
		assertDescriptorReason(
			RouteBundleHandoffException.Reason.PUBLICATION_RECEIPT_IDENTITY_MISMATCH,
			canonicalBytes(producerDrift),
			ACTIVATION_REQUEST);

		var digestDrift = descriptor.node().deepCopy();
		digestDrift.put("descriptorSha256", "f".repeat(64));
		assertDescriptorReason(
			RouteBundleHandoffException.Reason.HANDOFF_SELF_DIGEST_MISMATCH,
			canonicalBytes(digestDrift),
			ACTIVATION_REQUEST);

		assertDescriptorReason(
			RouteBundleHandoffException.Reason.HANDOFF_CANONICAL_BYTES_MISMATCH,
			(descriptor.json() + "\n").getBytes(StandardCharsets.UTF_8),
			ACTIVATION_REQUEST);
	}

	@Test
	void rawJsonAndSelfIdentityDriftFailClosedWithTypedReasons() throws Exception {
		var fixture = fixture();

		assertReason(
			RouteBundleHandoffException.Reason.ACTIVATION_REQUEST_IDENTITY_INVALID,
			fixture.bytes(),
			null);
		assertReason(
			RouteBundleHandoffException.Reason.ACTIVATION_REQUEST_IDENTITY_INVALID,
			fixture.bytes(),
			" activation");
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID,
			null,
			ACTIVATION_REQUEST);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID,
			new byte[0],
			ACTIVATION_REQUEST);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID,
			("\uFEFF" + fixture.json()).getBytes(StandardCharsets.UTF_8),
			ACTIVATION_REQUEST);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID,
			new byte[] {(byte) 0xc3, (byte) 0x28},
			ACTIVATION_REQUEST);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_CANONICAL_BYTES_MISMATCH,
			(fixture.json() + "\n").getBytes(StandardCharsets.UTF_8),
			ACTIVATION_REQUEST);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID,
			fixture.json().replaceFirst("\\{", "{\"schemaVersion\":1,").getBytes(StandardCharsets.UTF_8),
			ACTIVATION_REQUEST);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID,
			(fixture.json() + "{}").getBytes(StandardCharsets.UTF_8),
			ACTIVATION_REQUEST);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			"[]".getBytes(StandardCharsets.UTF_8),
			ACTIVATION_REQUEST);

		var unknownKey = fixture.node().deepCopy();
		unknownKey.put("unexpected", true);
		rebindHandoff(unknownKey);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(unknownKey),
			ACTIVATION_REQUEST);

		var selfDigestDrift = fixture.node().deepCopy();
		selfDigestDrift.put("handoffSha256", "f".repeat(64));
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SELF_DIGEST_MISMATCH,
			canonicalBytes(selfDigestDrift),
			ACTIVATION_REQUEST);

		var manifestContainerDrift = fixture.node().deepCopy();
		manifestContainerDrift.put("manifest", "not-an-object");
		rebindHandoff(manifestContainerDrift);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(manifestContainerDrift),
			ACTIVATION_REQUEST);

		var schemaVersionTypeDrift = fixture.node().deepCopy();
		schemaVersionTypeDrift.put("schemaVersion", "1");
		rebindHandoff(schemaVersionTypeDrift);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(schemaVersionTypeDrift),
			ACTIVATION_REQUEST);

		var manifestIdentityDrift = fixture.node().deepCopy();
		((ObjectNode) manifestIdentityDrift.path("manifest"))
			.put("artifactKind", "server-route-bundle-legacy");
		rebindHandoff(manifestIdentityDrift);
		assertReason(
			RouteBundleHandoffException.Reason.MANIFEST_IDENTITY_MISMATCH,
			canonicalBytes(manifestIdentityDrift),
			ACTIVATION_REQUEST);

		var platformDigestDrift = fixture.node().deepCopy();
		((ObjectNode) platformDigestDrift.path("platformRelease"))
			.put("serverRouteBundleDigest", "sha256:" + "f".repeat(64));
		rebindHandoff(platformDigestDrift);
		assertReason(
			RouteBundleHandoffException.Reason.MANIFEST_IDENTITY_MISMATCH,
			canonicalBytes(platformDigestDrift),
			ACTIVATION_REQUEST);
	}

	@Test
	void receiptAndReleaseIdentityDriftFailClosedBeforeAdmission() throws Exception {
		var fixture = fixture();

		var receiptSelfDrift = fixture.node().deepCopy();
		((ObjectNode) receiptSelfDrift.path("publicationReceipt"))
			.put("receiptSha256", "f".repeat(64));
		rebindHandoff(receiptSelfDrift);
		assertReason(
			RouteBundleHandoffException.Reason.PUBLICATION_RECEIPT_IDENTITY_MISMATCH,
			canonicalBytes(receiptSelfDrift),
			ACTIVATION_REQUEST);

		var candidateDrift = fixture.node().deepCopy();
		((ObjectNode) candidateDrift.path("publicationReceipt").path("candidate"))
			.put("sourceSnapshotSetHash", "f".repeat(64));
		rebindReceiptAndHandoff(candidateDrift);
		assertReason(
			RouteBundleHandoffException.Reason.MANIFEST_IDENTITY_MISMATCH,
			canonicalBytes(candidateDrift),
			ACTIVATION_REQUEST);

		var payloadSizeDrift = fixture.node().deepCopy();
		((ObjectNode) payloadSizeDrift.path("publicationReceipt").path("objects").path(3))
			.put("sizeBytes", 99);
		rebindReceiptAndHandoff(payloadSizeDrift);
		assertReason(
			RouteBundleHandoffException.Reason.PUBLICATION_RECEIPT_IDENTITY_MISMATCH,
			canonicalBytes(payloadSizeDrift),
			ACTIVATION_REQUEST);

		var receiptKindDrift = fixture.node().deepCopy();
		((ObjectNode) receiptKindDrift.path("publicationReceipt"))
			.put("artifactKind", "legacy-publication-receipt");
		rebindReceiptAndHandoff(receiptKindDrift);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(receiptKindDrift),
			ACTIVATION_REQUEST);

		var repositoryShaDrift = fixture.node().deepCopy();
		((ObjectNode) repositoryShaDrift.path("publicationReceipt").path("repository"))
			.put("gitSha", "not-a-git-sha");
		rebindReceiptAndHandoff(repositoryShaDrift);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(repositoryShaDrift),
			ACTIVATION_REQUEST);

		var locatorDrift = fixture.node().deepCopy();
		((ObjectNode) locatorDrift.path("publicationReceipt").path("locator"))
			.put("publicBaseUrl", "https://example.invalid/o");
		rebindReceiptAndHandoff(locatorDrift);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(locatorDrift),
			ACTIVATION_REQUEST);

		var objectCountDrift = fixture.node().deepCopy();
		((ArrayNode) objectCountDrift.path("publicationReceipt").path("objects")).remove(7);
		rebindReceiptAndHandoff(objectCountDrift);
		assertReason(
			RouteBundleHandoffException.Reason.PUBLICATION_RECEIPT_IDENTITY_MISMATCH,
			canonicalBytes(objectCountDrift),
			ACTIVATION_REQUEST);

		var objectContainerDrift = fixture.node().deepCopy();
		((ArrayNode) objectContainerDrift.path("publicationReceipt").path("objects"))
			.set(0, JSON.getNodeFactory().textNode("not-an-object"));
		rebindReceiptAndHandoff(objectContainerDrift);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(objectContainerDrift),
			ACTIVATION_REQUEST);

		var objectPathTypeDrift = fixture.node().deepCopy();
		((ObjectNode) objectPathTypeDrift.path("publicationReceipt").path("objects").path(0))
			.put("path", 1);
		rebindReceiptAndHandoff(objectPathTypeDrift);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(objectPathTypeDrift),
			ACTIVATION_REQUEST);

		var objectSizeRangeDrift = fixture.node().deepCopy();
		((ObjectNode) objectSizeRangeDrift.path("publicationReceipt").path("objects").path(0))
			.put("sizeBytes", 0);
		rebindReceiptAndHandoff(objectSizeRangeDrift);
		assertReason(
			RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID,
			canonicalBytes(objectSizeRangeDrift),
			ACTIVATION_REQUEST);

		var releaseResultDrift = fixture.node().deepCopy();
		((ObjectNode) releaseResultDrift.path("release")).put("result", "NO_GO");
		rebindHandoff(releaseResultDrift);
		assertReason(
			RouteBundleHandoffException.Reason.RELEASE_EVIDENCE_IDENTITY_MISMATCH,
			canonicalBytes(releaseResultDrift),
			ACTIVATION_REQUEST);

		var releaseReceiptDrift = fixture.node().deepCopy();
		((ObjectNode) releaseReceiptDrift.path("release"))
			.put("publicationReceiptSha256", "f".repeat(64));
		rebindHandoff(releaseReceiptDrift);
		assertReason(
			RouteBundleHandoffException.Reason.RELEASE_EVIDENCE_IDENTITY_MISMATCH,
			canonicalBytes(releaseReceiptDrift),
			ACTIVATION_REQUEST);

		var releaseReferenceDrift = fixture.node().deepCopy();
		((ObjectNode) releaseReferenceDrift.path("backendAdmission"))
			.put("promotionEvidenceReference", "sha256:" + "f".repeat(64));
		rebindHandoff(releaseReferenceDrift);
		assertReason(
			RouteBundleHandoffException.Reason.RELEASE_EVIDENCE_IDENTITY_MISMATCH,
			canonicalBytes(releaseReferenceDrift),
			ACTIVATION_REQUEST);
	}

	private static void assertReason(
		RouteBundleHandoffException.Reason reason,
		byte[] bytes,
		String activationRequestIdentity) {
		assertThatThrownBy(() -> RouteBundleConsumerHandoffParser.parse(bytes, activationRequestIdentity))
			.isInstanceOf(RouteBundleHandoffException.class)
			.extracting(error -> ((RouteBundleHandoffException) error).reason())
			.isEqualTo(reason);
	}

	private static void assertDescriptorReason(
		RouteBundleHandoffException.Reason reason,
		byte[] bytes,
		String activationRequestIdentity) {
		assertThatThrownBy(() -> RouteBundleConsumerHandoffParser.parsePublicationDescriptor(
			bytes, activationRequestIdentity))
			.isInstanceOf(RouteBundleHandoffException.class)
			.extracting(error -> ((RouteBundleHandoffException) error).reason())
			.isEqualTo(reason);
	}

	private static DescriptorFixture descriptorFixture() {
		var handoff = fixture();
		ObjectNode descriptor = handoff.node().deepCopy();
		descriptor.put("schemaVersion", 2);
		descriptor.put("artifactKind", "server-route-bundle-publication-descriptor");
		var producer = descriptor.putObject("producer");
		producer.put("repository", "AquilaXk/easysubway-data");
		producer.put("gitSha", "9".repeat(40));
		descriptor.remove(List.of("backendAdmission", "platformRelease", "handoffSha256"));
		try {
			rebindDescriptor(descriptor);
			byte[] bytes = canonicalBytes(descriptor);
			return new DescriptorFixture(
				bytes,
				new String(bytes, StandardCharsets.UTF_8),
				descriptor,
				handoff.manifestSha256(),
				handoff.receiptRawSha256(),
				descriptor.path("descriptorSha256").textValue());
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException(exception);
		}
	}

	static Fixture fixture() {
		return fixture("production-v1", "AQID");
	}

	static Fixture fixture(String keyId, String signature) {
		return fixture(keyId, signature, 7);
	}

	static Fixture fixture(String keyId, String signature, long releaseSequence) {
		List<Map<String, Object>> payloadInventory = List.of(
			map("path", "payload/accessibility.sqlite.zst", "sizeBytes", 15, "sha256", "4".repeat(64)),
			map("path", "payload/fare.sqlite.zst", "sizeBytes", 16, "sha256", "5".repeat(64)),
			map("path", "payload/timetable.sqlite.zst", "sizeBytes", 17, "sha256", "3".repeat(64)),
			map("path", "payload/topology.sqlite.zst", "sizeBytes", 18, "sha256", "2".repeat(64)));
		String payloadSha256 = sha256(canonicalBytes(payloadInventory));
		Map<String, Object> signingInput = map(
			"manifestVersion", 1,
			"artifactKind", "server-route-bundle",
			"bundleId", "server-route-bundle-20990101",
			"releaseSequence", releaseSequence,
			"stationSetSha256", "0".repeat(64),
			"payloadSha256", payloadSha256,
			"topologySha256", "2".repeat(64),
			"timetableSha256", "3".repeat(64),
			"accessibilitySha256", "4".repeat(64),
			"fareSha256", "5".repeat(64),
			"provenanceSha256", "6".repeat(64),
			"compatibilitySha256", "7".repeat(64),
			"serviceTimezone", "Asia/Seoul",
			"activeFrom", "2099-01-01T09:00:00.000+09:00",
			"freshUntil", "2099-02-01T09:00:00.000+09:00",
			"schemaCompatibility", map("backendMin", 3, "backendMax", 3),
			"keyId", keyId);
		byte[] signingInputBytes = canonicalBytes(signingInput);
		String signingInputSha256 = sha256(signingInputBytes);
		Map<String, Object> manifest = new LinkedHashMap<>(signingInput);
		manifest.put("signature", map(
			"algorithm", "rsa-sha256-server-route-bundle-v1",
			"value", signature));
		String manifestSha256 = sha256(canonicalBytes(manifest));
		String objectPrefix = "server-route-bundles/v1/" + manifestSha256 + "/";
		List<Map<String, Object>> objects = List.of(
			published(objectPrefix, "compatibility.json", 12, "7".repeat(64)),
			published(objectPrefix, "manifest.json", 13, manifestSha256),
			published(objectPrefix, "manifest.signing-input.json", 14, signingInputSha256),
			published(objectPrefix, "payload/accessibility.sqlite.zst", 15, "4".repeat(64)),
			published(objectPrefix, "payload/fare.sqlite.zst", 16, "5".repeat(64)),
			published(objectPrefix, "payload/timetable.sqlite.zst", 17, "3".repeat(64)),
			published(objectPrefix, "payload/topology.sqlite.zst", 18, "2".repeat(64)),
			published(objectPrefix, "provenance.json", 19, "6".repeat(64)));
		Map<String, Object> candidate = map(
			"bundleId", signingInput.get("bundleId"),
			"releaseSequence", signingInput.get("releaseSequence"),
			"stationSetSha256", signingInput.get("stationSetSha256"),
			"sourceSnapshotSetHash", "8".repeat(64),
			"signingInputSha256", signingInputSha256,
			"signedManifestRawSha256", manifestSha256,
			"payloadRootSha256", signingInput.get("payloadSha256"),
			"componentInventorySha256", signingInput.get("payloadSha256"),
			"componentDigests", map(
				"accessibility", signingInput.get("accessibilitySha256"),
				"fare", signingInput.get("fareSha256"),
				"timetable", signingInput.get("timetableSha256"),
				"topology", signingInput.get("topologySha256")),
			"activeFrom", signingInput.get("activeFrom"),
			"freshUntil", signingInput.get("freshUntil"),
			"keyId", signingInput.get("keyId"),
			"prePublicationFinalSha256", "a".repeat(64));
		Map<String, Object> receiptPayload = map(
			"schemaVersion", 1,
			"artifactKind", "server-route-bundle-publication-receipt",
			"repository", map("name", "AquilaXk/easysubway-data", "gitSha", "9".repeat(40)),
			"candidate", candidate,
			"locator", map(
				"publicBaseUrl", "https://objectstorage.ap-seoul-1.oraclecloud.com/n/testnamespace/b/easysubway-route-bundles/o",
				"objectPrefix", objectPrefix),
			"objects", objects);
		String receiptSha256 = sha256(canonicalBytes(receiptPayload));
		Map<String, Object> receipt = new LinkedHashMap<>(receiptPayload);
		receipt.put("receiptSha256", receiptSha256);
		String receiptRawSha256 = sha256(canonicalBytes(receipt));
		Map<String, Object> handoffPayload = map(
			"schemaVersion", 1,
			"artifactKind", "server-route-bundle-consumer-handoff",
			"manifest", manifest,
			"sourceSnapshotSetHash", "8".repeat(64),
			"publicationReceipt", receipt,
			"release", map(
				"result", "GO",
				"finalSha256", "a".repeat(64),
				"finalRawSha256", "b".repeat(64),
				"publicationReceiptSha256", receiptSha256,
				"publicationReceiptRawSha256", receiptRawSha256,
				"promotionEvidenceSha256", "d".repeat(64)),
			"backendAdmission", map(
				"manifestSha256", manifestSha256,
				"finalEvidenceReference", "sha256:" + "b".repeat(64),
				"promotionEvidenceReference", "sha256:" + "d".repeat(64),
				"immutablePublicationReceiptIdentity", "sha256:" + receiptRawSha256),
			"platformRelease", map("serverRouteBundleDigest", "sha256:" + manifestSha256));
		String handoffSha256 = sha256(canonicalBytes(handoffPayload));
		Map<String, Object> handoff = new LinkedHashMap<>(handoffPayload);
		handoff.put("handoffSha256", handoffSha256);
		byte[] bytes = canonicalBytes(handoff);
		try {
			return new Fixture(bytes, signingInputBytes, new String(bytes, StandardCharsets.UTF_8),
				(ObjectNode) JSON.readTree(bytes), manifestSha256, receiptRawSha256, handoffSha256,
				payloadSha256);
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Map<String, Object> published(String prefix, String path, long sizeBytes, String sha256) {
		return map("path", path, "objectKey", prefix + path, "sizeBytes", sizeBytes, "sha256", sha256);
	}

	private static void rebindReceiptAndHandoff(ObjectNode handoff) throws JsonProcessingException {
		ObjectNode receipt = (ObjectNode) handoff.path("publicationReceipt");
		receipt.remove("receiptSha256");
		String receiptSha256 = sha256(canonicalBytes(receipt));
		receipt.put("receiptSha256", receiptSha256);
		String raw = sha256(canonicalBytes(receipt));
		ObjectNode release = (ObjectNode) handoff.path("release");
		release.put("publicationReceiptSha256", receiptSha256);
		release.put("publicationReceiptRawSha256", raw);
		((ObjectNode) handoff.path("backendAdmission"))
			.put("immutablePublicationReceiptIdentity", "sha256:" + raw);
		rebindHandoff(handoff);
	}

	private static void rebindHandoff(ObjectNode handoff) throws JsonProcessingException {
		handoff.remove("handoffSha256");
		handoff.put("handoffSha256", sha256(canonicalBytes(handoff)));
	}

	private static void rebindDescriptor(ObjectNode descriptor) throws JsonProcessingException {
		descriptor.remove("descriptorSha256");
		descriptor.put("descriptorSha256", sha256(canonicalBytes(descriptor)));
	}

	private static byte[] canonicalBytes(JsonNode value) throws JsonProcessingException {
		return JSON.writeValueAsBytes(sortNode(value));
	}

	private static byte[] canonicalBytes(Object value) {
		try {
			return JSON.writeValueAsBytes(sortValue(value));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static JsonNode sortNode(JsonNode value) {
		return JSON.valueToTree(sortValue(JSON.convertValue(value, Object.class)));
	}

	private static Object sortValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			var sorted = new TreeMap<String, Object>();
			for (var entry : map.entrySet()) {
				sorted.put(String.valueOf(entry.getKey()), sortValue(entry.getValue()));
			}
			return sorted;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(RouteBundleConsumerHandoffParserTest::sortValue).toList();
		}
		return value;
	}

	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Map<String, Object> map(Object... entries) {
		if (entries.length % 2 != 0) {
			throw new IllegalArgumentException("map entries must be key-value pairs");
		}
		var result = new LinkedHashMap<String, Object>();
		for (int index = 0; index < entries.length; index += 2) {
			result.put((String) entries[index], entries[index + 1]);
		}
		return result;
	}

	record Fixture(
		byte[] bytes,
		byte[] signingInputBytes,
		String json,
		ObjectNode node,
		String manifestSha256,
		String receiptRawSha256,
		String handoffSha256,
		String payloadSha256) {
	}

	record DescriptorFixture(
		byte[] bytes,
		String json,
		ObjectNode node,
		String manifestSha256,
		String receiptRawSha256,
		String descriptorSha256) {
	}
}
