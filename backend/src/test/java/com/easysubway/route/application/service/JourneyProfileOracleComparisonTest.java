package com.easysubway.route.application.service;

import com.easysubway.journey.application.TestRides;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.journey.application.JourneyCandidate;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileOracleComparisonTest {

	@Test
	void rejectsDifferentTripsAndTransferStationsEvenWhenObjectiveTotalsMatch() {
		var departure = Instant.parse("2020-01-01T00:01:00Z");
		var day = LocalDate.of(2020, 1, 1);
		var first = new JourneyProfileExactOracle.Ride("first", day, 0, "a", "line-a", "x", "line-a",
			departure, departure.plusSeconds(60), 0, 1, true, true);
		var second = new JourneyProfileExactOracle.Ride("second", day, 1, "x", "line-b", "b", "line-b",
			departure.plusSeconds(120), departure.plusSeconds(180), 0, 1, true, true);
		var transfer = new JourneyProfileExactOracle.Access("transfer", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"x", "line-a", "x", "line-b", 30, 12, 0, true, true);
		var expected = new JourneyProfileExactOracle().solve(new JourneyProfileExactOracle.Query(
			"a", "b", departure.minusSeconds(60), departure.plusSeconds(240), 1, 0, 1_000, () -> false),
			List.of(first, second), List.of(transfer)).getFirst();
		var firstLeg = TestRides.profileRide("line-a", "first", "x", "a", "x",
			first.departureAt(), first.arrivalAt(), null, null);
		var transferLeg = new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER,
			"x", "x", 30, 12, false, true, "VERIFIED");
		var secondLeg = TestRides.profileRide("line-b", "second", "b", "x", "b",
			second.departureAt(), second.arrivalAt(), null, null);
		var wrongTrip = TestRides.profileRide("line-b", "other", "b", "x", "b",
			second.departureAt(), second.arrivalAt(), null, null);
		var wrongTransfer = new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.TRANSFER,
			"elsewhere", "elsewhere", 30, 12, false, true, "VERIFIED");
		assertThat(JourneyProfileOracleComparison.matchesObservableTimetableTrace(expected,
			transferItinerary(expected, List.of(firstLeg, transferLeg, secondLeg)))).isTrue();
		for (var legs : List.of(List.of(firstLeg, transferLeg, wrongTrip), List.of(firstLeg, wrongTransfer, secondLeg),
			List.of(transferLeg, firstLeg, secondLeg), List.of(firstLeg, secondLeg))) {
			assertThat(JourneyProfileOracleComparison.matchesObservableTimetableTrace(expected,
				transferItinerary(expected, legs))).isFalse();
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
		return new JourneyProfileExactOracle().solve(new JourneyProfileExactOracle.Query(
			"a", "b", departure.minusSeconds(60), arrival.plusSeconds(60), 0, 0, 100, () -> false),
			List.of(ride), List.of()).getFirst();
	}

	private static JourneyProfileRaptorPort.Itinerary itinerary(JourneyProfileExactOracle.Candidate candidate) {
		var ride = candidate.rides().getFirst();
		var rideLeg = TestRides.profileRide("line", ride.tripId(), "b", "a", "b",
			ride.departureAt(), ride.arrivalAt(), null, null);
		return new JourneyProfileRaptorPort.Itinerary(ride.serviceDate(), candidate.readyAt(), candidate.arrivalAtDestination(),
			null, null, new JourneyProfileRaptorPort.ItineraryMetrics(0, 0, 0, 0, new JourneyProfileRaptorPort.NoTransfer()),
			JourneyCandidate.Fare.unavailable(), List.of(rideLeg));
	}

	private static JourneyProfileRaptorPort.Itinerary transferItinerary(JourneyProfileExactOracle.Candidate candidate) {
		var legs = new java.util.ArrayList<JourneyProfileRaptorPort.Leg>();
		for (int index = 0; index < candidate.rides().size(); index += 1) {
			var ride = candidate.rides().get(index);
			legs.add(TestRides.profileRide(ride.fromLineId(), ride.tripId(), ride.toStationId(),
				ride.fromStationId(), ride.toStationId(), ride.departureAt(), ride.arrivalAt(), null, null));
			if (index == candidate.accesses().size()) continue;
			var access = candidate.accesses().get(index);
			legs.add(new JourneyProfileRaptorPort.AccessLeg(
				JourneyProfileRaptorPort.AccessKind.valueOf(access.kind().name()), access.fromStationId(), access.toStationId(),
				access.durationSeconds(), access.walkingDistanceMeters(), false, true, "VERIFIED"));
		}
		return transferItinerary(candidate, legs);
	}

	private static JourneyProfileRaptorPort.Itinerary transferItinerary(
		JourneyProfileExactOracle.Candidate candidate, List<? extends JourneyProfileRaptorPort.Leg> legs
	) {
		var slack = candidate.minimumConnectionSlack() instanceof JourneyProfileExactOracle.ConnectionSlack.NoTransfer
			? new JourneyProfileRaptorPort.NoTransfer()
			: new JourneyProfileRaptorPort.MinimumTransferSeconds(
				((JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds) candidate.minimumConnectionSlack()).seconds());
		return new JourneyProfileRaptorPort.Itinerary(candidate.rides().getFirst().serviceDate(), candidate.readyAt(),
			candidate.arrivalAtDestination(), null, null, new JourneyProfileRaptorPort.ItineraryMetrics(
				candidate.transfersUsed(), candidate.walkingSeconds(), candidate.walkingDistanceMeters(),
				candidate.accessibilityBurden(), slack), JourneyCandidate.Fare.unavailable(), List.copyOf(legs));
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
		var transfer = new JourneyProfileExactOracle.Access(tripPrefix + "-transfer", JourneyProfileExactOracle.AccessKind.TRANSFER,
			"x", "line-a", "x", "line-b", 20, 7, 0, true, true);
		return new JourneyProfileExactOracle.Candidate(firstDeparture, second.arrivalAt(),
			1, 20, 7, 0, new JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds(
				secondDepartureOffset - 80L), tripPrefix, List.of(first, second), List.of(transfer));
	}

	private static JourneyProfileRaptorPort.Itinerary wrongTrip(JourneyProfileExactOracle.Candidate candidate) {
		var actual = itinerary(candidate);
		var legs = new java.util.ArrayList<>(actual.legs());
		var ride = (JourneyProfileRaptorPort.RideLeg) legs.get(0);
		legs.set(0, TestRides.profileRide(ride.lineId(), "wrong-" + ride.tripId(), ride.directionStationId(),
			ride.fromStationId(), ride.toStationId(), ride.plannedDepartureTime(), ride.plannedArrivalTime(), null, null));
		return new JourneyProfileRaptorPort.Itinerary(actual.serviceDate(), actual.plannedReadyAt(), actual.plannedArrivalAtDestination(),
			null, null, actual.metrics(), actual.fare(), List.copyOf(legs));
	}

}
