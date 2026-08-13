package com.easysubway.journey.canary;

import com.easysubway.journey.application.JourneyRequest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class JourneyCandidateCanaryCommandParser {

	public static final int MAX_REQUEST_BYTES = 4 * 1024;
	private static final int MAX_IDENTITY_LENGTH = 512;
	private static final int MAX_STATION_ID_LENGTH = 255;
	private static final int SCHEMA_VERSION = 1;
	private static final String ARTIFACT_KIND = "journey-v3-candidate-canary-command";
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> FIELDS = Set.of(
		"schemaVersion",
		"artifactKind",
		"canaryRequestIdentity",
		"candidateManifestSha256",
		"candidateGeneration",
		"requestId",
		"originStationId",
		"destinationStationId",
		"mobilityProfile",
		"constraintMode",
		"maxTransfers",
		"alternativeCount");
	private static final ObjectMapper REQUEST_JSON = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	public Command parse(byte[] requestBytes) {
		if (requestBytes == null || requestBytes.length == 0 || requestBytes.length > MAX_REQUEST_BYTES) {
			throw invalidRequest();
		}
		try {
			JsonNode request = REQUEST_JSON.readTree(requestBytes);
			if (!hasExactFields(request)
				|| !request.path("schemaVersion").isInt()
				|| request.path("schemaVersion").intValue() != SCHEMA_VERSION
				|| !request.path("artifactKind").isTextual()
				|| !ARTIFACT_KIND.equals(request.path("artifactKind").textValue())
				|| !request.path("canaryRequestIdentity").isTextual()
				|| !request.path("candidateManifestSha256").isTextual()
				|| !isLong(request.path("candidateGeneration"))
				|| !request.path("requestId").isTextual()
				|| !request.path("originStationId").isTextual()
				|| !request.path("destinationStationId").isTextual()
				|| !request.path("mobilityProfile").isTextual()
				|| !request.path("constraintMode").isTextual()
				|| !request.path("maxTransfers").isInt()
				|| !request.path("alternativeCount").isInt()) {
				throw invalidRequest();
			}

			String canaryRequestIdentity = request.path("canaryRequestIdentity").textValue();
			String candidateManifestSha256 = request.path("candidateManifestSha256").textValue();
			long candidateGeneration = request.path("candidateGeneration").longValue();
			String requestId = request.path("requestId").textValue();
			String originStationId = request.path("originStationId").textValue();
			String destinationStationId = request.path("destinationStationId").textValue();
			if (!validRawText(canaryRequestIdentity, MAX_IDENTITY_LENGTH)
				|| !SHA_256.matcher(candidateManifestSha256).matches()
				|| candidateGeneration < 1
				|| !validRawText(originStationId, MAX_STATION_ID_LENGTH)
				|| !validRawText(destinationStationId, MAX_STATION_ID_LENGTH)
				|| originStationId.equals(destinationStationId)) {
				throw invalidRequest();
			}

			var mobilityProfile = JourneyRequest.MobilityProfile.valueOf(
				request.path("mobilityProfile").textValue());
			var constraintMode = JourneyRequest.ConstraintMode.valueOf(
				request.path("constraintMode").textValue());
			int maxTransfers = request.path("maxTransfers").intValue();
			int alternativeCount = request.path("alternativeCount").intValue();
			new JourneyRequest(
				requestId,
				originStationId,
				destinationStationId,
				new JourneyRequest.Departure.Now(),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				mobilityProfile,
				constraintMode,
				maxTransfers,
				alternativeCount,
				() -> false);
			return new Command(
				SCHEMA_VERSION,
				ARTIFACT_KIND,
				canaryRequestIdentity,
				candidateManifestSha256,
				candidateGeneration,
				requestId,
				originStationId,
				destinationStationId,
				mobilityProfile,
				constraintMode,
				maxTransfers,
				alternativeCount);
		} catch (JourneyCandidateCanaryException exception) {
			throw exception;
		} catch (IOException | IllegalArgumentException exception) {
			throw invalidRequest();
		}
	}

	private static boolean hasExactFields(JsonNode request) {
		if (!request.isObject() || request.size() != FIELDS.size()) return false;
		var actual = new HashSet<String>();
		request.fieldNames().forEachRemaining(actual::add);
		return actual.equals(FIELDS);
	}

	private static boolean isLong(JsonNode value) {
		return value.isIntegralNumber() && value.canConvertToLong();
	}

	private static boolean validRawText(String value, int maxLength) {
		if (value.isEmpty() || value.length() > maxLength || !hasWellFormedUtf16(value)
			|| value.isBlank() || !value.equals(value.strip())) {
			return false;
		}
		int first = value.codePointAt(0);
		int last = value.codePointBefore(value.length());
		return !isBoundarySpace(first)
			&& !isBoundarySpace(last)
			&& value.codePoints().noneMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f);
	}

	private static boolean hasWellFormedUtf16(String value) {
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (Character.isHighSurrogate(current)) {
				if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
			} else if (Character.isLowSurrogate(current)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isBoundarySpace(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
	}

	private static JourneyCandidateCanaryException invalidRequest() {
		return new JourneyCandidateCanaryException(JourneyCandidateCanaryException.Kind.INVALID_REQUEST);
	}

	public record Command(
		int schemaVersion,
		String artifactKind,
		String canaryRequestIdentity,
		String candidateManifestSha256,
		long candidateGeneration,
		String requestId,
		String originStationId,
		String destinationStationId,
		JourneyRequest.MobilityProfile mobilityProfile,
		JourneyRequest.ConstraintMode constraintMode,
		int maxTransfers,
		int alternativeCount) {
	}
}
