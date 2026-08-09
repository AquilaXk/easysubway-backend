package com.easysubway.journey.bundle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RouteBundleArtifactInspector {
	private static final List<String> PATHS = List.of(
		"payload/accessibility.sqlite.zst", "payload/fare.sqlite.zst",
		"payload/timetable.sqlite.zst", "payload/topology.sqlite.zst");
	private static final Set<String> MANIFEST_FIELDS = Set.of(
		"manifestVersion", "artifactKind", "bundleId", "releaseSequence", "stationSetSha256", "payloadSha256",
		"topologySha256", "timetableSha256", "accessibilitySha256", "fareSha256", "provenanceSha256",
		"compatibilitySha256", "serviceTimezone", "activeFrom", "freshUntil", "schemaCompatibility", "keyId", "signature");
	private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	private RouteBundleArtifactInspector() {
	}

	static RouteBundlePayloadInspection inspect(byte[] signedManifestBytes, Map<String, byte[]> payloadBytes) {
		var manifest = parse(signedManifestBytes);
		validateExactFields(manifest, MANIFEST_FIELDS);
		var identity = identity(manifest);
		validatePayloadPaths(payloadBytes);
		validateDigest(payloadBytes.get("payload/topology.sqlite.zst"), identity.topologySha256(), RouteBundleInspectionException.Reason.TOPOLOGY_DIGEST_MISMATCH);
		validateDigest(payloadBytes.get("payload/timetable.sqlite.zst"), identity.timetableSha256(), RouteBundleInspectionException.Reason.TIMETABLE_DIGEST_MISMATCH);
		validateDigest(payloadBytes.get("payload/accessibility.sqlite.zst"), identity.accessibilitySha256(), RouteBundleInspectionException.Reason.ACCESSIBILITY_DIGEST_MISMATCH);
		validateDigest(payloadBytes.get("payload/fare.sqlite.zst"), identity.fareSha256(), RouteBundleInspectionException.Reason.FARE_DIGEST_MISMATCH);
		var payloadSha256 = sha256(canonicalInventory(payloadBytes));
		if (!payloadSha256.equals(identity.payloadSha256())) throw failure(RouteBundleInspectionException.Reason.PAYLOAD_INVENTORY_DIGEST_MISMATCH);
		return new RouteBundlePayloadInspection(identity, sha256(signedManifestBytes), payloadSha256);
	}

	private static JsonNode parse(byte[] bytes) {
		if (bytes == null) throw failure(RouteBundleInspectionException.Reason.MANIFEST_UTF8_OR_JSON_INVALID);
		try {
			var manifest = JSON.readTree(bytes);
			if (manifest == null || !manifest.isObject()) throw failure(RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
			return manifest;
		} catch (IOException exception) {
			if (exception instanceof JsonProcessingException processing
				&& processing.getOriginalMessage().contains("Duplicate field")) throw failure(RouteBundleInspectionException.Reason.MANIFEST_DUPLICATE_FIELD);
			throw failure(RouteBundleInspectionException.Reason.MANIFEST_UTF8_OR_JSON_INVALID);
		}
	}

	private static RouteBundleIdentity identity(JsonNode manifest) {
		try {
			var compatibility = object(manifest, "schemaCompatibility", Set.of("backendMin", "backendMax"));
			var signature = object(manifest, "signature", Set.of("algorithm", "value"));
			return new RouteBundleIdentity(
				integer(manifest, "manifestVersion"), text(manifest, "artifactKind"), text(manifest, "bundleId"), longInteger(manifest, "releaseSequence"),
				text(manifest, "stationSetSha256"), text(manifest, "payloadSha256"), text(manifest, "topologySha256"), text(manifest, "timetableSha256"),
				text(manifest, "accessibilitySha256"), text(manifest, "fareSha256"), text(manifest, "provenanceSha256"), text(manifest, "compatibilitySha256"),
				text(manifest, "serviceTimezone"), text(manifest, "activeFrom"), text(manifest, "freshUntil"),
				new RouteBundleIdentity.SchemaCompatibility(integer(compatibility, "backendMin"), integer(compatibility, "backendMax")),
				text(manifest, "keyId"), new RouteBundleIdentity.Signature(text(signature, "algorithm"), text(signature, "value")));
		} catch (IllegalArgumentException exception) {
			throw failure(RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
		}
	}

	private static JsonNode object(JsonNode parent, String name, Set<String> fields) {
		var value = parent.get(name);
		if (value == null || !value.isObject()) throw failure(RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
		validateExactFields(value, fields);
		return value;
	}

	private static void validateExactFields(JsonNode object, Set<String> expected) {
		var fields = new java.util.HashSet<String>();
		object.fieldNames().forEachRemaining(fields::add);
		if (!fields.equals(expected)) throw failure(RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
	}

	private static String text(JsonNode object, String name) {
		var value = object.get(name);
		if (value == null || !value.isTextual()) throw failure(RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
		return value.textValue();
	}

	private static int integer(JsonNode object, String name) {
		var value = object.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw failure(RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
		return value.intValue();
	}

	private static long longInteger(JsonNode object, String name) {
		var value = object.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw failure(RouteBundleInspectionException.Reason.MANIFEST_SCHEMA_INVALID);
		return value.longValue();
	}

	private static void validatePayloadPaths(Map<String, byte[]> payloadBytes) {
		if (payloadBytes == null || !payloadBytes.keySet().equals(Set.copyOf(PATHS)) || PATHS.stream().anyMatch(path -> payloadBytes.get(path) == null)) {
			throw failure(RouteBundleInspectionException.Reason.PAYLOAD_PATH_SET_MISMATCH);
		}
	}

	private static void validateDigest(byte[] payload, String expected, RouteBundleInspectionException.Reason reason) {
		if (!sha256(payload).equals(expected)) throw failure(reason);
	}

	private static byte[] canonicalInventory(Map<String, byte[]> payloads) {
		var builder = new StringBuilder("[");
		for (var index = 0; index < PATHS.size(); index++) {
			if (index > 0) builder.append(',');
			var path = PATHS.get(index);
			var bytes = payloads.get(path);
			builder.append("{\"path\":\"").append(path).append("\",\"sha256\":\"").append(sha256(bytes))
				.append("\",\"sizeBytes\":").append(bytes.length).append('}');
		}
		return builder.append(']').toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static RouteBundleInspectionException failure(RouteBundleInspectionException.Reason reason) {
		return new RouteBundleInspectionException(reason);
	}
}
