package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileExactOracleTest {

	private static final LocalDate DAY = LocalDate.of(2026, 7, 1);
	private final JourneyProfileExactOracle oracle = new JourneyProfileExactOracle();

	@Test
	void retainsTheCompleteImmutableRideAndAccessTraceForDifferentialComparison() {
		var first = ride("first", DAY, "origin", "a", "change", "a", at(100), at(200));
		var second = ride("second", DAY, "change", "b", "destination", "b", at(300), at(400));
		var entry = entry("a", 10);
		var transfer = transfer("change", "a", "b", 20);
		var exit = exit("b", 30);
		var result = oracle.solve(query(at(0), at(500), 1, 0, 10_000, () -> false),
			List.of(first, second), List.of(entry, transfer, exit));
		assertThat(result).hasSize(1);
		assertThat(result.getFirst().rides()).containsExactly(first, second);
		assertThat(result.getFirst().accesses()).containsExactly(entry, transfer, exit);
		assertThatThrownBy(result.getFirst().rides()::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(result.getFirst().accesses()::clear).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void retainsMoreThanThreeNonDominatedRoutesWithoutAPublicResultCap() {
		var rides = List.of(
			ride("one", DAY, "origin", "a", "destination", "a", at(100), at(500)),
			ride("two", DAY, "origin", "b", "destination", "b", at(200), at(700)),
			ride("three", DAY, "origin", "c", "destination", "c", at(300), at(900)),
			ride("four", DAY, "origin", "d", "destination", "d", at(400), at(1_100)));
		var accesses = List.of(entry("a", 20), exit("a", 20), entry("b", 30), exit("b", 30),
			entry("c", 40), exit("c", 40), entry("d", 50), exit("d", 50));

		var result = oracle.solve(query(at(0), at(2_000), 0, 0, 10_000, () -> false), rides, accesses);

		assertThat(result).hasSize(4).extracting(JourneyProfileExactOracle.Candidate::pathIdentity)
			.doesNotHaveDuplicates();
		assertThat(result).extracting(JourneyProfileExactOracle.Candidate::readyAt)
			.containsExactlyInAnyOrder(at(80), at(170), at(260), at(350));
	}

	@Test
	void rejectsAnAsymmetricOrMissingDirectionalTransfer() {
		var rides = List.of(
			ride("first", DAY, "origin", "a", "change", "a", at(100), at(200)),
			ride("second", DAY, "change", "b", "destination", "b", at(300), at(400)));
		var accesses = List.of(entry("a", 0), exit("b", 0), transfer("change", "b", "a", 10));

		assertThat(oracle.solve(query(at(0), at(500), 1, 0, 10_000, () -> false), rides, accesses)).isEmpty();
	}

	@Test
	void neverBoardsOrExitsAtAnotherStationOnTheSameLine() {
		var accesses = List.of(entry("a", 0), exit("a", 0));
		for (var ride : List.of(
			ride("wrong-origin", DAY, "elsewhere", "a", "destination", "a", at(100), at(200)),
			ride("wrong-destination", DAY, "origin", "a", "elsewhere", "a", at(100), at(200)))) {
			assertThat(oracle.solve(query(at(0), at(300), 0, 0, 10_000, () -> false),
				List.of(ride), accesses)).isEmpty();
		}
	}

	@Test
	void excludesAChainWhoseVerifiedExitCompletesAfterTheDeadline() {
		var rides = List.of(ride("direct", DAY, "origin", "a", "destination", "a", at(100), at(500)));

		assertThat(oracle.solve(query(at(0), at(550), 0, 0, 10_000, () -> false), rides,
			List.of(entry("a", 0), exit("a", 60)))).isEmpty();
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
			1, 60, 10_000, () -> false), rides, List.of(entry("a", 60), transfer("change", "a", "b", 300), exit("b", 60)));

		assertThat(result).singleElement().satisfies(candidate -> {
			assertThat(candidate.pathIdentity()).contains("2026-07-01", "late", "2026-07-02", "early");
			assertThat(candidate.readyAt()).isEqualTo(Instant.parse("2026-07-01T17:58:00Z"));
			assertThat(candidate.arrivalAtDestination()).isEqualTo(Instant.parse("2026-07-01T18:27:00Z"));
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
			List.of(entry("a", 0), transfer("change", "a", "b", 30), exit("b", 0)));

		assertThat(result).singleElement().extracting(JourneyProfileExactOracle.Candidate::minimumConnectionSlack)
			.isEqualTo(new JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds(0));
	}

	@Test
	void rejectsDuplicateDatedTripAndPositionIdentityInsteadOfSilentlyCollapsingIt() {
		var duplicate = ride("same", DAY, "origin", "a", "destination", "a", at(100), at(200));

		assertThatThrownBy(() -> oracle.solve(query(at(0), at(300), 0, 0, 10_000, () -> false),
			List.of(duplicate, duplicate), List.of(entry("a", 0), exit("a", 0))))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate ride identity");
	}

	@Test
	void keepsFrequencyInstancesDistinctWithinTheSameTripAndServiceDate() {
		var first = ride("frequency", DAY, "origin", "a", "destination", "a", at(100), at(200));
		var next = new JourneyProfileExactOracle.Ride("frequency", DAY, 1,
			"origin", "a", "destination", "a", at(300), at(400), 0, 1, true, true);
		assertThat(oracle.solve(query(at(0), at(500), 0, 0, 10_000, () -> false),
			List.of(first, next), List.of(entry("a", 0), exit("a", 0))))
			.hasSize(2).extracting(JourneyProfileExactOracle.Candidate::pathIdentity).doesNotHaveDuplicates();
	}

	@Test
	void acceptsEqualTimeEventsWithoutInventingAMinimumRideDuration() {
		var zeroDuration = ride("equal-time", DAY, "origin", "a", "destination", "a", at(100), at(100));
		assertThat(oracle.solve(query(at(0), at(200), 0, 0, 10_000, () -> false),
			List.of(zeroDuration), List.of(entry("a", 0), exit("a", 0)))).singleElement()
			.extracting(JourneyProfileExactOracle.Candidate::arrivalAtDestination).isEqualTo(at(100));
	}

	@Test
	void acceptsNullOnlyOnTheOffNetworkEndsOfEntryAndExit() {
		var direct = ride("off-network", DAY, "origin", "a", "destination", "a", at(100), at(200));
		var entry = new JourneyProfileExactOracle.Access("entry-null-from", JourneyProfileExactOracle.AccessKind.ENTRY,
			"origin", null, "origin", "a", 0, 1, 0, true, true);
		var exit = new JourneyProfileExactOracle.Access("exit-null-to", JourneyProfileExactOracle.AccessKind.EXIT,
			"destination", "a", "destination", null, 0, 1, 0, true, true);

		assertThat(oracle.solve(query(at(0), at(300), 0, 0, 10_000, () -> false), List.of(direct), List.of(entry, exit)))
			.singleElement().satisfies(candidate -> assertThat(candidate.arrivalAtDestination()).isEqualTo(at(200)));
		assertThatThrownBy(() -> new JourneyProfileExactOracle.Access("entry-null-to", JourneyProfileExactOracle.AccessKind.ENTRY,
			"origin", null, "origin", null, 0, 1, 0, true, true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyProfileExactOracle.Access("exit-null-from", JourneyProfileExactOracle.AccessKind.EXIT,
			"destination", null, "destination", null, 0, 1, 0, true, true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyProfileExactOracle.Access("transfer-null-from", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"change", null, "change", "b", 0, 1, 0, true, true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyProfileExactOracle.Access("transfer-null-to", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"change", "a", "change", null, 0, 1, 0, true, true)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyProfileExactOracle.Access("entry-blank-from", JourneyProfileExactOracle.AccessKind.ENTRY,
			"origin", "", "origin", "a", 0, 1, 0, true, true)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void preservesDifferentAccessPathsForTheSameRideAndRejectsConflictingAccessIdentity() {
		var direct = ride("direct", DAY, "origin", "a", "destination", "a", at(100), at(200));
		var fastLong = new JourneyProfileExactOracle.Access("fast-long", JourneyProfileExactOracle.AccessKind.ENTRY,
			"origin", "outside", "origin", "a", 10, 100, 0, true, true);
		var slowShort = new JourneyProfileExactOracle.Access("slow-short", JourneyProfileExactOracle.AccessKind.ENTRY,
			"origin", "outside", "origin", "a", 20, 10, 0, true, true);
		var query = query(at(0), at(300), 0, 0, 10_000, () -> false);
		var result = oracle.solve(query, List.of(direct), List.of(fastLong, slowShort, exit("a", 0)));
		assertThat(result).hasSize(2).extracting(JourneyProfileExactOracle.Candidate::pathIdentity).doesNotHaveDuplicates();
		assertThat(result).extracting(JourneyProfileExactOracle.Candidate::walkingDistanceMeters)
			.containsExactlyInAnyOrder(101L, 11L);
		assertThat(oracle.solve(query, List.of(direct), List.of(exit("a", 0), slowShort, fastLong))).isEqualTo(result);
		var conflict = new JourneyProfileExactOracle.Access("fast-long", JourneyProfileExactOracle.AccessKind.ENTRY,
			"origin", "outside", "origin", "a", 20, 10, 0, true, true);
		assertThatThrownBy(() -> oracle.solve(query, List.of(direct), List.of(fastLong, conflict)))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate access identity");
	}

	@Test
	void failsExplicitlyForWorkAndCancellationInsteadOfReturningPartialParity() {
		var rides = List.of(ride("direct", DAY, "origin", "a", "destination", "a", at(100), at(200)));
		var accesses = List.of(entry("a", 0), exit("a", 0));

		assertThatThrownBy(() -> oracle.solve(query(at(0), at(300), 0, 0, 1, () -> false), rides, accesses))
			.isInstanceOf(JourneyProfileExactOracle.WorkLimitExceeded.class);
		assertThatThrownBy(() -> oracle.solve(query(at(0), at(300), 0, 0, 10_000, () -> true), rides, accesses))
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

	private static JourneyProfileExactOracle.Access entry(String line, int seconds) {
		return new JourneyProfileExactOracle.Access("entry-" + line + "-" + seconds, JourneyProfileExactOracle.AccessKind.ENTRY,
			"origin", "outside", "origin", line, seconds, 1, 0, true, true);
	}

	private static JourneyProfileExactOracle.Access exit(String line, int seconds) {
		return new JourneyProfileExactOracle.Access("exit-" + line + "-" + seconds, JourneyProfileExactOracle.AccessKind.EXIT,
			"destination", line, "destination", "outside", seconds, 1, 0, true, true);
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
