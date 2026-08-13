package com.easysubway.journey.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JourneyCandidateCanaryCommandParserTest {

	static final String SHA_A = "a".repeat(64);
	static final String REQUEST_ID = "01K1Y000000000000000000000";
	private final JourneyCandidateCanaryCommandParser parser = new JourneyCandidateCanaryCommandParser();

	@Test
	void parsesTheExactBoundedClosedCommand() {
		var command = parser.parse(validCommand().getBytes(StandardCharsets.UTF_8));

		assertThat(command.schemaVersion()).isOne();
		assertThat(command.artifactKind()).isEqualTo("journey-v3-candidate-canary-command");
		assertThat(command.canaryRequestIdentity()).isEqualTo("canary-request-236");
		assertThat(command.candidateManifestSha256()).isEqualTo(SHA_A);
		assertThat(command.candidateGeneration()).isOne();
		assertThat(command.requestId()).isEqualTo(REQUEST_ID);
		assertThat(command.originStationId()).isEqualTo("station-origin");
		assertThat(command.destinationStationId()).isEqualTo("station-destination");
		assertThat(command.mobilityProfile()).isEqualTo(JourneyRequest.MobilityProfile.STEP_FREE);
		assertThat(command.constraintMode()).isEqualTo(JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE);
		assertThat(command.maxTransfers()).isEqualTo(2);
		assertThat(command.alternativeCount()).isEqualTo(1);
	}

	@Test
	void rejectsDuplicateExtraTrailingMalformedAndOversizedInput() {
		assertInvalid(validCommand().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
		assertInvalid(validCommand().replace("}", ",\"extra\":true}"));
		assertInvalid(validCommand() + "{}");
		assertInvalid("{");
		assertInvalid("null");
		assertInvalid("[]");
		assertInvalidBytes(null);
		assertInvalidBytes(new byte[0]);
		assertInvalidBytes(new byte[JourneyCandidateCanaryCommandParser.MAX_REQUEST_BYTES + 1]);
	}

	@Test
	void rejectsWrongConstantsIdentityAndCandidateBounds() {
		assertInvalid(validCommand().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
		assertInvalid(validCommand().replace("journey-v3-candidate-canary-command", "other"));
		assertInvalid(validCommand().replace("canary-request-236", ""));
		assertInvalid(validCommand().replace("canary-request-236", " canary-request-236"));
		assertInvalid(validCommand().replace("canary-request-236", "canary\\u0000request"));
		assertInvalid(validCommand().replace("canary-request-236", "x".repeat(513)));
		assertInvalid(validCommand().replace(SHA_A, "A".repeat(64)));
		assertInvalid(validCommand().replace("\"candidateGeneration\":1", "\"candidateGeneration\":0"));
		assertInvalid(validCommand().replace("\"candidateGeneration\":1", "\"candidateGeneration\":1.5"));
		assertInvalid(validCommand().replace(REQUEST_ID, "invalid-request-id"));
	}

	@Test
	void rejectsUnpairedSurrogatesButAcceptsAValidSupplementaryCodePoint() {
		assertInvalid(validCommand().replace("canary-request-236", "canary-\\uD800"));
		assertInvalid(validCommand().replace("canary-request-236", "canary-\\uDC00"));
		assertInvalid(validCommand().replace("station-origin", "station-\\uD800"));

		var command = parser.parse(validCommand()
			.replace("canary-request-236", "canary-\\uD83D\\uDE87")
			.getBytes(StandardCharsets.UTF_8));

		assertThat(command.canaryRequestIdentity()).isEqualTo("canary-🚇");
	}

	@Test
	void rejectsInvalidStationsEnumsAndExistingJourneyPolicyBounds() {
		assertInvalid(validCommand().replace("station-origin", " station-origin"));
		assertInvalid(validCommand().replace("station-origin", "station\\u007forigin"));
		assertInvalid(validCommand().replace("station-origin", "x".repeat(256)));
		assertInvalid(validCommand().replace("station-destination", "station-origin"));
		assertInvalid(validCommand().replace("STEP_FREE", "UNKNOWN"));
		assertInvalid(validCommand().replace("REQUIRE_STEP_FREE", "UNKNOWN"));
		assertInvalid(validCommand().replace("\"maxTransfers\":2", "\"maxTransfers\":4"));
		assertInvalid(validCommand().replace("\"alternativeCount\":1", "\"alternativeCount\":0"));
		assertInvalid(validCommand()
			.replace("STEP_FREE", "NO_STAIRS")
			.replace("REQUIRE_STEP_FREE", "NONE"));
	}

	private void assertInvalid(String request) {
		assertInvalidBytes(request.getBytes(StandardCharsets.UTF_8));
	}

	private void assertInvalidBytes(byte[] request) {
		assertThatThrownBy(() -> parser.parse(request))
			.isInstanceOf(JourneyCandidateCanaryException.class)
			.extracting("kind")
			.isEqualTo(JourneyCandidateCanaryException.Kind.INVALID_REQUEST);
	}

	static String validCommand() {
		return """
			{"schemaVersion":1,"artifactKind":"journey-v3-candidate-canary-command","canaryRequestIdentity":"canary-request-236","candidateManifestSha256":"%s","candidateGeneration":1,"requestId":"%s","originStationId":"station-origin","destinationStationId":"station-destination","mobilityProfile":"STEP_FREE","constraintMode":"REQUIRE_STEP_FREE","maxTransfers":2,"alternativeCount":1}
			""".formatted(SHA_A, REQUEST_ID).strip();
	}
}
