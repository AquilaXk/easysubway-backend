package com.easysubway.journey.bundle;

import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.ACTIVATION_REQUEST_IDENTITY_INVALID;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.HANDOFF_CANONICAL_BYTES_MISMATCH;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.HANDOFF_SCHEMA_INVALID;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.HANDOFF_SELF_DIGEST_MISMATCH;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.HANDOFF_UTF8_OR_JSON_INVALID;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.MANIFEST_IDENTITY_MISMATCH;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.PUBLICATION_RECEIPT_IDENTITY_MISMATCH;
import static com.easysubway.journey.bundle.RouteBundleHandoffException.Reason.RELEASE_EVIDENCE_IDENTITY_MISMATCH;

import com.easysubway.journey.bundle.RouteBundleConsumerHandoff.PublicationLocator;
import com.easysubway.journey.bundle.RouteBundleConsumerHandoff.PublishedObject;
import com.easysubway.journey.bundle.RouteBundleConsumerHandoff.ReleaseEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses only the closed Data handoff envelope; later verifiers own trust admission. */
public final class RouteBundleConsumerHandoffParser {

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();
	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
	private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern GIT_SHA = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern SHA_256_REFERENCE = Pattern.compile("sha256:[0-9a-f]{64}");
	private static final Pattern OCI_PUBLIC_BASE = Pattern.compile(
		"https://objectstorage\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.oraclecloud\\.com/"
			+ "n/[A-Za-z0-9_~-](?:[A-Za-z0-9._~-]*[A-Za-z0-9_~-])?/"
			+ "b/[A-Za-z0-9_~-](?:[A-Za-z0-9._~-]*[A-Za-z0-9_~-])?/o");
	private static final List<String> OBJECT_PATHS = List.of(
		"compatibility.json",
		"manifest.json",
		"manifest.signing-input.json",
		"payload/accessibility.sqlite.zst",
		"payload/fare.sqlite.zst",
		"payload/timetable.sqlite.zst",
		"payload/topology.sqlite.zst",
		"provenance.json");

	private RouteBundleConsumerHandoffParser() {
	}

	public static RouteBundleConsumerHandoff parse(byte[] handoffBytes, String activationRequestIdentity) {
		requireActivationRequestIdentity(activationRequestIdentity);
		ObjectNode root = parseCanonicalRoot(handoffBytes, "handoff");
		requireKeys(root, Set.of(
			"schemaVersion", "artifactKind", "manifest", "sourceSnapshotSetHash",
			"publicationReceipt", "release", "backendAdmission", "platformRelease", "handoffSha256"),
			"handoff");
		requireInteger(root, "schemaVersion", 1, 1, "handoff");
		requireExactText(root, "artifactKind", "server-route-bundle-consumer-handoff", "handoff");
		String handoffSha256 = requireSha256(root, "handoffSha256", "handoff");
		ObjectNode handoffPayload = root.deepCopy();
		handoffPayload.remove("handoffSha256");
		if (!handoffSha256.equals(sha256(canonicalBytes(
			handoffPayload, HANDOFF_UTF8_OR_JSON_INVALID, "handoff digest canonicalization failed")))) {
			throw failure(HANDOFF_SELF_DIGEST_MISMATCH, "handoffSha256 mismatch");
		}

		PublicationFacts facts = parsePublicationFacts(root, "handoff");
		RouteBundleAdmissionEvidence admissionEvidence = parseBackendAdmission(
			requireObject(root, "backendAdmission", "handoff"),
			facts.manifestSha256(),
			facts.release(),
			activationRequestIdentity);
		String platformDigest = parsePlatformRelease(
			requireObject(root, "platformRelease", "handoff"), facts.manifestSha256());

		return new RouteBundleConsumerHandoff(
			facts.receipt().repositoryGitSha(),
			facts.identity(),
			facts.sourceSnapshotSetHash(),
			admissionEvidence,
			facts.receipt().locator(),
			facts.receipt().objects(),
			facts.receipt().prePublicationFinalSha256(),
			facts.release(),
			platformDigest,
			handoffSha256);
	}

	public static RouteBundlePublicationDescriptor parsePublicationDescriptor(
		byte[] descriptorBytes,
		String activationRequestIdentity) {
		requireActivationRequestIdentity(activationRequestIdentity);
		ObjectNode root = parseCanonicalRoot(descriptorBytes, "publication descriptor");
		requireKeys(root, Set.of(
			"schemaVersion", "artifactKind", "producer", "manifest", "sourceSnapshotSetHash",
			"publicationReceipt", "release", "descriptorSha256"), "publicationDescriptor");
		requireInteger(root, "schemaVersion", 2, 2, "publicationDescriptor");
		requireExactText(
			root,
			"artifactKind",
			"server-route-bundle-publication-descriptor",
			"publicationDescriptor");
		String descriptorSha256 = requireSha256(root, "descriptorSha256", "publicationDescriptor");
		ObjectNode descriptorPayload = root.deepCopy();
		descriptorPayload.remove("descriptorSha256");
		if (!descriptorSha256.equals(sha256(canonicalBytes(
			descriptorPayload,
			HANDOFF_UTF8_OR_JSON_INVALID,
			"publication descriptor digest canonicalization failed")))) {
			throw failure(HANDOFF_SELF_DIGEST_MISMATCH, "descriptorSha256 mismatch");
		}

		ObjectNode producer = requireObject(root, "producer", "publicationDescriptor");
		requireKeys(producer, Set.of("repository", "gitSha"), "publicationDescriptor.producer");
		requireExactText(
			producer,
			"repository",
			"AquilaXk/easysubway-data",
			"publicationDescriptor.producer");
		String producerGitSha = requirePatternText(
			producer, "gitSha", GIT_SHA, "publicationDescriptor.producer");
		PublicationFacts facts = parsePublicationFacts(root, "publicationDescriptor");
		requireSame(
			producerGitSha,
			facts.receipt().repositoryGitSha(),
			"publication descriptor producer gitSha",
			PUBLICATION_RECEIPT_IDENTITY_MISMATCH);

		RouteBundleAdmissionEvidence admissionEvidence = createBackendAdmission(
			facts.manifestSha256(), facts.release(), activationRequestIdentity);
		var locator = facts.receipt().locator();
		var release = facts.release();
		return new RouteBundlePublicationDescriptor(
			producerGitSha,
			facts.identity(),
			facts.sourceSnapshotSetHash(),
			admissionEvidence,
			new RouteBundlePublicationDescriptor.PublicationLocator(
				locator.publicBaseUrl(), locator.objectPrefix()),
			facts.receipt().objects().stream()
				.map(object -> new RouteBundlePublicationDescriptor.PublishedObject(
					object.path(), object.objectKey(), object.sizeBytes(), object.sha256()))
				.toList(),
			facts.receipt().prePublicationFinalSha256(),
			new RouteBundlePublicationDescriptor.ReleaseEvidence(
				release.result(),
				release.finalSha256(),
				release.finalRawSha256(),
				release.publicationReceiptSha256(),
				release.publicationReceiptRawSha256(),
				release.promotionEvidenceSha256()),
			descriptorSha256);
	}

	private static PublicationFacts parsePublicationFacts(ObjectNode root, String label) {
		ObjectNode manifest = requireObject(root, "manifest", label);
		RouteBundleIdentity identity = parseManifest(manifest);
		String manifestSha256 = sha256(canonicalBytes(
			manifest, MANIFEST_IDENTITY_MISMATCH, "manifest canonicalization failed"));
		ObjectNode signingInput = manifest.deepCopy();
		signingInput.remove("signature");
		String signingInputSha256 = sha256(canonicalBytes(
			signingInput, MANIFEST_IDENTITY_MISMATCH, "signing input canonicalization failed"));
		String sourceSnapshotSetHash = requireSha256(root, "sourceSnapshotSetHash", label);
		Receipt receipt = parseReceipt(
			requireObject(root, "publicationReceipt", label),
			identity,
			manifestSha256,
			signingInputSha256,
			sourceSnapshotSetHash);
		ReleaseEvidence release = parseRelease(requireObject(root, "release", label), receipt);
		return new PublicationFacts(
			identity, manifestSha256, sourceSnapshotSetHash, receipt, release);
	}

	private static RouteBundleIdentity parseManifest(ObjectNode manifest) {
		requireKeys(manifest, Set.of(
			"manifestVersion", "artifactKind", "bundleId", "releaseSequence", "stationSetSha256",
			"payloadSha256", "topologySha256", "timetableSha256", "accessibilitySha256", "fareSha256",
			"provenanceSha256", "compatibilitySha256", "serviceTimezone", "activeFrom", "freshUntil",
			"schemaCompatibility", "keyId", "signature"), "manifest");
		ObjectNode compatibility = requireObject(manifest, "schemaCompatibility", "manifest");
		requireKeys(compatibility, Set.of("backendMin", "backendMax"), "schemaCompatibility");
		ObjectNode signature = requireObject(manifest, "signature", "manifest");
		requireKeys(signature, Set.of("algorithm", "value"), "signature");
		try {
			return new RouteBundleIdentity(
				(int) requireInteger(manifest, "manifestVersion", 1, 1, "manifest"),
				requireText(manifest, "artifactKind", "manifest"),
				requireText(manifest, "bundleId", "manifest"),
				requireInteger(manifest, "releaseSequence", 1, MAX_SAFE_INTEGER, "manifest"),
				requireSha256(manifest, "stationSetSha256", "manifest"),
				requireSha256(manifest, "payloadSha256", "manifest"),
				requireSha256(manifest, "topologySha256", "manifest"),
				requireSha256(manifest, "timetableSha256", "manifest"),
				requireSha256(manifest, "accessibilitySha256", "manifest"),
				requireSha256(manifest, "fareSha256", "manifest"),
				requireSha256(manifest, "provenanceSha256", "manifest"),
				requireSha256(manifest, "compatibilitySha256", "manifest"),
				requireText(manifest, "serviceTimezone", "manifest"),
				requireText(manifest, "activeFrom", "manifest"),
				requireText(manifest, "freshUntil", "manifest"),
				new RouteBundleIdentity.SchemaCompatibility(
					(int) requireInteger(compatibility, "backendMin", 3, 3, "schemaCompatibility"),
					(int) requireInteger(compatibility, "backendMax", 3, 3, "schemaCompatibility")),
				requireText(manifest, "keyId", "manifest"),
				new RouteBundleIdentity.Signature(
					requireText(signature, "algorithm", "signature"),
					requireText(signature, "value", "signature")));
		} catch (RouteBundleHandoffException exception) {
			throw exception;
		} catch (IllegalArgumentException exception) {
			throw failure(MANIFEST_IDENTITY_MISMATCH, "manifest identity is invalid", exception);
		}
	}

	private static Receipt parseReceipt(
		ObjectNode receipt,
		RouteBundleIdentity identity,
		String manifestSha256,
		String signingInputSha256,
		String sourceSnapshotSetHash) {
		requireKeys(receipt, Set.of(
			"schemaVersion", "artifactKind", "repository", "candidate", "locator", "objects", "receiptSha256"),
			"publicationReceipt");
		requireInteger(receipt, "schemaVersion", 1, 1, "publicationReceipt");
		requireExactText(receipt, "artifactKind", "server-route-bundle-publication-receipt", "publicationReceipt");
		String receiptSha256 = requireSha256(receipt, "receiptSha256", "publicationReceipt");
		ObjectNode receiptPayload = receipt.deepCopy();
		receiptPayload.remove("receiptSha256");
		if (!receiptSha256.equals(sha256(canonicalBytes(
			receiptPayload, PUBLICATION_RECEIPT_IDENTITY_MISMATCH, "receipt canonicalization failed")))) {
			throw failure(PUBLICATION_RECEIPT_IDENTITY_MISMATCH, "publication receipt self digest mismatch");
		}
		String receiptRawSha256 = sha256(canonicalBytes(
			receipt, PUBLICATION_RECEIPT_IDENTITY_MISMATCH, "receipt raw canonicalization failed"));

		ObjectNode repository = requireObject(receipt, "repository", "publicationReceipt");
		requireKeys(repository, Set.of("name", "gitSha"), "publicationReceipt.repository");
		requireExactText(repository, "name", "AquilaXk/easysubway-data", "publicationReceipt.repository");
		String repositoryGitSha = requirePatternText(
			repository, "gitSha", GIT_SHA, "publicationReceipt.repository");

		ObjectNode candidate = requireObject(receipt, "candidate", "publicationReceipt");
		requireKeys(candidate, Set.of(
			"bundleId", "releaseSequence", "stationSetSha256", "sourceSnapshotSetHash", "signingInputSha256",
			"signedManifestRawSha256", "payloadRootSha256", "componentInventorySha256", "componentDigests",
			"activeFrom", "freshUntil", "keyId", "prePublicationFinalSha256"), "publicationReceipt.candidate");
		ObjectNode componentDigests = requireObject(candidate, "componentDigests", "publicationReceipt.candidate");
		requireKeys(componentDigests, Set.of("accessibility", "fare", "timetable", "topology"),
			"publicationReceipt.candidate.componentDigests");
		requireSame(requireText(candidate, "bundleId", "publicationReceipt.candidate"), identity.bundleId(),
			"candidate bundleId");
		requireSame(requireInteger(candidate, "releaseSequence", 1, MAX_SAFE_INTEGER, "publicationReceipt.candidate"),
			identity.releaseSequence(), "candidate releaseSequence");
		requireSame(requireSha256(candidate, "stationSetSha256", "publicationReceipt.candidate"),
			identity.stationSetSha256(), "candidate stationSetSha256");
		requireSame(requireSha256(candidate, "sourceSnapshotSetHash", "publicationReceipt.candidate"),
			sourceSnapshotSetHash, "candidate sourceSnapshotSetHash");
		requireSame(requireSha256(candidate, "signingInputSha256", "publicationReceipt.candidate"),
			signingInputSha256, "candidate signingInputSha256");
		requireSame(requireSha256(candidate, "signedManifestRawSha256", "publicationReceipt.candidate"),
			manifestSha256, "candidate signedManifestRawSha256");
		requireSame(requireSha256(candidate, "payloadRootSha256", "publicationReceipt.candidate"),
			identity.payloadSha256(), "candidate payloadRootSha256");
		requireSame(requireSha256(candidate, "componentInventorySha256", "publicationReceipt.candidate"),
			identity.payloadSha256(), "candidate componentInventorySha256");
		requireSame(requireSha256(componentDigests, "accessibility", "candidate.componentDigests"),
			identity.accessibilitySha256(), "candidate accessibility digest");
		requireSame(requireSha256(componentDigests, "fare", "candidate.componentDigests"),
			identity.fareSha256(), "candidate fare digest");
		requireSame(requireSha256(componentDigests, "timetable", "candidate.componentDigests"),
			identity.timetableSha256(), "candidate timetable digest");
		requireSame(requireSha256(componentDigests, "topology", "candidate.componentDigests"),
			identity.topologySha256(), "candidate topology digest");
		requireSame(requireText(candidate, "activeFrom", "publicationReceipt.candidate"),
			identity.activeFrom(), "candidate activeFrom");
		requireSame(requireText(candidate, "freshUntil", "publicationReceipt.candidate"),
			identity.freshUntil(), "candidate freshUntil");
		requireSame(requireText(candidate, "keyId", "publicationReceipt.candidate"),
			identity.keyId(), "candidate keyId");
		String prePublicationFinalSha256 = requireSha256(
			candidate, "prePublicationFinalSha256", "publicationReceipt.candidate");

		ObjectNode locatorNode = requireObject(receipt, "locator", "publicationReceipt");
		requireKeys(locatorNode, Set.of("publicBaseUrl", "objectPrefix"), "publicationReceipt.locator");
		String publicBaseUrl = requirePatternText(
			locatorNode, "publicBaseUrl", OCI_PUBLIC_BASE, "publicationReceipt.locator");
		String objectPrefix = requireText(locatorNode, "objectPrefix", "publicationReceipt.locator");
		String expectedPrefix = "server-route-bundles/v1/" + manifestSha256 + "/";
		requireSame(
			objectPrefix,
			expectedPrefix,
			"publication objectPrefix",
			PUBLICATION_RECEIPT_IDENTITY_MISMATCH);

		JsonNode objectsNode = receipt.get("objects");
		if (!(objectsNode instanceof ArrayNode objectsArray) || objectsArray.size() != OBJECT_PATHS.size()) {
			throw failure(PUBLICATION_RECEIPT_IDENTITY_MISMATCH,
				"publication receipt objects must contain exact eight entries");
		}
		List<String> expectedDigests = List.of(
			identity.compatibilitySha256(),
			manifestSha256,
			signingInputSha256,
			identity.accessibilitySha256(),
			identity.fareSha256(),
			identity.timetableSha256(),
			identity.topologySha256(),
			identity.provenanceSha256());
		var objects = new ArrayList<PublishedObject>(OBJECT_PATHS.size());
		ArrayNode payloadInventory = NODES.arrayNode();
		for (int index = 0; index < OBJECT_PATHS.size(); index++) {
			ObjectNode object = requireObject(objectsArray.get(index), "publicationReceipt.objects[" + index + "]");
			requireKeys(object, Set.of("path", "objectKey", "sizeBytes", "sha256"),
				"publicationReceipt.objects[" + index + "]");
			String path = requireText(object, "path", "publicationReceipt.objects[" + index + "]");
			String objectKey = requireText(object, "objectKey", "publicationReceipt.objects[" + index + "]");
			long sizeBytes = requireInteger(
				object, "sizeBytes", 1, MAX_SAFE_INTEGER, "publicationReceipt.objects[" + index + "]");
			String digest = requireSha256(object, "sha256", "publicationReceipt.objects[" + index + "]");
			requireSame(
				path,
				OBJECT_PATHS.get(index),
				"publication object path",
				PUBLICATION_RECEIPT_IDENTITY_MISMATCH);
			requireSame(
				objectKey,
				expectedPrefix + path,
				"publication object key",
				PUBLICATION_RECEIPT_IDENTITY_MISMATCH);
			requireSame(
				digest,
				expectedDigests.get(index),
				"publication object digest",
				PUBLICATION_RECEIPT_IDENTITY_MISMATCH);
			objects.add(new PublishedObject(path, objectKey, sizeBytes, digest));
			if (path.startsWith("payload/")) {
				ObjectNode inventoryEntry = NODES.objectNode();
				inventoryEntry.put("path", path);
				inventoryEntry.put("sizeBytes", sizeBytes);
				inventoryEntry.put("sha256", digest);
				payloadInventory.add(inventoryEntry);
			}
		}
		String payloadInventorySha256 = sha256(canonicalBytes(
			payloadInventory,
			PUBLICATION_RECEIPT_IDENTITY_MISMATCH,
			"payload inventory canonicalization failed"));
		if (!payloadInventorySha256.equals(identity.payloadSha256())) {
			throw failure(PUBLICATION_RECEIPT_IDENTITY_MISMATCH,
				"publication receipt payload inventory digest mismatch");
		}
		return new Receipt(
			repositoryGitSha,
			new PublicationLocator(publicBaseUrl, objectPrefix),
			List.copyOf(objects),
			prePublicationFinalSha256,
			receiptSha256,
			receiptRawSha256);
	}

	private static ReleaseEvidence parseRelease(ObjectNode release, Receipt receipt) {
		requireKeys(release, Set.of(
			"result", "finalSha256", "finalRawSha256", "publicationReceiptSha256",
			"publicationReceiptRawSha256", "promotionEvidenceSha256"), "release");
		String result = requireText(release, "result", "release");
		if (!"GO".equals(result)) {
			throw failure(RELEASE_EVIDENCE_IDENTITY_MISMATCH, "release result must be GO");
		}
		String finalSha256 = requireSha256(release, "finalSha256", "release");
		String finalRawSha256 = requireSha256(release, "finalRawSha256", "release");
		String publicationReceiptSha256 = requireSha256(release, "publicationReceiptSha256", "release");
		String publicationReceiptRawSha256 = requireSha256(
			release, "publicationReceiptRawSha256", "release");
		String promotionEvidenceSha256 = requireSha256(release, "promotionEvidenceSha256", "release");
		if (!publicationReceiptSha256.equals(receipt.receiptSha256())
			|| !publicationReceiptRawSha256.equals(receipt.receiptRawSha256())) {
			throw failure(RELEASE_EVIDENCE_IDENTITY_MISMATCH, "release publication receipt identity mismatch");
		}
		return new ReleaseEvidence(
			result,
			finalSha256,
			finalRawSha256,
			publicationReceiptSha256,
			publicationReceiptRawSha256,
			promotionEvidenceSha256);
	}

	private static RouteBundleAdmissionEvidence parseBackendAdmission(
		ObjectNode backendAdmission,
		String manifestSha256,
		ReleaseEvidence release,
		String activationRequestIdentity) {
		requireKeys(backendAdmission, Set.of(
			"manifestSha256", "finalEvidenceReference", "promotionEvidenceReference",
			"immutablePublicationReceiptIdentity"), "backendAdmission");
		String actualManifestSha256 = requireSha256(backendAdmission, "manifestSha256", "backendAdmission");
		String finalReference = requireSha256Reference(
			backendAdmission, "finalEvidenceReference", "backendAdmission");
		String promotionReference = requireSha256Reference(
			backendAdmission, "promotionEvidenceReference", "backendAdmission");
		String receiptReference = requireSha256Reference(
			backendAdmission, "immutablePublicationReceiptIdentity", "backendAdmission");
		if (!actualManifestSha256.equals(manifestSha256)
			|| !finalReference.equals(reference(release.finalRawSha256()))
			|| !promotionReference.equals(reference(release.promotionEvidenceSha256()))
			|| !receiptReference.equals(reference(release.publicationReceiptRawSha256()))) {
			throw failure(RELEASE_EVIDENCE_IDENTITY_MISMATCH, "backend admission evidence identity mismatch");
		}
		return createBackendAdmission(actualManifestSha256, release, activationRequestIdentity);
	}

	private static RouteBundleAdmissionEvidence createBackendAdmission(
		String manifestSha256,
		ReleaseEvidence release,
		String activationRequestIdentity) {
		try {
			return new RouteBundleAdmissionEvidence(
				manifestSha256,
				reference(release.finalRawSha256()),
				reference(release.promotionEvidenceSha256()),
				reference(release.publicationReceiptRawSha256()),
				activationRequestIdentity);
		} catch (IllegalArgumentException exception) {
			throw failure(ACTIVATION_REQUEST_IDENTITY_INVALID, "activation request identity is invalid", exception);
		}
	}

	private static String parsePlatformRelease(ObjectNode platformRelease, String manifestSha256) {
		requireKeys(platformRelease, Set.of("serverRouteBundleDigest"), "platformRelease");
		String digest = requireSha256Reference(platformRelease, "serverRouteBundleDigest", "platformRelease");
		if (!digest.equals(reference(manifestSha256))) {
			throw failure(MANIFEST_IDENTITY_MISMATCH, "platform server route bundle digest mismatch");
		}
		return digest;
	}

	private static void requireActivationRequestIdentity(String value) {
		if (value == null || value.isEmpty() || !value.equals(value.strip())) {
			throw failure(ACTIVATION_REQUEST_IDENTITY_INVALID,
				"activation request identity must be nonempty raw text without trim changes");
		}
	}

	private static ObjectNode parseCanonicalRoot(byte[] bytes, String label) {
		if (bytes == null || bytes.length == 0) {
			throw failure(HANDOFF_UTF8_OR_JSON_INVALID, label + " bytes must be nonempty UTF-8 JSON");
		}
		String decoded = decodeUtf8(bytes);
		ObjectNode root = parseRoot(decoded);
		byte[] canonical = canonicalBytes(
			root, HANDOFF_UTF8_OR_JSON_INVALID, label + " canonicalization failed");
		if (!Arrays.equals(bytes, canonical)) {
			throw failure(HANDOFF_CANONICAL_BYTES_MISMATCH, label + " bytes must be canonical JSON");
		}
		return root;
	}

	private static String decodeUtf8(byte[] bytes) {
		if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
			throw failure(HANDOFF_UTF8_OR_JSON_INVALID, "handoff must not contain a UTF-8 BOM");
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException exception) {
			throw failure(HANDOFF_UTF8_OR_JSON_INVALID, "handoff is not strict UTF-8", exception);
		}
	}

	private static ObjectNode parseRoot(String decoded) {
		try {
			JsonNode value = JSON.readTree(decoded);
			if (!(value instanceof ObjectNode object)) {
				throw failure(HANDOFF_SCHEMA_INVALID, "handoff must be an object");
			}
			return object;
		} catch (RouteBundleHandoffException exception) {
			throw exception;
		} catch (JsonProcessingException exception) {
			throw failure(HANDOFF_UTF8_OR_JSON_INVALID, "handoff JSON is invalid", exception);
		}
	}

	private static byte[] canonicalBytes(
		JsonNode value,
		RouteBundleHandoffException.Reason reason,
		String message) {
		try {
			return JSON.writeValueAsBytes(canonicalNode(value));
		} catch (JsonProcessingException exception) {
			throw failure(reason, message, exception);
		}
	}

	private static JsonNode canonicalNode(JsonNode value) {
		if (value instanceof ObjectNode object) {
			ObjectNode sorted = NODES.objectNode();
			var names = new ArrayList<String>();
			object.fieldNames().forEachRemaining(names::add);
			names.sort(String::compareTo);
			for (String name : names) {
				sorted.set(name, canonicalNode(object.get(name)));
			}
			return sorted;
		}
		if (value instanceof ArrayNode array) {
			ArrayNode canonical = NODES.arrayNode();
			array.forEach(item -> canonical.add(canonicalNode(item)));
			return canonical;
		}
		return value.deepCopy();
	}

	private static ObjectNode requireObject(ObjectNode parent, String field, String label) {
		JsonNode value = parent.get(field);
		if (value instanceof ObjectNode object) {
			return object;
		}
		throw failure(HANDOFF_SCHEMA_INVALID, label + "." + field + " must be an object");
	}

	private static ObjectNode requireObject(JsonNode value, String label) {
		if (value instanceof ObjectNode object) {
			return object;
		}
		throw failure(HANDOFF_SCHEMA_INVALID, label + " must be an object");
	}

	private static void requireKeys(ObjectNode object, Set<String> expected, String label) {
		var actual = new java.util.HashSet<String>();
		object.fieldNames().forEachRemaining(actual::add);
		if (!actual.equals(expected)) {
			throw failure(HANDOFF_SCHEMA_INVALID, label + " keys mismatch");
		}
	}

	private static String requireText(ObjectNode object, String field, String label) {
		JsonNode value = object.get(field);
		if (value != null && value.isTextual()) {
			return value.textValue();
		}
		throw failure(HANDOFF_SCHEMA_INVALID, label + "." + field + " must be a string");
	}

	private static void requireExactText(ObjectNode object, String field, String expected, String label) {
		if (!expected.equals(requireText(object, field, label))) {
			throw failure(HANDOFF_SCHEMA_INVALID, label + "." + field + " mismatch");
		}
	}

	private static String requirePatternText(
		ObjectNode object,
		String field,
		Pattern pattern,
		String label) {
		String value = requireText(object, field, label);
		if (!pattern.matcher(value).matches()) {
			throw failure(HANDOFF_SCHEMA_INVALID, label + "." + field + " format mismatch");
		}
		return value;
	}

	private static String requireSha256(ObjectNode object, String field, String label) {
		return requirePatternText(object, field, SHA_256, label);
	}

	private static String requireSha256Reference(ObjectNode object, String field, String label) {
		return requirePatternText(object, field, SHA_256_REFERENCE, label);
	}

	private static long requireInteger(
		ObjectNode object,
		String field,
		long minimum,
		long maximum,
		String label) {
		JsonNode value = object.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
			throw failure(HANDOFF_SCHEMA_INVALID, label + "." + field + " must be an integer");
		}
		long result = value.longValue();
		if (result < minimum || result > maximum) {
			throw failure(HANDOFF_SCHEMA_INVALID, label + "." + field + " is outside range");
		}
		return result;
	}

	private static void requireSame(Object actual, Object expected, String label) {
		requireSame(actual, expected, label, MANIFEST_IDENTITY_MISMATCH);
	}

	private static void requireSame(
		Object actual,
		Object expected,
		String label,
		RouteBundleHandoffException.Reason reason) {
		if (!actual.equals(expected)) {
			throw failure(reason, label + " mismatch");
		}
	}

	private static String reference(String digest) {
		return "sha256:" + digest;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static RouteBundleHandoffException failure(
		RouteBundleHandoffException.Reason reason,
		String message) {
		return new RouteBundleHandoffException(reason, message);
	}

	private static RouteBundleHandoffException failure(
		RouteBundleHandoffException.Reason reason,
		String message,
		Throwable cause) {
		return new RouteBundleHandoffException(reason, message, cause);
	}

	private record Receipt(
		String repositoryGitSha,
		PublicationLocator locator,
		List<PublishedObject> objects,
		String prePublicationFinalSha256,
		String receiptSha256,
		String receiptRawSha256) {
	}

	private record PublicationFacts(
		RouteBundleIdentity identity,
		String manifestSha256,
		String sourceSnapshotSetHash,
		Receipt receipt,
		ReleaseEvidence release) {
	}
}
