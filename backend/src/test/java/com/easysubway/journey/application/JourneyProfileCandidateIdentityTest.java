package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyProfileCandidateIdentityTest {

	private static final Instant READY_AT = Instant.parse("2026-09-02T00:00:00Z");

	@Test
	void separatesPhysicalItineraryFromReadinessQualifiedCandidate() {
		var first = itinerary(READY_AT, null, "trip-1", 120, false);
		var laterReady = itinerary(READY_AT.plusSeconds(60), null, "trip-1", 120, false);
		var realtimeOnly = itinerary(READY_AT, READY_AT.plusSeconds(30), "trip-1", 120, false);

		String physicalId = JourneyProfileCandidateIdentity.physicalItineraryId(
			"station-a", "station-b", first);

		assertThat(physicalId).matches("[a-f0-9]{64}");
		assertThat(JourneyProfileCandidateIdentity.physicalItineraryId(
			"station-a", "station-b", laterReady)).isEqualTo(physicalId);
		assertThat(JourneyProfileCandidateIdentity.physicalItineraryId(
			"station-a", "station-b", realtimeOnly)).isEqualTo(physicalId);
		assertThat(JourneyProfileCandidateIdentity.candidateId(physicalId, READY_AT))
			.matches("[a-f0-9]{64}")
			.isNotEqualTo(JourneyProfileCandidateIdentity.candidateId(
				physicalId, READY_AT.plusSeconds(60)));
	}

	@Test
	void bindsEveryPlannedAccessAndRideSemanticUsedByThePhysicalItinerary() {
		var original = itinerary(READY_AT, null, "trip-1", 120, false);
		String originalId = JourneyProfileCandidateIdentity.physicalItineraryId(
			"station-a", "station-b", original);

		assertThat(JourneyProfileCandidateIdentity.physicalItineraryId(
			"station-a", "station-b", itinerary(READY_AT, null, "trip-2", 120, false)))
			.isNotEqualTo(originalId);
		assertThat(JourneyProfileCandidateIdentity.physicalItineraryId(
			"station-a", "station-b", itinerary(READY_AT, null, "trip-1", 121, false)))
			.isNotEqualTo(originalId);
		assertThat(JourneyProfileCandidateIdentity.physicalItineraryId(
			"station-a", "station-b", itinerary(READY_AT, null, "trip-1", 120, true)))
			.isNotEqualTo(originalId);
	}

	private static JourneyProfileRaptorPort.Itinerary itinerary(
		Instant readyAt,
		Instant realtimeReadyAt,
		String tripId,
		int entryDurationSeconds,
		boolean entryIncludesStairs
	) {
		Instant rideDeparture = Instant.parse("2026-09-02T00:10:00Z");
		Instant rideArrival = Instant.parse("2026-09-02T00:20:00Z");
		Instant realtimeArrival = realtimeReadyAt == null ? null : rideArrival.plusSeconds(30);
		return new JourneyProfileRaptorPort.Itinerary(
			LocalDate.of(2026, 9, 2),
			readyAt,
			rideArrival.plusSeconds(180),
			realtimeReadyAt,
			realtimeArrival == null ? null : realtimeArrival.plusSeconds(180),
			new JourneyProfileRaptorPort.ItineraryMetrics(
				0, entryDurationSeconds + 180L, 125, entryIncludesStairs ? 1 : 0,
				new JourneyProfileRaptorPort.NoTransfer()),
			List.of(
				new JourneyProfileRaptorPort.AccessLeg(
					JourneyProfileRaptorPort.AccessKind.ENTRY,
					"station-a", "station-a", entryDurationSeconds, 50,
					entryIncludesStairs, true, "VERIFIED"),
				new JourneyProfileRaptorPort.RideLeg(
					"line-1", tripId, "terminal", "station-a", "station-b",
					rideDeparture, rideArrival,
					realtimeReadyAt == null ? null : rideDeparture.plusSeconds(30),
					realtimeArrival),
				new JourneyProfileRaptorPort.AccessLeg(
					JourneyProfileRaptorPort.AccessKind.EXIT,
					"station-b", "station-b", 180, 75, false, true, "VERIFIED")));
	}
}
