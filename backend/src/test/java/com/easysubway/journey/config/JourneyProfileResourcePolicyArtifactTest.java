package com.easysubway.journey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class JourneyProfileResourcePolicyArtifactTest {

	@Test
	void readsEveryPolicyFieldAndUsesTheExactInputDigest() {
		byte[] bytes = validJson().getBytes(StandardCharsets.UTF_8);

		JourneyProfileResourcePolicy policy = JourneyProfileResourcePolicyArtifact.read(bytes, sha256(bytes));

		assertThat(policy.identity()).isEqualTo(new JourneyProfileResourcePolicy.Identity(
			"RAPTOR_RESOURCE_POLICY_V1", "1.0.0", sha256(bytes)));
		assertThat(policy.maxTemporalWindow().toSeconds()).isEqualTo(3_600);
		assertThat(policy.maxServiceDayCount()).isEqualTo(2);
		assertThat(policy.maxEstimatedWork()).isEqualTo(1_000L);
		assertThat(policy.maxLabelsPerState()).isEqualTo(8);
		assertThat(policy.maxDestinationProfileLabels()).isEqualTo(16);
		assertThat(policy.maxProfileBreakpoints()).isEqualTo(32);
		assertThat(policy.realtimeApplicableFutureHorizon().toSeconds()).isEqualTo(3_600);
		assertThat(policy.pointSearchDeadline().toSeconds()).isEqualTo(2);
		assertThat(policy.profileSearchDeadline().toSeconds()).isEqualTo(5);
		assertThat(policy.lastConnectionDeadline().toSeconds()).isEqualTo(8);
		assertThat(policy.pointSearchCostUnits()).isEqualTo(1);
		assertThat(policy.shortDepartureProfileCostUnits()).isEqualTo(2);
		assertThat(policy.arriveByProfileCostUnits()).isEqualTo(3);
		assertThat(policy.lastConnectionCostUnits()).isEqualTo(4);
		assertThat(policy.maxCostUnitsPerSession()).isEqualTo(10);
	}

	@Test
	void rejectsTamperedBytesEvenWhenTheirJsonIsOtherwiseValid() {
		byte[] bytes = validJson().getBytes(StandardCharsets.UTF_8);
		byte[] tampered = validJson().replace("\"1.0.0\"", "\"1.0.1\"")
			.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> JourneyProfileResourcePolicyArtifact.read(tampered, sha256(bytes)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsDuplicateExtraMissingAndTrailingJson() {
		assertInvalid(validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
		assertInvalid("");
		assertInvalid("null");
		assertInvalid(validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
		assertInvalid(validJson().replace("}", ",\"unexpected\":true}"));
		assertInvalid(validJson().replace("\"maxLabelsPerState\":8,", ""));
		assertInvalid(validJson() + " {} ");
	}

	@Test
	void rejectsWrongScalarTypesFractionsAndOverflow() {
		assertInvalid(validJson().replace("\"pointSearchDeadlineSeconds\":2", "\"pointSearchDeadlineSeconds\":\"2\""));
		assertInvalid(validJson().replace("\"semanticVersion\":\"1.0.0\"", "\"semanticVersion\":null"));
		assertInvalid(validJson().replace("\"maxEstimatedWork\":1000", "\"maxEstimatedWork\":1.5"));
		assertInvalid(validJson().replace("\"maxLabelsPerState\":8", "\"maxLabelsPerState\":2147483648"));
		assertInvalid(validJson().replace("\"maxEstimatedWork\":1000", "\"maxEstimatedWork\":9223372036854775808"));
	}

	@Test
	void preservesTheExistingRequestCostCeilingValidation() {
		assertInvalid(validJson().replace("\"lastConnectionCostUnits\":4", "\"lastConnectionCostUnits\":11"));
	}

	private static void assertInvalid(String json) {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> JourneyProfileResourcePolicyArtifact.read(bytes, sha256(bytes)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static String validJson() {
		return """
			{"schemaVersion":1,"artifactKind":"journey-profile-resource-policy",
			"resourcePolicyId":"RAPTOR_RESOURCE_POLICY_V1","semanticVersion":"1.0.0",
			"maxTemporalWindowSeconds":3600,"maxServiceDayCount":2,"maxEstimatedWork":1000,
			"maxLabelsPerState":8,"maxDestinationProfileLabels":16,"maxProfileBreakpoints":32,
			"realtimeApplicableFutureHorizonSeconds":3600,"pointSearchDeadlineSeconds":2,
			"profileSearchDeadlineSeconds":5,"lastConnectionDeadlineSeconds":8,
			"pointSearchCostUnits":1,"shortDepartureProfileCostUnits":2,"arriveByProfileCostUnits":3,
			"lastConnectionCostUnits":4,"maxCostUnitsPerSession":10}
			""".replaceAll("\\s+", "");
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}
}
