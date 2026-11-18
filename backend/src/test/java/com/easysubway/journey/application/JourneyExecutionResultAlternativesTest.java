package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#469 검색 성공 결과의 대표 묶음과 계단 없는 경로 결과 불변식")
class JourneyExecutionResultAlternativesTest {

	private static final Instant DEPARTURE = Instant.parse("2026-08-12T00:01:00Z");

	@Test
	void acceptsConsistentCategoriesAndStatus() {
		var stairs = candidate("journey-stairs", false, List.of(JourneyAlternatives.Category.FASTEST));
		var stairFree = candidate("journey-step-free", true, List.of(JourneyAlternatives.Category.STAIR_FREE));

		var success = success(List.of(stairs, stairFree), JourneyAlternatives.StairFreeStatus.INCLUDED);

		assertThat(success.stairFreeAlternative().status()).isEqualTo(JourneyAlternatives.StairFreeStatus.INCLUDED);
	}

	@Test
	void rejectsStatusThatContradictsJourneys() {
		var stairs = candidate("journey-stairs", false, List.of(JourneyAlternatives.Category.FASTEST));
		var stairFree = candidate("journey-step-free", true, List.of());

		assertThatThrownBy(() -> success(List.of(stairs), JourneyAlternatives.StairFreeStatus.INCLUDED))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("stairFreeAlternative status");
		assertThatThrownBy(() -> success(List.of(stairs, stairFree), JourneyAlternatives.StairFreeStatus.UNDETERMINED))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("stairFreeAlternative status");
	}

	@Test
	void rejectsDuplicatedOrUnorderedCategories() {
		var first = candidate("journey-1", false, List.of(JourneyAlternatives.Category.FASTEST));
		var duplicate = candidate("journey-2", false, List.of(JourneyAlternatives.Category.FASTEST));

		assertThatThrownBy(() -> success(List.of(first, duplicate), JourneyAlternatives.StairFreeStatus.NOT_FOUND))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("duplicated");
		assertThatThrownBy(() -> candidate("journey-4", false,
			List.of(JourneyAlternatives.Category.STAIR_FREE, JourneyAlternatives.Category.FASTEST)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> candidate("journey-5", false,
			List.of(JourneyAlternatives.Category.FASTEST, JourneyAlternatives.Category.FASTEST)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void planResultCarriesStairFreeAlternativeExactlyWhenCandidatesExist() {
		var candidate = candidate("journey-1", true, List.of(JourneyAlternatives.Category.FASTEST));
		var alternative = new JourneyAlternatives.StairFreeAlternative(JourneyAlternatives.StairFreeStatus.INCLUDED,
			JourneyAlternatives.FacilityStatus.APPLIED);
		var scan = new JourneyRaptorPort.ScanMetrics(1, 2, 3);
		var boundary = JourneyRaptorPort.RouteBoundaryReceipt.observed(0);
		var measurement = JourneyRaptorPort.RouteMeasurementReceipt.unobservable();

		assertThat(new JourneyRaptorPort.PlanResult("query-1", List.of(candidate), scan, boundary, measurement, alternative)
			.stairFreeAlternative()).isEqualTo(alternative);
		assertThat(new JourneyRaptorPort.PlanResult("query-1", List.of(), scan, boundary).stairFreeAlternative()).isNull();
		assertThatThrownBy(() -> new JourneyRaptorPort.PlanResult("query-1", List.of(candidate), scan, boundary))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyRaptorPort.PlanResult("query-1", List.of(), scan, boundary, measurement,
			alternative)).isInstanceOf(IllegalArgumentException.class);
	}

	private static JourneyCandidate candidate(
		String journeyId, boolean stairFree, List<JourneyAlternatives.Category> categories
	) {
		var candidate = TestJourneyCandidates.unavailableFare(journeyId, DEPARTURE, DEPARTURE.plusSeconds(300), null, null,
			300, 0, 0, JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(stairFree, List.of("ACCESSIBILITY_VERIFIED")),
			List.of(TestRides.candidateRide("line-1", "trip-" + journeyId, "station-b", "station-a", "station-b",
				DEPARTURE, DEPARTURE.plusSeconds(300), null, null)));
		return candidate.withAlternativeCategories(categories);
	}

	private static JourneyExecutionResult.Success success(
		List<JourneyCandidate> journeys, JourneyAlternatives.StairFreeStatus status
	) {
		return new JourneyExecutionResult.Success("01K1Y000000000000000000000", "query-1", DEPARTURE,
			DEPARTURE.plusSeconds(600), DEPARTURE, LocalDate.parse("2026-08-12"), 1,
			new JourneyRaptorPort.ScanMetrics(1, 2, 3),
			new JourneyExecutionResult.SourceIdentity("bundle-1", "a".repeat(64), "timetable-1", "accessibility-1", null),
			new JourneyExecutionResult.RequestPolicy(JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE, 1, 3),
			journeys, JourneyExecutionResult.SafetyBoundary.observed(),
			new JourneyAlternatives.StairFreeAlternative(status, JourneyAlternatives.FacilityStatus.UNOBSERVED));
	}
}
