package com.easysubway.journey.config;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads one closed, digest-bound Journey profile resource-policy artifact. */
public final class JourneyProfileResourcePolicyArtifact {

	private static final int SCHEMA_VERSION = 1;
	private static final String ARTIFACT_KIND = "journey-profile-resource-policy";
	private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
	private static final Set<String> FIELDS = Set.of(
		"schemaVersion", "artifactKind", "resourcePolicyId", "semanticVersion",
		"maxTemporalWindowSeconds", "maxServiceDayCount", "maxEstimatedWork",
		"maxLabelsPerState", "maxDestinationProfileLabels", "maxProfileBreakpoints",
		"realtimeApplicableFutureHorizonSeconds", "pointSearchDeadlineSeconds",
		"profileSearchDeadlineSeconds", "lastConnectionDeadlineSeconds", "pointSearchCostUnits",
		"shortDepartureProfileCostUnits", "arriveByProfileCostUnits", "lastConnectionCostUnits",
		"maxCostUnitsPerSession");
	private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	private JourneyProfileResourcePolicyArtifact() {
	}

	public static JourneyProfileResourcePolicy read(byte[] bytes, String expectedSha256) {
		if (bytes == null || !isSha256(expectedSha256)) {
			throw invalidArtifact();
		}
		// 해시 검증과 파싱이 호출자 변경에 영향받지 않는 동일 bytes를 사용한다.
		byte[] snapshot = bytes.clone();
		if (!expectedSha256.equals(sha256(snapshot))) throw invalidArtifact();
		try {
			JsonNode root = JSON.readTree(snapshot);
			if (!hasExactFields(root)
				|| requireInt(root, "schemaVersion") != SCHEMA_VERSION
				|| !ARTIFACT_KIND.equals(requireText(root, "artifactKind"))) {
				throw invalidArtifact();
			}
			return new JourneyProfileResourcePolicy(
				new JourneyProfileResourcePolicy.Identity(
					requireText(root, "resourcePolicyId"), requireText(root, "semanticVersion"), expectedSha256),
				seconds(root, "maxTemporalWindowSeconds"),
				requireInt(root, "maxServiceDayCount"),
				requireLong(root, "maxEstimatedWork"),
				requireInt(root, "maxLabelsPerState"),
				requireInt(root, "maxDestinationProfileLabels"),
				requireInt(root, "maxProfileBreakpoints"),
				seconds(root, "realtimeApplicableFutureHorizonSeconds"),
				seconds(root, "pointSearchDeadlineSeconds"),
				seconds(root, "profileSearchDeadlineSeconds"),
				seconds(root, "lastConnectionDeadlineSeconds"),
				requireInt(root, "pointSearchCostUnits"),
				requireInt(root, "shortDepartureProfileCostUnits"),
				requireInt(root, "arriveByProfileCostUnits"),
				requireInt(root, "lastConnectionCostUnits"),
				requireInt(root, "maxCostUnitsPerSession"));
		} catch (IOException | IllegalArgumentException exception) {
			throw invalidArtifact();
		}
	}

	private static boolean hasExactFields(JsonNode root) {
		if (root == null || !root.isObject() || root.size() != FIELDS.size()) return false;
		var actual = new HashSet<String>();
		root.fieldNames().forEachRemaining(actual::add);
		return actual.equals(FIELDS);
	}

	private static String requireText(JsonNode root, String field) {
		JsonNode value = root.get(field);
		if (value == null || !value.isTextual()) throw invalidArtifact();
		return value.textValue();
	}

	private static int requireInt(JsonNode root, String field) {
		JsonNode value = root.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw invalidArtifact();
		}
		return value.intValue();
	}

	private static long requireLong(JsonNode root, String field) {
		JsonNode value = root.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
			throw invalidArtifact();
		}
		return value.longValue();
	}

	private static Duration seconds(JsonNode root, String field) {
		return Duration.ofSeconds(requireLong(root, field));
	}

	private static boolean isSha256(String value) {
		return value != null && SHA_256.matcher(value).matches();
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static IllegalArgumentException invalidArtifact() {
		return new IllegalArgumentException("invalid Journey profile resource-policy artifact");
	}
}
