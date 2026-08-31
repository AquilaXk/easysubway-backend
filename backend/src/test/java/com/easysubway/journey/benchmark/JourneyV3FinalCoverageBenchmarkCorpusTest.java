package com.easysubway.journey.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyExecutionFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class JourneyV3FinalCoverageBenchmarkCorpusTest {

	@Test
	void parsesAnExactFinalBoundV2CorpusAndComputesItsRawDigest() throws Exception {
		String raw = validCorpus();
		var corpus = JourneyV3FinalCoverageBenchmarkCorpus.parse(raw);

		assertThat(corpus.rawSha256()).isEqualTo(java.util.HexFormat.of().formatHex(
			MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))));
		assertThat(corpus.regions()).containsExactly("seoul");
		assertThat(corpus.operators()).containsExactly("metro");
		assertThat(corpus.requiredCoverageCells()).hasSize(1);
		assertThat(corpus.cases()).singleElement().satisfies(value -> {
			assertThat(value.transferBucket()).isEqualTo(JourneyV3FinalCoverageBenchmarkCorpus.TransferBucket.DIRECT);
			assertThat(value.expectedOutcome()).isInstanceOf(
				JourneyV3FinalCoverageBenchmarkCorpus.ExpectedOutcome.Success.class);
		});
	}

	@Test
	void rejectsDuplicateUnknownTrailingAndNonexactCoverageShapes() {
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus().replace(
			"\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus() + "{}"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus().replace(
			"\"regions\":[\"seoul\"]", "\"regions\":[\"seoul\",\"incheon\"]")))
			.hasMessageContaining("exactly match");
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus().replace(
			"\"expectedOutcome\":{\"kind\":\"SUCCESS\"}",
			"\"expectedOutcome\":{\"kind\":\"SUCCESS\",\"reason\":\"NO_ROUTE\"}")))
			.hasMessageContaining("fields are invalid");
	}

	@Test
	void validatesFailureOutcomesByTheirExactTypedReason() {
		var corpus = JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus().replace(
			"\"expectedOutcome\":{\"kind\":\"SUCCESS\"}",
			"\"expectedOutcome\":{\"kind\":\"FAILURE\",\"reason\":\"NO_ROUTE\"}"));
		var benchmarkCase = corpus.cases().getFirst();

		JourneyV3FinalCoverageBenchmarkCorpus.validateObservedOutcome(benchmarkCase,
			new JourneyExecutionFailure(JourneyExecutionFailure.Reason.NO_ROUTE));
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.validateObservedOutcome(benchmarkCase,
			new JourneyExecutionFailure(JourneyExecutionFailure.Reason.RAPTOR_FAILED)))
			.hasMessageContaining("failure reason");
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.transferBucket(4))
			.hasMessageContaining("outside the corpus contract");
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus().replace(
			"\"expectedOutcome\":{\"kind\":\"SUCCESS\"}",
			"\"expectedOutcome\":{\"kind\":\"FAILURE\",\"reason\":\"ACTIVE_SNAPSHOT_STALE\"}")))
			.hasMessageContaining("only NO_ROUTE");
	}

	private static String validCorpus() {
		String digest = "a".repeat(64);
		return """
			{"schemaVersion":2,"corpusVersion":"v2","finalBinding":{
				"descriptorSha256":"%1$s","finalSha256":"%1$s","finalRawSha256":"%1$s",
				"publicationReceiptSha256":"%1$s","publicationReceiptRawSha256":"%1$s",
				"stationSetSha256":"%1$s","sourceSnapshotSetHash":"%1$s","topologySha256":"%1$s",
				"accessibilitySha256":"%1$s"},
				"regions":["seoul"],"operators":["metro"],"requiredCoverageCells":[{
				"regionId":"seoul","operatorId":"metro","transferBucket":"DIRECT","timeBand":"PEAK",
				"serviceDay":"WEEKDAY","mobilityProfile":"STANDARD","constraintMode":"NONE"}],"cases":[{
				"id":"seoul-direct-peak","originStationId":"station-a","destinationStationId":"station-b",
				"departureLocalTime":"08:00","regionId":"seoul","operatorId":"metro","transferBucket":"DIRECT",
				"timeBand":"PEAK","serviceDay":"WEEKDAY","mobilityProfile":"STANDARD","constraintMode":"NONE",
				"expectedOutcome":{"kind":"SUCCESS"}}]}
			""".formatted(digest);
	}
}
