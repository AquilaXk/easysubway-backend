package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileOracleComparisonTest {

	@Test
	void rejectsDifferentTripsAndAccessStationsEvenWhenObjectiveTotalsMatch() {
		var departure = Instant.parse("2020-01-01T00:01:00Z");
		var arrival = departure.plusSeconds(60);
		var day = LocalDate.of(2020, 1, 1);
		var ride = new JourneyProfileExactOracle.Ride("trip", day, 0, "a", "line", "b", "line",
			departure, arrival, 0, 1, true, true);
		var entry = new JourneyProfileExactOracle.Access("entry", JourneyProfileExactOracle.AccessKind.ENTRY,
			"a", null, "a", "line", 10, 5, 0, true, true);
		var exit = new JourneyProfileExactOracle.Access("exit", JourneyProfileExactOracle.AccessKind.EXIT,
			"b", "line", "b", null, 20, 7, 0, true, true);
		var expected = new JourneyProfileExactOracle().solve(new JourneyProfileExactOracle.Query(
			"a", "b", departure.minusSeconds(60), arrival.plusSeconds(60), 0, 0, 100, () -> false),
			List.of(ride), List.of(entry, exit)).getFirst();
		var entryLeg = new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY,
			"a", "a", 10, 5, false, true, "VERIFIED");
		var exitLeg = new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.EXIT,
			"b", "b", 20, 7, false, true, "VERIFIED");
		var rideLeg = new JourneyProfileRaptorPort.RideLeg("line", "trip", "b", "a", "b",
			departure, arrival, null, null);
		var wrongTrip = new JourneyProfileRaptorPort.RideLeg("line", "other", "b", "a", "b",
			departure, arrival, null, null);
		var wrongExit = new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.EXIT,
			"elsewhere", "elsewhere", 20, 7, false, true, "VERIFIED");
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableTrace(expected,
			itinerary(day, departure, arrival, List.of(entryLeg, rideLeg, exitLeg)))).isTrue();
		for (var legs : List.of(List.of(entryLeg, wrongTrip, exitLeg), List.of(entryLeg, rideLeg, wrongExit),
			List.of(exitLeg, rideLeg, entryLeg))) {
			assertThat(JourneyProfileOracleComparison.matchesObservableTimetableTrace(expected,
				itinerary(day, departure, arrival, legs))).isFalse();
		}
	}

	@Test
	void matchesObservableFrontiersAsCompleteOrderIndependentMultisets() {
		var first = candidate("first", Instant.parse("2020-01-01T00:01:00Z"), 60);
		var second = candidate("second", Instant.parse("2020-01-01T00:03:00Z"), 90);
		var firstActual = itinerary(first);
		var secondActual = itinerary(second);

		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(
			List.of(first, second), List.of(secondActual, firstActual))).isTrue();
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(
			List.of(first), List.of(firstActual, secondActual))).isFalse();
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(
			List.of(first, second), List.of(firstActual))).isFalse();
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(
			List.of(first, second), List.of(firstActual, firstActual))).isFalse();
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(
			List.of(first, second), List.of(wrongTrip(first), wrongTrip(second)))).isFalse();
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableFrontier(List.of(), List.of())).isTrue();
	}

	@Test
	void countsNoRequiredObjectiveLossWhenEveryExactRepresentativeIsObservable() {
		var earliestArrival = candidate("earliest", Instant.parse("2020-01-01T00:01:00Z"), 60);
		var latestReady = candidate("latest", Instant.parse("2020-01-01T00:03:00Z"), 90);

		assertThat(JourneyProfileOracleComparison.requiredObjectiveLoss(
			List.of(earliestArrival, latestReady), List.of(itinerary(latestReady), itinerary(earliestArrival))))
			.isZero();
	}

	@Test
	void countsOnlyTheMissingUniqueEarliestArrivalOrLatestReadyRepresentative() {
		var earliestArrival = candidate("earliest", Instant.parse("2020-01-01T00:01:00Z"), 60);
		var latestReady = candidate("latest", Instant.parse("2020-01-01T00:03:00Z"), 90);

		assertThat(JourneyProfileOracleComparison.requiredObjectiveLoss(
			List.of(earliestArrival, latestReady), List.of(itinerary(latestReady)))).isEqualTo(1);
		assertThat(JourneyProfileOracleComparison.requiredObjectiveLoss(
			List.of(earliestArrival, latestReady), List.of(itinerary(earliestArrival)))).isEqualTo(1);
	}

	@Test
	void countsEveryObjectiveLostWhenMetricsMatchButTheRideTraceDoesNot() {
		var expected = candidate("expected", Instant.parse("2020-01-01T00:01:00Z"), 60);

		assertThat(JourneyProfileOracleComparison.requiredObjectiveLoss(
			List.of(expected), List.of(wrongTrip(expected)))).isEqualTo(6);
	}

	@Test
	void rejectsAnEmptyExpectedFrontierBecauseTypedFailureParityIsSeparate() {
		assertThatThrownBy(() -> JourneyProfileOracleComparison.requiredObjectiveLoss(List.of(), List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("expected");
	}

	@Test
	void treatsTheGreatestNumericTransferSlackAsTheRequiredSlackRepresentative() {
		Instant departure = Instant.parse("2020-01-01T00:01:00Z");
		var lowSlack = transferCandidate("low-slack", departure, 100);
		var highSlack = transferCandidate("high-slack", departure, 200);

		assertThat(JourneyProfileOracleComparison.requiredObjectiveLoss(
			List.of(lowSlack, highSlack), List.of(transferItinerary(lowSlack)))).isEqualTo(1);
	}

	private static JourneyProfileExactOracle.Candidate candidate(String tripId, Instant departure, int rideSeconds) {
		var arrival = departure.plusSeconds(rideSeconds);
		var day = LocalDate.of(2020, 1, 1);
		var ride = new JourneyProfileExactOracle.Ride(tripId, day, 0, "a", "line", "b", "line",
			departure, arrival, 0, 1, true, true);
		var entry = new JourneyProfileExactOracle.Access("entry-" + tripId, JourneyProfileExactOracle.AccessKind.ENTRY,
			"a", null, "a", "line", 10, 5, 0, true, true);
		var exit = new JourneyProfileExactOracle.Access("exit-" + tripId, JourneyProfileExactOracle.AccessKind.EXIT,
			"b", "line", "b", null, 20, 7, 0, true, true);
		return new JourneyProfileExactOracle().solve(new JourneyProfileExactOracle.Query(
			"a", "b", departure.minusSeconds(60), arrival.plusSeconds(60), 0, 0, 100, () -> false),
			List.of(ride), List.of(entry, exit)).getFirst();
	}

	private static JourneyProfileRaptorPort.Itinerary itinerary(JourneyProfileExactOracle.Candidate candidate) {
		var ride = candidate.rides().getFirst();
		var entry = new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY,
			"a", "a", 10, 5, false, true, "VERIFIED");
		var exit = new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.EXIT,
			"b", "b", 20, 7, false, true, "VERIFIED");
		var rideLeg = new JourneyProfileRaptorPort.RideLeg("line", ride.tripId(), "b", "a", "b",
			ride.departureAt(), ride.arrivalAt(), null, null);
		return itinerary(ride.serviceDate(), ride.departureAt(), ride.arrivalAt(), List.of(entry, rideLeg, exit));
	}

	private static JourneyProfileRaptorPort.Itinerary transferItinerary(JourneyProfileExactOracle.Candidate candidate) {
		var legs = new java.util.ArrayList<JourneyProfileRaptorPort.Leg>();
		for (int index = 0; index < candidate.rides().size(); index += 1) {
			var access = candidate.accesses().get(index);
			legs.add(new JourneyProfileRaptorPort.AccessLeg(
				JourneyProfileRaptorPort.AccessKind.valueOf(access.kind().name()), access.fromStationId(), access.toStationId(),
				access.durationSeconds(), access.walkingDistanceMeters(), false, true, "VERIFIED"));
			var ride = candidate.rides().get(index);
			legs.add(new JourneyProfileRaptorPort.RideLeg(ride.fromLineId(), ride.tripId(), ride.toStationId(),
				ride.fromStationId(), ride.toStationId(), ride.departureAt(), ride.arrivalAt(), null, null));
		}
		var exit = candidate.accesses().getLast();
		legs.add(new JourneyProfileRaptorPort.AccessLeg(
			JourneyProfileRaptorPort.AccessKind.valueOf(exit.kind().name()), exit.fromStationId(), exit.toStationId(),
			exit.durationSeconds(), exit.walkingDistanceMeters(), false, true, "VERIFIED"));
		var slack = candidate.minimumConnectionSlack() instanceof JourneyProfileExactOracle.ConnectionSlack.NoTransfer
			? new JourneyProfileRaptorPort.NoTransfer()
			: new JourneyProfileRaptorPort.MinimumTransferSeconds(
				((JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds) candidate.minimumConnectionSlack()).seconds());
		return new JourneyProfileRaptorPort.Itinerary(candidate.rides().getFirst().serviceDate(), candidate.readyAt(),
			candidate.arrivalAtDestination(), null, null, new JourneyProfileRaptorPort.ItineraryMetrics(
				candidate.transfersUsed(), candidate.walkingSeconds(), candidate.walkingDistanceMeters(),
				candidate.accessibilityBurden(), slack), List.copyOf(legs));
	}

	private static JourneyProfileExactOracle.Candidate transferCandidate(
		String tripPrefix, Instant firstDeparture, int secondDepartureOffset
	) {
		var day = LocalDate.of(2020, 1, 1);
		var first = new JourneyProfileExactOracle.Ride(tripPrefix + "-first", day, 0, "a", "line-a", "x", "line-a",
			firstDeparture, firstDeparture.plusSeconds(60), 0, 1, true, true);
		var secondDeparture = firstDeparture.plusSeconds(secondDepartureOffset);
		var second = new JourneyProfileExactOracle.Ride(tripPrefix + "-second", day, 1, "x", "line-b", "b", "line-b",
			secondDeparture, secondDeparture.plusSeconds(60), 0, 1, true, true);
		var entry = new JourneyProfileExactOracle.Access(tripPrefix + "-entry", JourneyProfileExactOracle.AccessKind.ENTRY,
			"a", null, "a", "line-a", 10, 5, 0, true, true);
		var transfer = new JourneyProfileExactOracle.Access(tripPrefix + "-transfer", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"x", "line-a", "x", "line-b", 20, 7, 0, true, true);
		var exit = new JourneyProfileExactOracle.Access(tripPrefix + "-exit", JourneyProfileExactOracle.AccessKind.EXIT,
			"b", "line-b", "b", null, 20, 7, 0, true, true);
		return new JourneyProfileExactOracle.Candidate(firstDeparture.minusSeconds(10), second.arrivalAt().plusSeconds(20),
			1, 50, 19, 0, new JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds(
				secondDepartureOffset - 80L), tripPrefix, List.of(first, second), List.of(entry, transfer, exit));
	}

	private static JourneyProfileRaptorPort.Itinerary wrongTrip(JourneyProfileExactOracle.Candidate candidate) {
		var actual = itinerary(candidate);
		var legs = new java.util.ArrayList<>(actual.legs());
		var ride = (JourneyProfileRaptorPort.RideLeg) legs.get(1);
		legs.set(1, new JourneyProfileRaptorPort.RideLeg(ride.lineId(), "wrong-" + ride.tripId(), ride.directionStationId(),
			ride.fromStationId(), ride.toStationId(), ride.plannedDepartureTime(), ride.plannedArrivalTime(), null, null));
		return new JourneyProfileRaptorPort.Itinerary(actual.serviceDate(), actual.plannedReadyAt(), actual.plannedArrivalAtDestination(),
			null, null, actual.metrics(), List.copyOf(legs));
	}

	private static JourneyProfileRaptorPort.Itinerary itinerary(
		LocalDate day, Instant departure, Instant arrival, List<? extends JourneyProfileRaptorPort.Leg> legs
	) {
		return new JourneyProfileRaptorPort.Itinerary(day, departure.minusSeconds(10), arrival.plusSeconds(20),
			null, null, new JourneyProfileRaptorPort.ItineraryMetrics(0, 30, 12, 0,
				new JourneyProfileRaptorPort.NoTransfer()), List.copyOf(legs));
	}
}
