package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #454: 오라클도 승강장에서 시작해 승강장에서 끝나는 여정만 다룬다(진입·하차 이동 없음). */
class JourneyProfileExactOracleTest {

	private static final LocalDate DAY = LocalDate.of(2026, 7, 1);
	private final JourneyProfileExactOracle oracle = new JourneyProfileExactOracle();

	@Test
	void retainsTheCompleteImmutableRideAndTransferTraceForDifferentialComparison() {
		var first = ride("first", DAY, "origin", "a", "change", "a", at(100), at(200));
		var second = ride("second", DAY, "change", "b", "destination", "b", at(300), at(400));
		var transfer = transfer("change", "a", "b", 20);
		var result = oracle.solve(query(at(0), at(500), 1, 0, 10_000, () -> false),
			List.of(first, second), List.of(transfer));
		assertThat(result).hasSize(1);
		assertThat(result.getFirst().rides()).containsExactly(first, second);
		assertThat(result.getFirst().accesses()).containsExactly(transfer);
		assertThat(result.getFirst().readyAt()).isEqualTo(at(100));
		assertThat(result.getFirst().arrivalAtDestination()).isEqualTo(at(400));
		assertThatThrownBy(result.getFirst().rides()::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(result.getFirst().accesses()::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void solvesPointAtFixedReadinessBeforeApplyingPareto() {
		var earlier = ride("earlier", DAY, "origin", "a", "destination", "a", at(100), at(200));
		var later = ride("later", DAY, "origin", "a", "destination", "a", at(200), at(300));
		var query = query(at(0), at(400), 0, 0, 10_000, () -> false);

		assertThat(oracle.solve(query, List.of(earlier, later), List.of())).hasSize(2);
		var point = oracle.solvePoint(query, List.of(earlier, later), List.of());
		assertThat(point).singleElement().satisfies(candidate -> {
			assertThat(candidate.readyAt()).isEqualTo(at(0));
			assertThat(candidate.rides()).containsExactly(earlier);
			assertThat(candidate.accesses()).isEmpty();
		});
	}

	@Test
	void requiresExactSlackFeasibilityBeforeFixingPointReadiness() {
		var exact = ride("exact", DAY, "origin", "a", "destination", "a", at(100), at(200));
		var late = ride("late", DAY, "origin", "a", "destination", "a", at(99), at(199));
		var query = query(at(95), at(300), 0, 5, 10_000, () -> false);

		assertThat(oracle.solvePoint(query, List.of(exact), List.of())).singleElement()
			.extracting(JourneyProfileExactOracle.Candidate::readyAt).isEqualTo(at(95));
		assertThat(oracle.solvePoint(query, List.of(late), List.of())).isEmpty();
	}

	@Test
	void keepsOnlyArrivalsWithinTheAlternativeWindowOfTheEarliestArrivalBeforePareto() {
		// 환승 1회로 200초에 도착하는 여정과, 환승 없이 늦게 도착하는 직행 두 개. 창이 없으면 셋 다 파레토다.
		var feeder = ride("feeder", DAY, "origin", "a", "hub", "a", at(100), at(150));
		var fast = ride("fast", DAY, "hub", "b", "destination", "b", at(160), at(200));
		var edge = ride("edge", DAY, "origin", "c", "destination", "c", at(100), at(2_000));
		var outside = ride("outside", DAY, "origin", "d", "destination", "d", at(50), at(2_001));
		var rides = List.of(feeder, fast, edge, outside);
		var accesses = List.of(transfer("hub", "a", "b", 0));
		var query = query(at(0), at(3_000), 1, 0, 10_000, () -> false);

		assertThat(JourneyProfileExactOracle.ALTERNATIVE_WINDOW_SECONDS)
			.isEqualTo(com.easysubway.journey.application.JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW_SECONDS);
		assertThat(oracle.solvePoint(query, rides, accesses)).hasSize(2)
			.extracting(candidate -> candidate.rides().getFirst()).containsExactlyInAnyOrder(feeder, edge);
		// 가장 이른 도착 200초 + 30분 = 2000초까지만 대안이다. 2001초 도착은 창 밖이다.
		assertThat(oracle.solvePointArrivalWindow(query, rides, accesses))
			.extracting(candidate -> candidate.rides().getFirst()).containsExactlyInAnyOrder(feeder, edge);
		assertThat(oracle.solvePointArrivalWindow(query, List.of(feeder, fast, outside), accesses)).singleElement()
			.extracting(candidate -> candidate.rides().getFirst()).isEqualTo(feeder);
		assertThat(oracle.solvePoint(query, List.of(feeder, fast, outside), accesses)).hasSize(2);
	}

	@Test
	void keepsOnlyReadinessWithinTheAlternativeWindowOfTheLatestReadinessBeforePareto() {
		var latest = ride("latest", DAY, "origin", "a", "destination", "a", at(4_000), at(4_100));
		var edge = ride("edge", DAY, "origin", "a", "destination", "a", at(2_200), at(2_250));
		var outside = ride("outside", DAY, "origin", "a", "destination", "a", at(2_199), at(2_210));
		var query = query(at(0), at(5_000), 0, 0, 10_000, () -> false);

		assertThat(oracle.solve(query, List.of(latest, edge, outside), List.of())).hasSize(3);
		// 가장 늦은 준비 4000초 - 30분 = 2200초부터가 대안이다.
		assertThat(oracle.solveLatestReadyWindow(query, List.of(latest, edge, outside), List.of()))
			.extracting(candidate -> candidate.rides().getFirst()).containsExactlyInAnyOrder(latest, edge);
	}

	@Test
	void retainsMoreThanThreeNonDominatedRoutesWithoutAPublicResultCap() {
		var rides = List.of(
			ride("one", DAY, "origin", "a", "destination", "a", at(100), at(500)),
			ride("two", DAY, "origin", "b", "destination", "b", at(200), at(700)),
			ride("three", DAY, "origin", "c", "destination", "c", at(300), at(900)),
			ride("four", DAY, "origin", "d", "destination", "d", at(400), at(1_100)));

		var result = oracle.solve(query(at(0), at(2_000), 0, 0, 10_000, () -> false), rides, List.of());

		assertThat(result).hasSize(4).extracting(JourneyProfileExactOracle.Candidate::pathIdentity)
			.doesNotHaveDuplicates();
		assertThat(result).extracting(JourneyProfileExactOracle.Candidate::readyAt)
			.containsExactlyInAnyOrder(at(100), at(200), at(300), at(400));
	}

	@Test
	void rejectsAnAsymmetricOrMissingDirectionalTransfer() {
		var rides = List.of(
			ride("first", DAY, "origin", "a", "change", "a", at(100), at(200)),
			ride("second", DAY, "change", "b", "destination", "b", at(300), at(400)));

		assertThat(oracle.solve(query(at(0), at(500), 1, 0, 10_000, () -> false), rides,
			List.of(transfer("change", "b", "a", 10)))).isEmpty();
		assertThat(oracle.solve(query(at(0), at(500), 1, 0, 10_000, () -> false), rides, List.of())).isEmpty();
	}

	@Test
	void neverBoardsOrAlightsAtAnotherStationOnTheSameLine() {
		for (var ride : List.of(
			ride("wrong-origin", DAY, "elsewhere", "a", "destination", "a", at(100), at(200)),
			ride("wrong-destination", DAY, "origin", "a", "elsewhere", "a", at(100), at(200)))) {
			assertThat(oracle.solve(query(at(0), at(300), 0, 0, 10_000, () -> false),
				List.of(ride), List.of())).isEmpty();
		}
	}

	@Test
	void excludesAChainWhosePlatformArrivalIsAfterTheDeadline() {
		var rides = List.of(ride("direct", DAY, "origin", "a", "destination", "a", at(100), at(500)));

		assertThat(oracle.solve(query(at(0), at(499), 0, 0, 10_000, () -> false), rides, List.of())).isEmpty();
		assertThat(oracle.solve(query(at(0), at(500), 0, 0, 10_000, () -> false), rides, List.of())).singleElement()
			.extracting(JourneyProfileExactOracle.Candidate::arrivalAtDestination).isEqualTo(at(500));
	}

	@Test
	void connectsDatedTripsAcrossServiceDatesAndPreservesTheirDistinctIdentity() {
		var nextDay = DAY.plusDays(1);
		var rides = List.of(
			ride("late", DAY, "origin", "a", "change", "a", Instant.parse("2026-07-01T18:00:00Z"),
				Instant.parse("2026-07-01T18:10:00Z")),
			ride("early", nextDay, "change", "b", "destination", "b", Instant.parse("2026-07-01T18:16:00Z"),
				Instant.parse("2026-07-01T18:26:00Z")));

		var result = oracle.solve(query(Instant.parse("2026-07-01T17:00:00Z"), Instant.parse("2026-07-01T19:00:00Z"),
			1, 60, 10_000, () -> false), rides, List.of(transfer("change", "a", "b", 300)));

		assertThat(result).singleElement().satisfies(candidate -> {
			assertThat(candidate.pathIdentity()).contains("2026-07-01", "late", "2026-07-02", "early");
			assertThat(candidate.readyAt()).isEqualTo(Instant.parse("2026-07-01T17:59:00Z"));
			assertThat(candidate.arrivalAtDestination()).isEqualTo(Instant.parse("2026-07-01T18:26:00Z"));
			assertThat(candidate.minimumConnectionSlack())
				.isEqualTo(new JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds(0));
		});
	}

	@Test
	void acceptsTransferSlackExactlyEqualToTheRequiredAccessAndBoardingTime() {
		var rides = List.of(
			ride("first", DAY, "origin", "a", "change", "a", at(100), at(200)),
			ride("second", DAY, "change", "b", "destination", "b", at(240), at(300)));

		var result = oracle.solve(query(at(0), at(400), 1, 10, 10_000, () -> false), rides,
			List.of(transfer("change", "a", "b", 30)));

		assertThat(result).singleElement().extracting(JourneyProfileExactOracle.Candidate::minimumConnectionSlack)
			.isEqualTo(new JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds(0));
	}

	@Test
	void rejectsDuplicateDatedTripAndPositionIdentityInsteadOfSilentlyCollapsingIt() {
		var duplicate = ride("same", DAY, "origin", "a", "destination", "a", at(100), at(200));

		assertThatThrownBy(() -> oracle.solve(query(at(0), at(300), 0, 0, 10_000, () -> false),
			List.of(duplicate, duplicate), List.of()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate ride identity");
	}

	@Test
	void keepsFrequencyInstancesDistinctWithinTheSameTripAndServiceDate() {
		var first = ride("frequency", DAY, "origin", "a", "destination", "a", at(100), at(200));
		var next = new JourneyProfileExactOracle.Ride("frequency", DAY, 1,
			"origin", "a", "destination", "a", at(300), at(400), 0, 1, true, true);
		assertThat(oracle.solve(query(at(0), at(500), 0, 0, 10_000, () -> false),
			List.of(first, next), List.of()))
			.hasSize(2).extracting(JourneyProfileExactOracle.Candidate::pathIdentity).doesNotHaveDuplicates();
	}

	@Test
	void acceptsEqualTimeEventsWithoutInventingAMinimumRideDuration() {
		var zeroDuration = ride("equal-time", DAY, "origin", "a", "destination", "a", at(100), at(100));
		assertThat(oracle.solve(query(at(0), at(200), 0, 0, 10_000, () -> false),
			List.of(zeroDuration), List.of())).singleElement()
			.extracting(JourneyProfileExactOracle.Candidate::arrivalAtDestination).isEqualTo(at(100));
	}

	@Test
	void requiresBothTransferLineEnds() {
		assertThatThrownBy(() -> new JourneyProfileExactOracle.Access("transfer-null-from", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"change", null, "change", "b", 0, 1, 0, true, true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyProfileExactOracle.Access("transfer-null-to", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"change", "a", "change", null, 0, 1, 0, true, true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyProfileExactOracle.Access("transfer-blank-from", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"change", "", "change", "b", 0, 1, 0, true, true)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void preservesDifferentTransferPathsForTheSameRidesAndRejectsConflictingAccessIdentity() {
		var first = ride("first", DAY, "origin", "a", "change", "a", at(100), at(200));
		var second = ride("second", DAY, "change", "b", "destination", "b", at(300), at(400));
		var fastLong = new JourneyProfileExactOracle.Access("fast-long", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"change", "a", "change", "b", 10, 100, 0, true, true);
		var slowShort = new JourneyProfileExactOracle.Access("slow-short", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"change", "a", "change", "b", 20, 10, 0, true, true);
		var query = query(at(0), at(500), 1, 0, 10_000, () -> false);
		var result = oracle.solve(query, List.of(first, second), List.of(fastLong, slowShort));
		assertThat(result).hasSize(2).extracting(JourneyProfileExactOracle.Candidate::pathIdentity).doesNotHaveDuplicates();
		assertThat(result).extracting(JourneyProfileExactOracle.Candidate::walkingDistanceMeters)
			.containsExactlyInAnyOrder(100L, 10L);
		assertThat(oracle.solve(query, List.of(first, second), List.of(slowShort, fastLong))).isEqualTo(result);
		var conflict = new JourneyProfileExactOracle.Access("fast-long", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"change", "a", "change", "b", 20, 10, 0, true, true);
		assertThatThrownBy(() -> oracle.solve(query, List.of(first, second), List.of(fastLong, conflict)))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate access identity");
	}

	@Test
	void failsExplicitlyForWorkAndCancellationInsteadOfReturningPartialParity() {
		var rides = List.of(ride("direct", DAY, "origin", "a", "destination", "a", at(100), at(200)));

		assertThatThrownBy(() -> oracle.solve(query(at(0), at(300), 0, 0, 1, () -> false), rides, List.of()))
			.isInstanceOf(JourneyProfileExactOracle.WorkLimitExceeded.class);
		assertThatThrownBy(() -> oracle.solve(query(at(0), at(300), 0, 0, 10_000, () -> true), rides, List.of()))
			.isInstanceOf(JourneyProfileExactOracle.Cancelled.class);
		assertThatThrownBy(() -> oracle.solve(query(at(0), at(300), 0, 0, 10_000, () -> true), List.of(), List.of()))
			.isInstanceOf(JourneyProfileExactOracle.Cancelled.class);
	}

	private static JourneyProfileExactOracle.Query query(
		Instant earliest, Instant deadline, int transfers, int slack, long maxWork, java.util.function.BooleanSupplier cancelled
	) {
		return new JourneyProfileExactOracle.Query("origin", "destination", earliest, deadline, transfers, slack, maxWork, cancelled);
	}

	private static JourneyProfileExactOracle.Ride ride(
		String tripId, LocalDate date, String fromStation, String fromLine, String toStation, String toLine,
		Instant departure, Instant arrival
	) {
		return new JourneyProfileExactOracle.Ride(
			tripId, date, 0, fromStation, fromLine, toStation, toLine, departure, arrival, 0, 1, true, true);
	}

	private static JourneyProfileExactOracle.Access transfer(String station, String fromLine, String toLine, int seconds) {
		return new JourneyProfileExactOracle.Access("transfer-" + station + "-" + fromLine + "-" + toLine,
			JourneyProfileExactOracle.AccessKind.TRANSFER,
			station, fromLine, station, toLine, seconds, 1, 0, true, true);
	}

	private static Instant at(long seconds) {
		return Instant.parse("2026-07-01T00:00:00Z").plusSeconds(seconds);
	}
}
