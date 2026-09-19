package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.journey.application.JourneyFrontierPolicyV1.FeasibleCandidate;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.ObjectiveTag;
import com.easysubway.journey.application.JourneyProfileRaptorPort.MinimumTransferSeconds;
import com.easysubway.journey.application.JourneyProfileRaptorPort.NoTransfer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyFrontierPolicyV1Test {

	private static final Instant START = Instant.parse("2026-09-02T00:00:00Z");

	@Test
	void publishesTheFixedPolicyIdentity() {
		assertThat(JourneyFrontierPolicyV1.identity())
			.isEqualTo(new JourneyFrontierPolicyV1.Identity("FRONTIER_POLICY_V1", "1.0.0"));
	}

	@Test
	void matchesAnIndependentUnboundedParetoOracleAcrossAllSevenDimensions() {
		List<TestLabel> labels = List.of(
			label("fast", 20, 70, 2, 300, 400, 3, 70),
			label("late", 30, 90, 1, 200, 300, 2, 80),
			label("walk", 22, 85, 2, 100, 100, 1, 60),
			label("dominated", 20, 90, 3, 400, 500, 4, 50));

		var outcome = JourneyFrontierPolicyV1.evaluate(
			toProduction(labels), EnumSet.allOf(ObjectiveTag.class), 6);

		assertThat(outcome).isInstanceOf(JourneyFrontierPolicyV1.Success.class);
		var success = (JourneyFrontierPolicyV1.Success) outcome;
		assertThat(success.labels()).extracting(item -> item.candidate().journeyId())
			.containsExactlyInAnyOrderElementsOf(independentFront(labels).stream().map(TestLabel::journeyId).toList());
		assertThat(success.metrics().labelsDominated()).isEqualTo(1);
		assertThat(success.metrics().maxLabelsObservedPerState()).isEqualTo(3);
		assertThat(success.metrics().labelsGenerated()).isEqualTo(
			success.metrics().labelsAccepted() + success.metrics().labelsDominated()
				+ success.metrics().labelsDeduplicated());
	}

	@Test
	void aggregatesRequiredTagsOnTheirSingleDeterministicRepresentatives() {
		var outcome = JourneyFrontierPolicyV1.evaluate(List.of(
			candidate("a", 20, 70, 2, 300, 400, 3, 70),
			candidate("b", 30, 90, 1, 200, 300, 2, 80),
			candidate("c", 22, 85, 2, 100, 100, 1, 60)),
			EnumSet.allOf(ObjectiveTag.class), 6);

		var success = (JourneyFrontierPolicyV1.Success) outcome;
		assertThat(success.labels()).extracting(
			item -> item.candidate().journeyId(), JourneyFrontierPolicyV1.SelectedLabel::objectiveTags)
			.containsExactlyInAnyOrder(
				tuple("a", List.of(ObjectiveTag.FASTEST_ARRIVAL)),
				tuple("b", List.of(ObjectiveTag.LATEST_DEPARTURE, ObjectiveTag.FEWEST_TRANSFERS,
					ObjectiveTag.SAFEST_CONNECTION)),
				tuple("c", List.of(ObjectiveTag.LOWEST_WALKING_BURDEN, ObjectiveTag.BEST_ACCESSIBILITY)));
	}

	@Test
	void resolvesObjectiveTiesByJourneyIdRegardlessOfInputOrder() {
		FeasibleCandidate first = candidate("a", 20, 80, 1, 100, 100, 1, 60);
		FeasibleCandidate second = candidate("b", 20, 80, 1, 100, 100, 1, 60);

		var outcome = JourneyFrontierPolicyV1.evaluate(List.of(second, first),
			EnumSet.of(ObjectiveTag.FASTEST_ARRIVAL, ObjectiveTag.LATEST_DEPARTURE), 2);

		var success = (JourneyFrontierPolicyV1.Success) outcome;
		assertThat(success.labels()).singleElement().satisfies(label -> {
			assertThat(label.candidate().journeyId()).isEqualTo("a");
			assertThat(label.objectiveTags()).containsExactlyInAnyOrder(
				ObjectiveTag.FASTEST_ARRIVAL, ObjectiveTag.LATEST_DEPARTURE);
		});
	}

	@Test
	void ranksADirectJourneyAsSaferThanEveryTransferConnectionWithoutANumericSentinel() {
		FeasibleCandidate direct = new FeasibleCandidate("direct", START.plusSeconds(20), START.plusSeconds(80),
			0, 100, 100, 0, new NoTransfer());
		FeasibleCandidate transfer = new FeasibleCandidate("transfer", START.plusSeconds(20), START.plusSeconds(80),
			1, 100, 100, 0, new MinimumTransferSeconds(600));

		var outcome = JourneyFrontierPolicyV1.evaluate(List.of(transfer, direct),
			EnumSet.of(ObjectiveTag.SAFEST_CONNECTION), 1);

		var success = (JourneyFrontierPolicyV1.Success) outcome;
		assertThat(success.labels()).singleElement().satisfies(label ->
			assertThat(label.candidate().journeyId()).isEqualTo("direct"));
	}

	@Test
	void rejectsConflictingDuplicateJourneyIdsAndDeduplicatesExactDuplicates() {
		FeasibleCandidate original = candidate("same", 20, 80, 1, 100, 100, 1, 60);
		assertThatThrownBy(() -> JourneyFrontierPolicyV1.evaluate(List.of(original,
			candidate("same", 20, 81, 1, 100, 100, 1, 60)),
			EnumSet.of(ObjectiveTag.FASTEST_ARRIVAL), 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("conflicting duplicate journeyId: same");

		var outcome = JourneyFrontierPolicyV1.evaluate(List.of(original, original),
			EnumSet.of(ObjectiveTag.FASTEST_ARRIVAL), 1);
		var success = (JourneyFrontierPolicyV1.Success) outcome;
		assertThat(success.metrics().labelsDeduplicated()).isEqualTo(1);
		assertThat(success.labels()).hasSize(1);
	}

	@Test
	void failsClosedWhenUniqueRequiredRepresentativesExceedTheCallerBound() {
		var outcome = JourneyFrontierPolicyV1.evaluate(List.of(
			candidate("fast", 20, 70, 2, 300, 400, 3, 70),
			candidate("late", 30, 90, 1, 200, 300, 2, 80),
			candidate("walk", 22, 85, 2, 100, 100, 1, 60)),
			EnumSet.allOf(ObjectiveTag.class), 2);

		var exceeded = (JourneyFrontierPolicyV1.CapacityExceeded) outcome;
		assertThat(exceeded.selectedLabels()).isEmpty();
		assertThat(exceeded.metrics().capacityState())
			.isEqualTo(JourneyFrontierPolicyV1.CapacityState.EXCEEDED);
		assertThat(exceeded.metrics().labelsPrunedByBound()).isEqualTo(1);
	}

	@Test
	void rejectsHardInvalidCandidatesAndNonPositiveCallerBounds() {
		assertThatThrownBy(() -> candidate("bad", 20, 20, 0, 0, 0, 0, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> JourneyFrontierPolicyV1.evaluate(List.of(),
			EnumSet.of(ObjectiveTag.FASTEST_ARRIVAL), 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("maxDestinationProfileLabels must be positive");
	}

	private static List<FeasibleCandidate> toProduction(List<TestLabel> labels) {
		return labels.stream().map(label -> candidate(label.journeyId(), label.departureOffset(), label.arrivalOffset(),
			label.transfers(), label.walkingSeconds(), label.walkingDistanceMeters(), label.accessibilityBurden(),
			label.minimumConnectionSlackSeconds())).toList();
	}

	private static FeasibleCandidate candidate(String id, long departure, long arrival, int transfers,
		long walkingSeconds, long walkingDistanceMeters, int accessibilityBurden, long slack) {
		return new FeasibleCandidate(id, START.plusSeconds(departure), START.plusSeconds(arrival), transfers,
			walkingSeconds, walkingDistanceMeters, accessibilityBurden, new MinimumTransferSeconds(slack));
	}

	private static TestLabel label(String id, long departure, long arrival, int transfers, long walkingSeconds,
		long walkingDistanceMeters, int accessibilityBurden, long slack) {
		return new TestLabel(id, departure, arrival, transfers, walkingSeconds, walkingDistanceMeters,
			accessibilityBurden, slack);
	}

	private static List<TestLabel> independentFront(List<TestLabel> labels) {
		List<TestLabel> result = new ArrayList<>();
		for (TestLabel candidate : labels) {
			boolean dominated = labels.stream().anyMatch(other -> other != candidate && independentlyDominates(other, candidate));
			if (!dominated) result.add(candidate);
		}
		return result.stream().sorted(Comparator.comparing(TestLabel::journeyId)).toList();
	}

	private static boolean independentlyDominates(TestLabel left, TestLabel right) {
		return left.departureOffset() >= right.departureOffset()
			&& left.arrivalOffset() <= right.arrivalOffset()
			&& left.transfers() <= right.transfers()
			&& left.walkingSeconds() <= right.walkingSeconds()
			&& left.walkingDistanceMeters() <= right.walkingDistanceMeters()
			&& left.accessibilityBurden() <= right.accessibilityBurden()
			&& left.minimumConnectionSlackSeconds() >= right.minimumConnectionSlackSeconds()
			&& (left.departureOffset() > right.departureOffset()
				|| left.arrivalOffset() < right.arrivalOffset()
				|| left.transfers() < right.transfers()
				|| left.walkingSeconds() < right.walkingSeconds()
				|| left.walkingDistanceMeters() < right.walkingDistanceMeters()
				|| left.accessibilityBurden() < right.accessibilityBurden()
				|| left.minimumConnectionSlackSeconds() > right.minimumConnectionSlackSeconds());
	}

	private record TestLabel(String journeyId, long departureOffset, long arrivalOffset, int transfers,
		long walkingSeconds, long walkingDistanceMeters, int accessibilityBurden,
		long minimumConnectionSlackSeconds) {
	}
}
