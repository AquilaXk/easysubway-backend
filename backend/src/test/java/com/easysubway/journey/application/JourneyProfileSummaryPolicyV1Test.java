package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyFrontierPolicyV1.FeasibleCandidate;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.Metrics;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.ObjectiveTag;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.SelectedLabel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class JourneyProfileSummaryPolicyV1Test {

	private static final Instant START = Instant.parse("2026-09-02T00:00:00Z");
	private static final BooleanSupplier NOT_CANCELLED = () -> false;

	@Test
	void selectsDepartureSummaryAndCapsDistinctRecommendationsInTheFixedTagOrder() {
		var summary = JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(60)), 1),
			frontier(
				label("fast", 10, 30, 2, 200, 200, 2, 20, ObjectiveTag.FASTEST_ARRIVAL),
				label("late", 20, 50, 1, 100, 100, 1, 40, ObjectiveTag.LATEST_DEPARTURE,
					ObjectiveTag.FEWEST_TRANSFERS, ObjectiveTag.BEST_ACCESSIBILITY,
					ObjectiveTag.LOWEST_WALKING_BURDEN, ObjectiveTag.SAFEST_CONNECTION)),
			1);

		assertThat(summary).isEqualTo(new JourneyProfileSummaryPolicyV1.Departure(
			"fast", "late", List.of("fast")));
	}

	@Test
	void selectsArriveByPrimaryFromLatestDepartureAndUsesItsTemporalFacts() {
		var summary = JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.ArriveBy(START, START.plusSeconds(60)), 2),
			frontier(
				label("fast", 10, 30, 2, 200, 200, 2, 20, ObjectiveTag.FASTEST_ARRIVAL),
				label("late", 20, 50, 1, 100, 100, 1, 40, ObjectiveTag.LATEST_DEPARTURE,
					ObjectiveTag.FEWEST_TRANSFERS, ObjectiveTag.BEST_ACCESSIBILITY,
					ObjectiveTag.LOWEST_WALKING_BURDEN, ObjectiveTag.SAFEST_CONNECTION)),
			2);

		assertThat(summary).isEqualTo(new JourneyProfileSummaryPolicyV1.ArriveBy(
			START.plusSeconds(60), START.plusSeconds(20), "late", List.of("late", "fast")));
	}

	@Test
	void emitsOnlyAStrictlySaferDistinctLastConnectionAlternative() {
		var summary = JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 2)), 3),
			frontier(
				label("last", 30, 50, 1, 100, 100, 1, 40, ObjectiveTag.LATEST_DEPARTURE,
					ObjectiveTag.FEWEST_TRANSFERS, ObjectiveTag.BEST_ACCESSIBILITY),
				label("safe", 20, 60, 2, 200, 200, 2, 50, ObjectiveTag.SAFEST_CONNECTION,
					ObjectiveTag.FASTEST_ARRIVAL, ObjectiveTag.LOWEST_WALKING_BURDEN)),
			3);

		assertThat(summary).isEqualTo(new JourneyProfileSummaryPolicyV1.LastConnection(
			START.plusSeconds(30), "last", List.of("safe"), List.of("last", "safe")));

		var notStrictlySafer = JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 2)), 3),
			frontier(
				label("last", 30, 50, 1, 100, 100, 1, 40, ObjectiveTag.LATEST_DEPARTURE,
					ObjectiveTag.FEWEST_TRANSFERS, ObjectiveTag.BEST_ACCESSIBILITY),
				label("safe", 20, 60, 2, 200, 200, 2, 40, ObjectiveTag.SAFEST_CONNECTION,
					ObjectiveTag.FASTEST_ARRIVAL, ObjectiveTag.LOWEST_WALKING_BURDEN)),
			3);
		assertThat(((JourneyProfileSummaryPolicyV1.LastConnection) notStrictlySafer)
			.saferAlternativeJourneyIds()).isEmpty();
	}

	@Test
	void rejectsPointQueriesMissingRepresentativesDuplicateIdsAndMismatchedFacts() {
		var labels = frontier(
			label("fast", 10, 30, 2, 200, 200, 2, 20, ObjectiveTag.FASTEST_ARRIVAL),
			label("late", 20, 50, 1, 100, 100, 1, 40, ObjectiveTag.LATEST_DEPARTURE,
				ObjectiveTag.FEWEST_TRANSFERS, ObjectiveTag.BEST_ACCESSIBILITY,
				ObjectiveTag.LOWEST_WALKING_BURDEN, ObjectiveTag.SAFEST_CONNECTION));

		assertThatThrownBy(() -> JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.DepartAt(START), 1), labels, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(15)), 1), labels, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(60)), 1),
				frontier(label("same", 10, 30, 1, 1, 1, 1, 1, ObjectiveTag.FASTEST_ARRIVAL),
					label("same", 20, 50, 1, 1, 1, 1, 2, ObjectiveTag.LATEST_DEPARTURE,
						ObjectiveTag.FEWEST_TRANSFERS, ObjectiveTag.BEST_ACCESSIBILITY,
						ObjectiveTag.LOWEST_WALKING_BURDEN, ObjectiveTag.SAFEST_CONNECTION)), 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(60)), 1),
				frontier(label("only", 10, 30, 1, 1, 1, 1, 1, ObjectiveTag.FASTEST_ARRIVAL)), 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(60)), 1), labels, 2))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.DepartBetween(START, START.plusSeconds(60)), 1), labels, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyProfileSummaryPolicyV1.select(
			query(new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 3)), 1), labels, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("service day");
	}

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporalQuery, int alternativeCount) {
		return new JourneyRaptorQuery("01K1Y000000000000000000000", "origin", "destination", temporalQuery,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 3,
			alternativeCount, NOT_CANCELLED);
	}

	private static JourneyFrontierPolicyV1.Success frontier(SelectedLabel... labels) {
		return new JourneyFrontierPolicyV1.Success(List.of(labels), new Metrics(0, 0, 0, 0, 0, 0, 0, 0,
			JourneyFrontierPolicyV1.CapacityState.WITHIN_CAPACITY));
	}

	private static SelectedLabel label(String journeyId, long departure, long arrival, long transfers,
		long walkingSeconds, long walkingMeters, long accessibility, long slack, ObjectiveTag... tags) {
		return new SelectedLabel(new FeasibleCandidate(journeyId, START.plusSeconds(departure),
			START.plusSeconds(arrival), transfers, walkingSeconds, walkingMeters, accessibility, slack), List.of(tags));
	}
}
