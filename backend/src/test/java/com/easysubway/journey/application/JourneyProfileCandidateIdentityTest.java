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

	/** #454: 승강장 기준 여정 — 승차, 환승(시간·계단이 식별자에 묶이는지 확인), 승차. */
	private static JourneyProfileRaptorPort.Itinerary itinerary(
		Instant readyAt,
		Instant realtimeReadyAt,
		String tripId,
		int transferDurationSeconds,
		boolean transferIncludesStairs
	) {
		Instant firstDeparture = Instant.parse("2026-09-02T00:10:00Z");
		Instant firstArrival = Instant.parse("2026-09-02T00:20:00Z");
		Instant secondDeparture = Instant.parse("2026-09-02T00:30:00Z");
		Instant secondArrival = Instant.parse("2026-09-02T00:40:00Z");
		boolean realtime = realtimeReadyAt != null;
		return new JourneyProfileRaptorPort.Itinerary(
			LocalDate.of(2026, 9, 2),
			readyAt,
			secondArrival,
			realtimeReadyAt,
			realtime ? secondArrival.plusSeconds(30) : null,
			new JourneyProfileRaptorPort.ItineraryMetrics(
				1, transferDurationSeconds, 50, transferIncludesStairs ? 1 : 0,
				new JourneyProfileRaptorPort.MinimumTransferSeconds(300)),
			JourneyCandidate.Fare.unavailable(),
			List.of(
				TestRides.profileRide(
					"line-1", tripId, "terminal", "station-a", "station-x",
					firstDeparture, firstArrival,
					realtime ? firstDeparture.plusSeconds(30) : null,
					realtime ? firstArrival.plusSeconds(30) : null),
				new JourneyProfileRaptorPort.AccessLeg(
					JourneyProfileRaptorPort.AccessKind.TRANSFER,
					"station-x", "station-x", transferDurationSeconds, 50,
					transferIncludesStairs, true, "VERIFIED"),
				TestRides.profileRide(
					"line-2", "trip-second", "terminal", "station-x", "station-b",
					secondDeparture, secondArrival,
					realtime ? secondDeparture.plusSeconds(30) : null,
					realtime ? secondArrival.plusSeconds(30) : null)));
	}
}
