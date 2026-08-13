package com.easysubway.journey.activation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class JourneyActivationCommandParser {

	public static final int MAX_REQUEST_BYTES = 4 * 1024;
	private static final int MAX_ACTIVATION_IDENTITY_LENGTH = 512;
	private static final int SCHEMA_VERSION = 1;
	private static final String ARTIFACT_KIND = "journey-v3-activation-command";
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> FIELDS = Set.of(
		"schemaVersion",
		"artifactKind",
		"activationRequestIdentity",
		"candidateManifestSha256",
		"candidateGeneration",
		"expectedActiveGeneration",
		"trafficGeneration");
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
				|| !request.path("activationRequestIdentity").isTextual()
				|| !request.path("candidateManifestSha256").isTextual()
				|| !isLong(request.path("candidateGeneration"))
				|| !isLong(request.path("expectedActiveGeneration"))
				|| !isLong(request.path("trafficGeneration"))) {
				throw invalidRequest();
			}
			String activationRequestIdentity = request.path("activationRequestIdentity").textValue();
			String candidateManifestSha256 = request.path("candidateManifestSha256").textValue();
			long candidateGeneration = request.path("candidateGeneration").longValue();
			long expectedActiveGeneration = request.path("expectedActiveGeneration").longValue();
			long trafficGeneration = request.path("trafficGeneration").longValue();
			if (!validActivationIdentity(activationRequestIdentity)
				|| !SHA_256.matcher(candidateManifestSha256).matches()
				|| candidateGeneration < 1
				|| expectedActiveGeneration < 0
				|| trafficGeneration < 1
				|| candidateGeneration != nextGeneration(expectedActiveGeneration)) {
				throw invalidRequest();
			}
			return new Command(
				SCHEMA_VERSION,
				ARTIFACT_KIND,
				activationRequestIdentity,
				candidateManifestSha256,
				candidateGeneration,
				expectedActiveGeneration,
				trafficGeneration);
		} catch (JourneyActivationException exception) {
			throw exception;
		} catch (IOException exception) {
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

	private static boolean validActivationIdentity(String value) {
		return !value.isEmpty()
			&& value.length() <= MAX_ACTIVATION_IDENTITY_LENGTH
			&& value.equals(value.strip())
			&& value.codePoints().noneMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f);
	}

	private static long nextGeneration(long expectedActiveGeneration) {
		try {
			return Math.addExact(expectedActiveGeneration, 1);
		} catch (ArithmeticException exception) {
			throw invalidRequest();
		}
	}

	private static JourneyActivationException invalidRequest() {
		return new JourneyActivationException(JourneyActivationException.Kind.INVALID_REQUEST);
	}

	public record Command(
		int schemaVersion,
		String artifactKind,
		String activationRequestIdentity,
		String candidateManifestSha256,
		long candidateGeneration,
		long expectedActiveGeneration,
		long trafficGeneration) {
	}
}
