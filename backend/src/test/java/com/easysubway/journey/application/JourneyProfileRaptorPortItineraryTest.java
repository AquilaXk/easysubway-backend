package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileRaptorPortItineraryTest {

	private static final Instant START = Instant.parse("2026-09-02T00:00:00Z");

	@Test
	void rejectsUnorderedOrUnpairedItineraryTimes() {
		var ride = TestRides.profileRide("line", "trip", "terminal", "origin", "destination",
			START.plusSeconds(60), START.plusSeconds(600), null, null);
		var metrics = new JourneyProfileRaptorPort.ItineraryMetrics(0, 0, 0, 0, new JourneyProfileRaptorPort.NoTransfer());

		assertThatThrownBy(() -> new JourneyProfileRaptorPort.Itinerary(LocalDate.of(2026, 9, 2), START.plusSeconds(600),
			START, null, null, metrics, JourneyCandidate.Fare.unavailable(), List.of(ride)))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("planned itinerary times must be ordered");
		assertThatThrownBy(() -> new JourneyProfileRaptorPort.Itinerary(LocalDate.of(2026, 9, 2), START,
			START.plusSeconds(600), START, null, metrics, JourneyCandidate.Fare.unavailable(), List.of(ride)))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("realtime itinerary times must be a pair");
		assertThatThrownBy(() -> new JourneyProfileRaptorPort.Itinerary(LocalDate.of(2026, 9, 2), START,
			START.plusSeconds(600), START.plusSeconds(600), START, metrics, JourneyCandidate.Fare.unavailable(), List.of(ride)))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("realtime itinerary times must be ordered");
	}
}
