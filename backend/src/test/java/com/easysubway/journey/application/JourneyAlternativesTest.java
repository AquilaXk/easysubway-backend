package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#469 결과 구성 규칙(JourneyAlternatives)")
class JourneyAlternativesTest {

	private record Option(String id, int arrival, int boardings, boolean stairFree) {
	}

	private static final Comparator<Option> ARRIVAL = Comparator.comparingInt(Option::arrival)
		.thenComparingInt(Option::boardings);

	private static JourneyAlternatives.Selection<Option> compose(List<Option> options, int count, boolean stairFreeFirst) {
		return JourneyAlternatives.compose(options, count, stairFreeFirst, ARRIVAL, Option::boardings, Option::stairFree);
	}

	@Test
	void emptyCandidatesYieldEmptySelection() {
		var selection = compose(List.of(), 3, false);

		assertThat(selection.items()).isEmpty();
		assertThat(selection.categories()).isEmpty();
		assertThat(selection.stairFreeOmitted()).isFalse();
	}

	@Test
	void keepsEveryCandidateInArrivalOrderWhenTheyFit() {
		var late = new Option("late", 300, 1, true);
		var early = new Option("early", 100, 2, false);

		var selection = compose(List.of(late, early), 3, false);

		assertThat(selection.items()).containsExactly(early, late);
		assertThat(selection.categories()).containsExactly(
			Set.of(JourneyAlternatives.Category.FASTEST),
			Set.of(JourneyAlternatives.Category.FEWEST_TRANSFERS, JourneyAlternatives.Category.STAIR_FREE));
	}

	@Test
	void reservesRepresentativesThenFillsInArrivalOrder() {
		var fastest = new Option("fastest", 100, 3, false);
		var second = new Option("second", 110, 3, false);
		var third = new Option("third", 120, 2, false);
		var direct = new Option("direct", 200, 1, true);

		// 빠른 경로이자 계단 없는 경로가 따로 있고, 남는 자리는 도착 순으로 채운다.
		var standard = compose(List.of(direct, third, second, fastest), 3, false);
		assertThat(standard.items()).containsExactly(fastest, second, direct);
		assertThat(standard.categories()).containsExactly(
			Set.of(JourneyAlternatives.Category.FASTEST), Set.of(),
			Set.of(JourneyAlternatives.Category.FEWEST_TRANSFERS, JourneyAlternatives.Category.STAIR_FREE));
		assertThat(standard.stairFreeOmitted()).isFalse();
	}

	@Test
	void ordersStairFreeBeforeFewestTransfersWhenRequested() {
		var fastest = new Option("fastest", 100, 3, false);
		var fewest = new Option("fewest", 150, 1, false);
		var stairFree = new Option("stair-free", 180, 2, true);

		assertThat(compose(List.of(fastest, fewest, stairFree), 2, false).items()).containsExactly(fastest, fewest);
		var preferred = compose(List.of(fastest, fewest, stairFree), 2, true);
		assertThat(preferred.items()).containsExactly(fastest, stairFree);
		assertThat(preferred.stairFreeOmitted()).isFalse();
		var standard = compose(List.of(fastest, fewest, stairFree), 2, false);
		assertThat(standard.stairFreeOmitted()).isTrue();
	}

	@Test
	void leavesStairFreeGroupEmptyWithoutStairFreeCandidate() {
		var fastest = new Option("fastest", 100, 2, false);
		var other = new Option("other", 110, 2, false);
		var fewest = new Option("fewest", 150, 1, false);

		var selection = compose(List.of(fastest, other, fewest), 2, true);

		assertThat(selection.items()).containsExactly(fastest, fewest);
		assertThat(selection.categories()).noneMatch(set -> set.contains(JourneyAlternatives.Category.STAIR_FREE));
		assertThat(selection.stairFreeOmitted()).isFalse();
	}

	@Test
	void rejectsInvalidInputs() {
		assertThatThrownBy(() -> compose(List.of(new Option("a", 1, 1, true)), 0, false))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyAlternatives.Selection<>(List.of("a"), List.of(), false))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyAlternatives.StairFreeAlternative(null,
			JourneyAlternatives.FacilityStatus.APPLIED)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void decidesStairFreeStatus() {
		assertThat(JourneyAlternatives.stairFreeStatus(true, true, true))
			.isEqualTo(JourneyAlternatives.StairFreeStatus.INCLUDED);
		assertThat(JourneyAlternatives.stairFreeStatus(false, true, true))
			.isEqualTo(JourneyAlternatives.StairFreeStatus.OMITTED);
		assertThat(JourneyAlternatives.stairFreeStatus(false, false, true))
			.isEqualTo(JourneyAlternatives.StairFreeStatus.UNDETERMINED);
		assertThat(JourneyAlternatives.stairFreeStatus(false, false, false))
			.isEqualTo(JourneyAlternatives.StairFreeStatus.NOT_FOUND);
	}
}
