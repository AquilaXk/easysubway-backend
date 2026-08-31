package com.easysubway.journey.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.ActiveJourneySnapshotPort;
import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyExecutionFailure;
import com.easysubway.journey.application.JourneyExecutionResult;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRaptorPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus().replace(
			"\"schemaVersion\":2", "\"schemaVersion\":4294967298")))
			.hasMessageContaining("corpus version");
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus().replace(
			"\"mobilityProfile\":\"STANDARD\",\"constraintMode\":\"NONE\"",
			"\"mobilityProfile\":\"NO_STAIRS\",\"constraintMode\":\"NONE\"")))
			.hasMessageContaining("NO_STAIRS requires REQUIRE_STEP_FREE");
	}

	@Test
	void validatesFailureOutcomesByTheirExactTypedReason() {
		var corpus = JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus().replace(
			"\"expectedOutcome\":{\"kind\":\"SUCCESS\"}",
			"\"expectedOutcome\":{\"kind\":\"FAILURE\",\"reason\":\"NO_ROUTE\"}"));
		var benchmarkCase = corpus.cases().getFirst();

		JourneyV3FinalCoverageBenchmarkCorpus.validateObservedOutcome(benchmarkCase, new JourneyExecutionFailure(
			JourneyExecutionFailure.Reason.NO_ROUTE, observedSuccess().executionObservation()));
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.validateObservedOutcome(benchmarkCase,
			new JourneyExecutionFailure(JourneyExecutionFailure.Reason.NO_ROUTE)))
			.hasMessageContaining("execution observation");
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

	@Test
	void rejectsSuccessWithoutObservedRequestBoundServingAndBoundaryEvidence() {
		var benchmarkCase = JourneyV3FinalCoverageBenchmarkCorpus.parse(validCorpus()).cases().getFirst();

		JourneyV3FinalCoverageBenchmarkCorpus.validateObservedOutcome(benchmarkCase, observedSuccess());
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.validateObservedOutcome(benchmarkCase,
			success(JourneyExecutionResult.RequestMeasurement.unobservable())))
			.hasMessageContaining("request measurement");
		assertThatThrownBy(() -> JourneyV3FinalCoverageBenchmarkCorpus.validateObservedOutcome(benchmarkCase,
			unobservableSafetyBoundarySuccess()))
			.hasMessageContaining("boundary evidence");
	}

	private static JourneyExecutionResult.Success observedSuccess() {
		var activeServing = new JourneyExecutionResult.ActiveServingIdentity(
			JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED,
			"b".repeat(64), "c".repeat(64), "sha256:" + "d".repeat(64), "e".repeat(40), "03:00");
		var identity = new ActiveJourneySnapshotPort.RequestExecutionIdentity(
			"01K1Y000000000000000000000", "a".repeat(64), 1, activeReadiness(), activeServing);
		return success(JourneyExecutionResult.RequestMeasurement.observed(identity,
			JourneyExecutionResult.BoundaryObservation.observed(0, 0, 0, 0)));
	}

	private static JourneyExecutionResult.Success success(
		JourneyExecutionResult.RequestMeasurement requestMeasurement
	) {
		return success(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, null,
			JourneyExecutionResult.SafetyBoundary.observed(), requestMeasurement);
	}

	private static JourneyExecutionResult.Success unobservableSafetyBoundarySuccess() {
		return success(JourneyRequest.TimePolicy.REALTIME_REQUIRED, "realtime-1",
			JourneyExecutionResult.SafetyBoundary.unobservable(), observedSuccess().requestMeasurement());
	}

	private static JourneyExecutionResult.Success success(
		JourneyRequest.TimePolicy timePolicy,
		String realtimeSnapshotId,
		JourneyExecutionResult.SafetyBoundary safetyBoundary,
		JourneyExecutionResult.RequestMeasurement requestMeasurement
	) {
		Instant departure = Instant.parse("2026-08-12T00:01:00Z");
		var journey = new JourneyCandidate("journey-1", departure, departure.plusSeconds(300), null, null,
			300, 0, 0, JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH")), List.of(
				new JourneyCandidate.Ride("line-1", "trip-1", "station-b", "station-a", "station-b",
					departure, departure.plusSeconds(300), null, null)));
		return new JourneyExecutionResult.Success("01K1Y000000000000000000000", "query-1", departure,
			departure.plusSeconds(600), departure, LocalDate.parse("2026-08-12"), 1,
			new JourneyRaptorPort.ScanMetrics(1, 2, 3), new JourneyExecutionResult.SourceIdentity("bundle-1",
				"a".repeat(64), "timetable-1", "accessibility-1", realtimeSnapshotId),
			new JourneyExecutionResult.RequestPolicy(timePolicy,
				JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 1, 1), List.of(journey),
			safetyBoundary, requestMeasurement);
	}

	private static JourneyExecutionResult.ActiveReadinessIdentity activeReadiness() {
		return new JourneyExecutionResult.ActiveReadinessIdentity(
			1, "journey-v3-active-readiness", "backend-a", "d".repeat(64),
			"sha256:" + "f".repeat(64), "1".repeat(64), "2".repeat(64), "a".repeat(64),
			"bundle-1", 1, 1, "Asia/Seoul", "03:00", 1, true, false,
			Instant.parse("2026-08-12T01:00:00Z"), Instant.parse("2026-08-11T23:00:00Z"),
			"3".repeat(64));
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
