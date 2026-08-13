package com.easysubway.journey.activation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JourneyActivationCommandParserTest {

	private static final String SHA_A = "a".repeat(64);
	private final JourneyActivationCommandParser parser = new JourneyActivationCommandParser();

	@Test
	void parsesTheExactBoundedClosedCommand() {
		var command = parser.parse(validCommand().getBytes(StandardCharsets.UTF_8));

		assertThat(command.schemaVersion()).isOne();
		assertThat(command.artifactKind()).isEqualTo("journey-v3-activation-command");
		assertThat(command.activationRequestIdentity()).isEqualTo("activation-request-228");
		assertThat(command.candidateManifestSha256()).isEqualTo(SHA_A);
		assertThat(command.candidateGeneration()).isOne();
		assertThat(command.expectedActiveGeneration()).isZero();
		assertThat(command.trafficGeneration()).isEqualTo(31);
	}

	@Test
	void rejectsDuplicateExtraTrailingAndMalformedInput() {
		assertInvalid(validCommand().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
		assertInvalid(validCommand().replace("}", ",\"extra\":true}"));
		assertInvalid(validCommand() + "{}");
		assertInvalid("{");
	}

	@Test
	void rejectsWrongConstantsBoundsAndGenerationRelations() {
		assertInvalid(validCommand().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
		assertInvalid(validCommand().replace("journey-v3-activation-command", "other"));
		assertInvalid(validCommand().replace("activation-request-228", " activation-request-228"));
		assertInvalid(validCommand().replace(SHA_A, "A".repeat(64)));
		assertInvalid(validCommand().replace("\"candidateGeneration\":1", "\"candidateGeneration\":2"));
		assertInvalid(validCommand().replace("\"expectedActiveGeneration\":0", "\"expectedActiveGeneration\":-1"));
		assertInvalid(validCommand().replace("\"trafficGeneration\":31", "\"trafficGeneration\":0"));
	}

	@Test
	void rejectsEmptyAndOversizedBodies() {
		assertThatThrownBy(() -> parser.parse(new byte[0]))
			.isInstanceOf(JourneyActivationException.class)
			.extracting("kind")
			.isEqualTo(JourneyActivationException.Kind.INVALID_REQUEST);
		assertThatThrownBy(() -> parser.parse(new byte[4097]))
			.isInstanceOf(JourneyActivationException.class)
			.extracting("kind")
			.isEqualTo(JourneyActivationException.Kind.INVALID_REQUEST);
	}

	private void assertInvalid(String request) {
		assertThatThrownBy(() -> parser.parse(request.getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(JourneyActivationException.class)
			.extracting("kind")
			.isEqualTo(JourneyActivationException.Kind.INVALID_REQUEST);
	}

	static String validCommand() {
		return """
			{"schemaVersion":1,"artifactKind":"journey-v3-activation-command","activationRequestIdentity":"activation-request-228","candidateManifestSha256":"%s","candidateGeneration":1,"expectedActiveGeneration":0,"trafficGeneration":31}
			""".formatted(SHA_A).strip();
	}
}
