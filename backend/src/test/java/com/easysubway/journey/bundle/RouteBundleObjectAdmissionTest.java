package com.easysubway.journey.bundle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RouteBundleObjectAdmissionTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	static final String ACTIVATION_REQUEST = "sha256:" + "e".repeat(64);
	private static final List<String> PATHS = List.of(
		"compatibility.json",
		"manifest.json",
		"manifest.signing-input.json",
		"payload/accessibility.sqlite.zst",
		"payload/fare.sqlite.zst",
		"payload/timetable.sqlite.zst",
		"payload/topology.sqlite.zst",
		"provenance.json");

	@Test
	void admitsExactEightSignedObjectsAndDefensivelyCopiesEveryByteArray() throws Exception {
		Fixture fixture = fixture();
		Map<String, byte[]> input = fixture.mutableObjects();

		var admission = RouteBundleObjectAdmission.admit(
			fixture.handoffBytes(), ACTIVATION_REQUEST, input, fixture.currentKey());

		assertEquals(PATHS, new ArrayList<>(admission.objects().keySet()));
		assertEquals(fixture.handoffSha256(), admission.verifiedSignature().handoff().handoffSha256());
		assertEquals("launch-2026", admission.verifiedSignature().keyId());
		assertEquals(sha(fixture.objects().get("manifest.signing-input.json")),
			admission.verifiedSignature().signingInputSha256());

		input.get("compatibility.json")[0] ^= 1;
		byte[] firstRead = admission.objectBytes("compatibility.json");
		assertArrayEquals(fixture.objects().get("compatibility.json"), firstRead);
		firstRead[0] ^= 1;
		assertArrayEquals(fixture.objects().get("compatibility.json"),
			admission.objectBytes("compatibility.json"));
		byte[] mapRead = admission.objects().get("manifest.json");
		mapRead[0] ^= 1;
		assertArrayEquals(fixture.objects().get("manifest.json"), admission.objectBytes("manifest.json"));
		assertThrows(IllegalArgumentException.class, () -> admission.objectBytes("unknown.json"));
	}

	@Test
	void admitsFetchedDescriptorV2ObjectsAndBindsTheCurrentKey() throws Exception {
		Fixture fixture = fixture();
		var fetched = fixture.fetchedObjects();

		var admission = RouteBundleObjectAdmission.admitPublicationDescriptor(
			fixture.descriptorBytes(), ACTIVATION_REQUEST, fetched, fixture.currentKey());

		assertEquals(PATHS, new ArrayList<>(admission.objects().keySet()));
		assertEquals(fixture.descriptorSha256(),
			admission.verifiedDescriptorSignature().descriptor().descriptorSha256());
		assertEquals("launch-2026", admission.verifiedDescriptorSignature().keyId());
		assertEquals(sha(fixture.objects().get("manifest.signing-input.json")),
			admission.verifiedDescriptorSignature().signingInputSha256());
		byte[] read = admission.objectBytes("manifest.json");
		read[0] ^= 1;
		assertArrayEquals(fixture.objects().get("manifest.json"), admission.objectBytes("manifest.json"));
	}

	@Test
	void rejectsMixedFetchedDescriptorIdentityBeforeCurrentKeyAdmission() throws Exception {
		Fixture fixture = fixture();
		var fetched = new RouteBundlePublicationObjectFetcher.FetchedPublicationObjects(
			"f".repeat(64), "launch-2026", fixture.fetchedObjects().objects());

		assertAdmissionReason(
			RouteBundleObjectAdmission.Reason.FETCHED_DESCRIPTOR_IDENTITY_MISMATCH,
			() -> RouteBundleObjectAdmission.admitPublicationDescriptor(
				fixture.descriptorBytes(), ACTIVATION_REQUEST, fetched, fixture.currentKey()));
	}

	@Test
	void rejectsMissingExtraAndAliasedPathsBeforeSignatureAdmission() throws Exception {
		Fixture fixture = fixture();
		assertAdmissionReason(
			RouteBundleObjectAdmission.Reason.OBJECT_PATH_SET_MISMATCH,
			() -> RouteBundleObjectAdmission.admit(
				fixture.handoffBytes(), ACTIVATION_REQUEST, null, fixture.currentKey()));
		for (Map<String, byte[]> invalid : List.of(
			without(fixture.mutableObjects(), "compatibility.json"),
			with(fixture.mutableObjects(), "unknown.json", bytes("unknown")),
			with(without(fixture.mutableObjects(), "compatibility.json"),
				"./compatibility.json", fixture.objects().get("compatibility.json")))) {
			assertAdmissionReason(
				RouteBundleObjectAdmission.Reason.OBJECT_PATH_SET_MISMATCH,
				() -> RouteBundleObjectAdmission.admit(
					fixture.handoffBytes(), ACTIVATION_REQUEST, invalid, fixture.currentKey()));
		}
	}

	@Test
	void rejectsNullEmptySizeAndDigestMismatchWithoutIssuingAToken() throws Exception {
		Fixture fixture = fixture();
		var nullObject = fixture.mutableObjects();
		nullObject.put("compatibility.json", null);
		assertAdmissionReason(
			RouteBundleObjectAdmission.Reason.OBJECT_BYTES_INVALID,
			() -> RouteBundleObjectAdmission.admit(
				fixture.handoffBytes(), ACTIVATION_REQUEST, nullObject, fixture.currentKey()));

		assertAdmissionReason(
			RouteBundleObjectAdmission.Reason.OBJECT_BYTES_INVALID,
			() -> RouteBundleObjectAdmission.admit(
				fixture.handoffBytes(), ACTIVATION_REQUEST,
				with(fixture.mutableObjects(), "compatibility.json", new byte[0]), fixture.currentKey()));

		byte[] longer = java.util.Arrays.copyOf(
			fixture.objects().get("compatibility.json"),
			fixture.objects().get("compatibility.json").length + 1);
		assertAdmissionReason(
			RouteBundleObjectAdmission.Reason.OBJECT_SIZE_MISMATCH,
			() -> RouteBundleObjectAdmission.admit(
				fixture.handoffBytes(), ACTIVATION_REQUEST,
				with(fixture.mutableObjects(), "compatibility.json", longer), fixture.currentKey()));

		byte[] mutated = fixture.objects().get("compatibility.json").clone();
		mutated[0] ^= 1;
		assertAdmissionReason(
			RouteBundleObjectAdmission.Reason.OBJECT_DIGEST_MISMATCH,
			() -> RouteBundleObjectAdmission.admit(
				fixture.handoffBytes(), ACTIVATION_REQUEST,
				with(fixture.mutableObjects(), "compatibility.json", mutated), fixture.currentKey()));
	}

	@Test
	void passesOnlyAdmittedSigningInputToTheExistingCurrentKeyVerifier() throws Exception {
		Fixture fixture = fixture();
		KeyPair otherKey = rsaKeyPair();

		assertVerificationReason(
			RouteBundleCurrentKeyVerifier.Reason.CURRENT_KEY_ID_MISMATCH,
			() -> RouteBundleObjectAdmission.admit(
				fixture.handoffBytes(), ACTIVATION_REQUEST, fixture.mutableObjects(),
				new RouteBundleCurrentKeyVerifier.CurrentKey("old-key", fixture.currentKey().publicKeyPem())));
		assertVerificationReason(
			RouteBundleCurrentKeyVerifier.Reason.CURRENT_KEY_CONFIG_INVALID,
			() -> RouteBundleObjectAdmission.admit(
				fixture.handoffBytes(), ACTIVATION_REQUEST, fixture.mutableObjects(),
				new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2026", "not-a-public-key")));
		assertVerificationReason(
			RouteBundleCurrentKeyVerifier.Reason.MANIFEST_SIGNATURE_INVALID,
			() -> RouteBundleObjectAdmission.admit(
				fixture.handoffBytes(), ACTIVATION_REQUEST, fixture.mutableObjects(),
				new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2026", pem(otherKey.getPublic()))));
	}

	@Test
	void reparsesRawHandoffBeforeInspectingCallerOwnedObjects() throws Exception {
		Fixture fixture = fixture();
		var failure = assertThrows(
			RouteBundleHandoffException.class,
			() -> RouteBundleObjectAdmission.admit(
				null, ACTIVATION_REQUEST, null, fixture.currentKey()));
		assertEquals(RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID, failure.reason());
	}

	static Fixture fixture() throws Exception {
		KeyPair keyPair = rsaKeyPair();
		var payloads = new LinkedHashMap<String, byte[]>();
		payloads.put("payload/accessibility.sqlite.zst", bytes("accessibility"));
		payloads.put("payload/fare.sqlite.zst", bytes("fare"));
		payloads.put("payload/timetable.sqlite.zst", bytes("timetable"));
		payloads.put("payload/topology.sqlite.zst", bytes("topology"));
		byte[] compatibility = bytes("{\"backendMax\":3,\"backendMin\":3}");
		byte[] provenance = bytes("{\"source\":\"synthetic-test\"}");

		List<Map<String, Object>> payloadInventory = payloads.entrySet().stream()
			.map(entry -> map(
				"path", entry.getKey(),
				"sizeBytes", entry.getValue().length,
				"sha256", sha(entry.getValue())))
			.toList();
		String payloadSha256 = sha(canonicalBytes(payloadInventory));
		Map<String, Object> signingInput = map(
			"manifestVersion", 1,
			"artifactKind", "server-route-bundle",
			"bundleId", "server-route-bundle-20990101",
			"releaseSequence", 7,
			"stationSetSha256", "0".repeat(64),
			"payloadSha256", payloadSha256,
			"topologySha256", sha(payloads.get("payload/topology.sqlite.zst")),
			"timetableSha256", sha(payloads.get("payload/timetable.sqlite.zst")),
			"accessibilitySha256", sha(payloads.get("payload/accessibility.sqlite.zst")),
			"fareSha256", sha(payloads.get("payload/fare.sqlite.zst")),
			"provenanceSha256", sha(provenance),
			"compatibilitySha256", sha(compatibility),
			"serviceTimezone", "Asia/Seoul",
			"activeFrom", "2099-01-01T09:00:00.000+09:00",
			"freshUntil", "2099-02-01T09:00:00.000+09:00",
			"schemaCompatibility", map("backendMin", 3, "backendMax", 3),
			"keyId", "launch-2026");
		byte[] signingInputBytes = canonicalBytes(signingInput);
		Map<String, Object> manifest = new LinkedHashMap<>(signingInput);
		manifest.put("signature", map(
			"algorithm", "rsa-sha256-server-route-bundle-v1",
			"value", sign(keyPair.getPrivate(), signingInputBytes)));
		byte[] manifestBytes = canonicalBytes(manifest);
		String manifestSha256 = sha(manifestBytes);

		var objects = new LinkedHashMap<String, byte[]>();
		objects.put("compatibility.json", compatibility);
		objects.put("manifest.json", manifestBytes);
		objects.put("manifest.signing-input.json", signingInputBytes);
		objects.putAll(payloads);
		objects.put("provenance.json", provenance);

		String objectPrefix = "server-route-bundles/v1/" + manifestSha256 + "/";
		List<Map<String, Object>> publishedObjects = PATHS.stream()
			.map(path -> published(objectPrefix, path, objects.get(path)))
			.toList();
		Map<String, Object> candidate = map(
			"bundleId", signingInput.get("bundleId"),
			"releaseSequence", signingInput.get("releaseSequence"),
			"stationSetSha256", signingInput.get("stationSetSha256"),
			"sourceSnapshotSetHash", "8".repeat(64),
			"signingInputSha256", sha(signingInputBytes),
			"signedManifestRawSha256", manifestSha256,
			"payloadRootSha256", payloadSha256,
			"componentInventorySha256", payloadSha256,
			"componentDigests", map(
				"accessibility", sha(payloads.get("payload/accessibility.sqlite.zst")),
				"fare", sha(payloads.get("payload/fare.sqlite.zst")),
				"timetable", sha(payloads.get("payload/timetable.sqlite.zst")),
				"topology", sha(payloads.get("payload/topology.sqlite.zst"))),
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
			"objects", publishedObjects);
		String receiptSha256 = sha(canonicalBytes(receiptPayload));
		Map<String, Object> receipt = new LinkedHashMap<>(receiptPayload);
		receipt.put("receiptSha256", receiptSha256);
		String receiptRawSha256 = sha(canonicalBytes(receipt));
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
		String handoffSha256 = sha(canonicalBytes(handoffPayload));
		Map<String, Object> handoff = new LinkedHashMap<>(handoffPayload);
		handoff.put("handoffSha256", handoffSha256);
		Map<String, Object> descriptor = new LinkedHashMap<>(handoffPayload);
		descriptor.put("schemaVersion", 2);
		descriptor.put("artifactKind", "server-route-bundle-publication-descriptor");
		descriptor.put("producer", map(
			"repository", "AquilaXk/easysubway-data",
			"gitSha", "9".repeat(40)));
		descriptor.remove("backendAdmission");
		descriptor.remove("platformRelease");
		descriptor.put("descriptorSha256", sha(canonicalBytes(descriptor)));
		return new Fixture(
			canonicalBytes(handoff), canonicalBytes(descriptor), copyObjects(objects), objectPrefix,
			new RouteBundleCurrentKeyVerifier.CurrentKey("launch-2026", pem(keyPair.getPublic())),
			handoffSha256, String.valueOf(descriptor.get("descriptorSha256")));
	}

	private static Map<String, byte[]> without(Map<String, byte[]> source, String path) {
		source.remove(path);
		return source;
	}

	private static Map<String, byte[]> with(Map<String, byte[]> source, String path, byte[] value) {
		source.put(path, value);
		return source;
	}

	private static Map<String, byte[]> copyObjects(Map<String, byte[]> source) {
		var copy = new LinkedHashMap<String, byte[]>();
		for (var entry : source.entrySet()) copy.put(entry.getKey(), entry.getValue().clone());
		return copy;
	}

	private static Map<String, Object> published(String prefix, String path, byte[] value) {
		return map("path", path, "objectKey", prefix + path, "sizeBytes", value.length, "sha256", sha(value));
	}

	private static KeyPair rsaKeyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	private static String sign(PrivateKey privateKey, byte[] value) throws Exception {
		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(privateKey);
		signer.update(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
	}

	private static String pem(PublicKey publicKey) {
		return "-----BEGIN PUBLIC KEY-----\n"
			+ Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(publicKey.getEncoded())
			+ "\n-----END PUBLIC KEY-----";
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static String sha(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		} catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static byte[] canonicalBytes(Object value) {
		try {
			return JSON.writeValueAsBytes(sortValue(value));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Object sortValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			var sorted = new TreeMap<String, Object>();
			for (var entry : map.entrySet()) {
				sorted.put(String.valueOf(entry.getKey()), sortValue(entry.getValue()));
			}
			return sorted;
		}
		if (value instanceof List<?> list) return list.stream().map(RouteBundleObjectAdmissionTest::sortValue).toList();
		return value;
	}

	private static Map<String, Object> map(Object... entries) {
		var result = new LinkedHashMap<String, Object>();
		for (int index = 0; index < entries.length; index += 2) {
			result.put((String) entries[index], entries[index + 1]);
		}
		return result;
	}

	private static void assertAdmissionReason(
		RouteBundleObjectAdmission.Reason expected,
		org.junit.jupiter.api.function.Executable executable) {
		var failure = assertThrows(RouteBundleObjectAdmission.AdmissionException.class, executable);
		assertEquals(expected, failure.reason());
	}

	private static void assertVerificationReason(
		RouteBundleCurrentKeyVerifier.Reason expected,
		org.junit.jupiter.api.function.Executable executable) {
		var failure = assertThrows(RouteBundleCurrentKeyVerifier.VerificationException.class, executable);
		assertEquals(expected, failure.reason());
	}

	static record Fixture(
		byte[] handoffBytes,
		byte[] descriptorBytes,
		Map<String, byte[]> objects,
		String objectPrefix,
		RouteBundleCurrentKeyVerifier.CurrentKey currentKey,
		String handoffSha256,
		String descriptorSha256) {

		private Map<String, byte[]> mutableObjects() {
			return copyObjects(objects);
		}

		private RouteBundlePublicationObjectFetcher.FetchedPublicationObjects fetchedObjects() {
			return new RouteBundlePublicationObjectFetcher.FetchedPublicationObjects(
				descriptorSha256,
				currentKey.keyId(),
				PATHS.stream().map(path -> new RouteBundlePublicationObjectFetcher.FetchedObject(
					path, objectPrefix + path, objects.get(path))).toList());
		}
	}
}
