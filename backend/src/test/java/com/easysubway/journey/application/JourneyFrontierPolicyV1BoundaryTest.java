package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.easysubway.journey.application.JourneyFrontierFixtures.START;
import static com.easysubway.journey.application.JourneyFrontierFixtures.candidate;

import com.easysubway.journey.application.JourneyFrontierPolicyV1.CapacityExceeded;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.CapacityState;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.FeasibleCandidate;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.Identity;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.Metrics;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.ObjectiveTag;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.SelectedLabel;
import com.easysubway.journey.application.JourneyFrontierPolicyV1.Success;
import com.easysubway.journey.application.JourneyProfileRaptorPort.ConnectionSlack;
import com.easysubway.journey.application.JourneyProfileRaptorPort.MinimumTransferSeconds;
import com.easysubway.journey.application.JourneyProfileRaptorPort.NoTransfer;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 정책 정의에서 손으로 계산한 기대값만 쓴다(운영 함수 결과로 기대값을 만들지 않는다).
 * PIT 첫 기준선의 생존 변이(Frontier 지표·걷기 부담 동률·생성자 검증 경계)를 고정한다(#3036).
 */
class JourneyFrontierPolicyV1BoundaryTest {

	private static final Set<ObjectiveTag> ALL_TAGS = EnumSet.allOf(ObjectiveTag.class);
	private static final Set<ObjectiveTag> FASTEST_ONLY = EnumSet.of(ObjectiveTag.FASTEST_ARRIVAL);

	/** 출발이 늦을수록 도착도 늦어 서로 지배하지 않는 3개. id 오름차순이면 도착이 점점 빨라진다. */
	private static List<FeasibleCandidate> tradeOffTrio() {
		return List.of(
			candidate("a", 40, 90, 3, 100, 100, 0, 60),
			candidate("b", 30, 80, 2, 100, 100, 0, 60),
			candidate("c", 20, 70, 1, 100, 100, 0, 60));
	}

	private enum Dimension {
		DEPARTURE, ARRIVAL, TRANSFERS, WALKING_SECONDS, WALKING_DISTANCE, ACCESSIBILITY, SLACK
	}

	/** 기준 후보(출발 20, 도착 80, 환승 2, 도보 100초·100m, 부담 1, 여유 60초)보다 지정한 한 차원만 더 좋은 후보. */
	private static FeasibleCandidate betterOnlyIn(Dimension better) {
		return candidate("a",
			20 + step(better, Dimension.DEPARTURE, 1),
			80 + step(better, Dimension.ARRIVAL, -1),
			2 + step(better, Dimension.TRANSFERS, -1),
			100 + step(better, Dimension.WALKING_SECONDS, -1),
			100 + step(better, Dimension.WALKING_DISTANCE, -1),
			1 + step(better, Dimension.ACCESSIBILITY, -1),
			60 + step(better, Dimension.SLACK, 1));
	}

	private static long step(Dimension better, Dimension dimension, long improvement) {
		return better == dimension ? improvement : 0;
	}

	@ParameterizedTest(name = "{0} 한 차원만 좋으면 지배한다")
	@EnumSource(Dimension.class)
	void aCandidateBetterInExactlyOneDimensionDominatesAnOtherwiseEqualOne(Dimension dimension) {
		FeasibleCandidate worse = candidate("b", 20, 80, 2, 100, 100, 1, 60);

		Success success = (Success) JourneyFrontierPolicyV1.evaluate(
			List.of(worse, betterOnlyIn(dimension)), ALL_TAGS, 6);

		assertThat(success.labels()).extracting(label -> label.candidate().journeyId()).containsExactly("a");
		assertThat(success.metrics().labelsAccepted()).as(dimension.name()).isEqualTo(1);
		assertThat(success.metrics().labelsDominated()).as(dimension.name()).isEqualTo(1);
	}

	@Test
	void dominanceUsesTheRawSlackEvenAboveTheSafestConnectionCreditCap() {
		// 700초와 1500초는 600초 상한을 적용하면 같아지지만, 지배 판정은 원래 값을 쓰므로 1500초가 700초를 지배한다.
		Success success = (Success) JourneyFrontierPolicyV1.evaluate(List.of(
			candidate("b", 20, 80, 2, 100, 100, 1, 700),
			candidate("a", 20, 80, 2, 100, 100, 1, 1_500)), ALL_TAGS, 6);

		assertThat(success.labels()).extracting(label -> label.candidate().journeyId()).containsExactly("a");
		assertThat(success.metrics().labelsDominated()).isEqualTo(1);
	}

	@Test
	void candidatesEqualInEveryDimensionDoNotDominateEachOther() {
		Success success = (Success) JourneyFrontierPolicyV1.evaluate(List.of(
			candidate("a", 20, 80, 2, 100, 100, 1, 60),
			candidate("b", 20, 80, 2, 100, 100, 1, 60)), ALL_TAGS, 6);

		assertThat(success.metrics().labelsAccepted()).isEqualTo(2);
		assertThat(success.metrics().labelsDominated()).isZero();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("walkingBurdenTable")
	void lowestWalkingBurdenComparesSecondsFirstAndDistanceOnlyOnATie(String name, List<FeasibleCandidate> input,
		String expectedId) {
		Success success = (Success) JourneyFrontierPolicyV1.evaluate(input,
			EnumSet.of(ObjectiveTag.LOWEST_WALKING_BURDEN), 1);

		assertThat(success.labels()).extracting(label -> label.candidate().journeyId()).containsExactly(expectedId);
	}

	static Stream<Arguments> walkingBurdenTable() {
		return Stream.of(
			// 100초/500m 대 200초/100m: 시간이 짧은 쪽이 거리가 길어도 이긴다.
			Arguments.of("시간 우선", List.of(
				candidate("a", 20, 80, 1, 100, 500, 0, 60),
				candidate("b", 20, 80, 1, 200, 100, 0, 60)), "a"),
			// 시간 동률이면 거리가 짧은 쪽. c는 도착이 1초 늦어 a와 서로 지배하지 않는다.
			Arguments.of("시간 동률은 거리", List.of(
				candidate("a", 20, 80, 1, 100, 500, 0, 60),
				candidate("c", 20, 81, 1, 100, 300, 0, 60)), "c"));
	}

	@ParameterizedTest(name = "입력 순서 {0}")
	@MethodSource("trioOrders")
	void countsRepresentativeReplacementsInCanonicalIdOrderRegardlessOfInputOrder(String order,
		List<FeasibleCandidate> input) {
		Success success = (Success) JourneyFrontierPolicyV1.evaluate(input, FASTEST_ONLY, 5);

		// id 오름차순 a(90)->b(80)->c(70)로 대표가 두 번 바뀐다.
		assertThat(success.metrics()).isEqualTo(new Metrics(3, 3, 0, 0, 0, 2, 0, 3, CapacityState.WITHIN_CAPACITY));
		assertThat(success.labels()).extracting(label -> label.candidate().journeyId()).containsExactly("c");
		assertThat(success.labels()).singleElement().satisfies(label ->
			assertThat(label.objectiveTags()).containsExactly(ObjectiveTag.FASTEST_ARRIVAL));
	}

	static Stream<Arguments> trioOrders() {
		List<FeasibleCandidate> trio = tradeOffTrio();
		return Stream.of(
			Arguments.of("a,b,c", trio),
			Arguments.of("c,b,a", List.of(trio.get(2), trio.get(1), trio.get(0))));
	}

	@Test
	void countsReplacementsOnlyForRequiredTags() {
		// FEWEST_TRANSFERS(3->2->1)도 두 번 바뀌지만 요구하지 않았으므로 FASTEST_ARRIVAL의 2회만 센다.
		Success fastestOnly = (Success) JourneyFrontierPolicyV1.evaluate(tradeOffTrio(), FASTEST_ONLY, 5);
		Success both = (Success) JourneyFrontierPolicyV1.evaluate(tradeOffTrio(),
			EnumSet.of(ObjectiveTag.FASTEST_ARRIVAL, ObjectiveTag.FEWEST_TRANSFERS), 5);

		assertThat(fastestOnly.metrics().labelsReplacedByRepresentativePolicy()).isEqualTo(2);
		assertThat(both.metrics().labelsReplacedByRepresentativePolicy()).isEqualTo(4);
	}

	@ParameterizedTest(name = "필요 대표 {1}개, 상한 {2} -> 용량 도달 {3}")
	@MethodSource("capacityTable")
	void reportsStatesAtLabelCapacityOnlyWhenRequiredRepresentativesReachTheBound(Set<ObjectiveTag> tags,
		int requiredRepresentatives, int bound, long expectedStatesAtCapacity) {
		var outcome = JourneyFrontierPolicyV1.evaluate(tradeOffTrio(), tags, bound);

		assertThat(outcome.metrics().statesAtLabelCapacity()).isEqualTo(expectedStatesAtCapacity);
		assertThat(outcome instanceof CapacityExceeded).isEqualTo(requiredRepresentatives > bound);
	}

	static Stream<Arguments> capacityTable() {
		Set<ObjectiveTag> fastestAndLatest = EnumSet.of(ObjectiveTag.FASTEST_ARRIVAL, ObjectiveTag.LATEST_DEPARTURE);
		return Stream.of(
			Arguments.of(FASTEST_ONLY, 1, 2, 0L),
			Arguments.of(FASTEST_ONLY, 1, 1, 1L),
			Arguments.of(fastestAndLatest, 2, 1, 1L));
	}

	@Test
	void rejectsAnEmptyRequiredObjectiveSet() {
		assertThatThrownBy(() -> JourneyFrontierPolicyV1.evaluate(tradeOffTrio(), EnumSet.noneOf(ObjectiveTag.class), 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("requiredObjectiveTags must not be empty");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("candidateMetricFields")
	void feasibleCandidateRejectsNegativeMetricsButAcceptsZero(String field,
		Function<Long, FeasibleCandidate> withValue) {
		assertThatCode(() -> withValue.apply(0L)).doesNotThrowAnyException();
		assertThatThrownBy(() -> withValue.apply(-1L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("feasible candidate metrics must not be negative");
	}

	static Stream<Arguments> candidateMetricFields() {
		return Stream.of(
			Arguments.of("transfers", (Function<Long, FeasibleCandidate>) value -> new FeasibleCandidate("x",
				START, START.plusSeconds(60), value, 0, 0, 0, slackFor(value))),
			Arguments.of("walkingSeconds", (Function<Long, FeasibleCandidate>) value -> new FeasibleCandidate("x",
				START, START.plusSeconds(60), 0, value, 0, 0, new NoTransfer())),
			Arguments.of("walkingDistanceMeters", (Function<Long, FeasibleCandidate>) value -> new FeasibleCandidate("x",
				START, START.plusSeconds(60), 0, 0, value, 0, new NoTransfer())),
			Arguments.of("accessibilityBurden", (Function<Long, FeasibleCandidate>) value -> new FeasibleCandidate("x",
				START, START.plusSeconds(60), 0, 0, 0, value, new NoTransfer())));
	}

	@ParameterizedTest(name = "도착 {0}초")
	@MethodSource("nonPositiveTravelTimes")
	void feasibleCandidateRequiresArrivalStrictlyAfterDeparture(long arrivalOffset) {
		assertThatThrownBy(() -> new FeasibleCandidate("x", START, START.plusSeconds(arrivalOffset), 0, 0, 0, 0,
			new NoTransfer()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("arrivalAtDestination must be after departure");
	}

	static Stream<Long> nonPositiveTravelTimes() {
		return Stream.of(0L, -1L);
	}

	@ParameterizedTest(name = "환승 {0}회 + {1}")
	@MethodSource("mismatchedSlack")
	void feasibleCandidateRequiresConnectionSlackMatchingTheTransferCount(long transfers, ConnectionSlack slack) {
		assertThatThrownBy(() -> new FeasibleCandidate("x", START, START.plusSeconds(60), transfers, 0, 0, 0, slack))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("connection slack must match transfer count");
	}

	static Stream<Arguments> mismatchedSlack() {
		return Stream.of(
			Arguments.of(0L, new MinimumTransferSeconds(60)),
			Arguments.of(1L, new NoTransfer()));
	}

	@Test
	void textFieldsRejectBlankValues() {
		assertThatThrownBy(() -> new Identity(" ", "1.0.0"))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("frontierPolicyId must not be blank");
		assertThatThrownBy(() -> new FeasibleCandidate("", START, START.plusSeconds(60), 0, 0, 0, 0, new NoTransfer()))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("journeyId must not be blank");
	}

	@Test
	void selectedLabelRequiresNonEmptyUniqueObjectiveTags() {
		FeasibleCandidate direct = candidate("a", 20, 80, 1, 0, 0, 0, 60);
		assertThatThrownBy(() -> new SelectedLabel(direct, List.of()))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("objectiveTags must not be empty");
		assertThatThrownBy(() -> new SelectedLabel(direct,
			List.of(ObjectiveTag.FASTEST_ARRIVAL, ObjectiveTag.FASTEST_ARRIVAL)))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("objectiveTags must be unique");
	}

	@ParameterizedTest(name = "필요 {0} 상한 {1} 용량 {2}")
	@MethodSource("invalidCapacityFailures")
	void capacityExceededRequiresExceededMetricsAndRequiredAboveAPositiveBound(int required, int bound,
		CapacityState state, String message) {
		assertThatThrownBy(() -> new CapacityExceeded(metrics(state), required, bound))
			.isInstanceOf(IllegalArgumentException.class).hasMessage(message);
	}

	static Stream<Arguments> invalidCapacityFailures() {
		String bounds = "capacity failure bounds are invalid";
		return Stream.of(
			Arguments.of(2, 1, CapacityState.WITHIN_CAPACITY, "capacity failure requires exceeded metrics"),
			Arguments.of(1, 1, CapacityState.EXCEEDED, bounds),
			Arguments.of(0, 0, CapacityState.EXCEEDED, bounds),
			Arguments.of(1, 0, CapacityState.EXCEEDED, bounds));
	}

	@Test
	void capacityExceededAcceptsRequiredJustAboveAPositiveBound() {
		assertThatCode(() -> new CapacityExceeded(metrics(CapacityState.EXCEEDED), 2, 1)).doesNotThrowAnyException();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("metricFields")
	void metricsRejectEachNegativeCounterButAcceptZero(String field, long[] counters) {
		assertThatCode(() -> metrics(new long[8], CapacityState.WITHIN_CAPACITY)).doesNotThrowAnyException();
		assertThatThrownBy(() -> metrics(counters, CapacityState.WITHIN_CAPACITY))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("frontier metrics must not be negative");
	}

	static Stream<Arguments> metricFields() {
		String[] names = {"labelsGenerated", "labelsAccepted", "labelsDominated", "labelsDeduplicated",
			"labelsPrunedByBound", "labelsReplacedByRepresentativePolicy", "statesAtLabelCapacity",
			"maxLabelsObservedPerState"};
		return IntStream.range(0, names.length).mapToObj(index -> {
			long[] counters = new long[names.length];
			counters[index] = -1;
			return Arguments.of(names[index], counters);
		});
	}

	private static Metrics metrics(CapacityState state) {
		return metrics(new long[8], state);
	}

	private static Metrics metrics(long[] c, CapacityState state) {
		return new Metrics(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], state);
	}

	private static ConnectionSlack slackFor(long transfers) {
		return transfers == 0 ? new NoTransfer() : new MinimumTransferSeconds(60);
	}
}
