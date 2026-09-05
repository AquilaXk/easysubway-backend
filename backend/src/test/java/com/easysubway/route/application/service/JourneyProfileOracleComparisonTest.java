package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

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

	private static JourneyProfileRaptorPort.Itinerary itinerary(
		LocalDate day, Instant departure, Instant arrival, List<? extends JourneyProfileRaptorPort.Leg> legs
	) {
		return new JourneyProfileRaptorPort.Itinerary(day, departure.minusSeconds(10), arrival.plusSeconds(20),
			null, null, new JourneyProfileRaptorPort.ItineraryMetrics(0, 30, 12, 0,
				new JourneyProfileRaptorPort.NoTransfer()), List.copyOf(legs));
	}
}
