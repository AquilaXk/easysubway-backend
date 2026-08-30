package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyCandidateTest {

	private static final Instant DEPARTURE = Instant.parse("2026-08-11T00:01:00Z");
	private static final Instant ARRIVAL = Instant.parse("2026-08-11T00:06:00Z");

	@Test
	void preservesClosedTimetableCandidateAccessibilityAndLegOrder() {
		List<String> reasons = new ArrayList<>(List.of("STEP_FREE_PATH"));
		List<JourneyCandidate.Leg> legs = new ArrayList<>(List.of(
			new JourneyCandidate.Entry("station-origin", 30),
			new JourneyCandidate.Ride(
				"line-1", "trip-1", "station-direction", "station-origin", "station-destination",
				DEPARTURE, ARRIVAL, null, null
			),
			new JourneyCandidate.Transfer("station-a", "station-b", 45),
			new JourneyCandidate.Exit("station-destination", 20)
		));

		JourneyCandidate candidate = new JourneyCandidate(
			"journey-1", DEPARTURE, ARRIVAL, null, null, 300, 1, 75,
			JourneyCandidate.TimeSource.TIMETABLE,
			new JourneyCandidate.Accessibility(true, reasons),
			legs
		);
		reasons.clear();
		legs.clear();

		assertThat(candidate.status()).isEqualTo(JourneyCandidate.Status.FOUND);
		assertThat(candidate.planSource()).isEqualTo(JourneyCandidate.PlanSource.SERVER_TIMETABLE_RAPTOR);
		assertThat(candidate.accessibility().result()).isEqualTo(JourneyCandidate.AccessibilityResult.VERIFIED);
		assertThat(candidate.accessibility().reasonCodes()).containsExactly("STEP_FREE_PATH");
		assertThat(candidate.legs()).extracting(JourneyCandidate.Leg::type)
			.containsExactly(
				JourneyCandidate.LegType.ENTRY,
				JourneyCandidate.LegType.RIDE,
				JourneyCandidate.LegType.TRANSFER,
				JourneyCandidate.LegType.EXIT
			);
		assertThatThrownBy(() -> candidate.legs().add(new JourneyCandidate.Exit("station-x", 1)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void acceptsOnlyCompleteRealtimePairsForRealtimeCandidatesAndRideLegs() {
		Instant realtimeDeparture = DEPARTURE.plusSeconds(30);
		Instant realtimeArrival = ARRIVAL.plusSeconds(30);
		JourneyCandidate candidate = new JourneyCandidate(
			"journey-1", DEPARTURE, ARRIVAL, realtimeDeparture, realtimeArrival, 300, 0, 50,
			JourneyCandidate.TimeSource.REALTIME,
			new JourneyCandidate.Accessibility(true, List.of()),
			List.of(new JourneyCandidate.Ride(
				"line-1", "trip-1", "station-direction", "station-origin", "station-destination",
				DEPARTURE, ARRIVAL, realtimeDeparture, realtimeArrival
			))
		);

		assertThat(candidate.hasRealtime()).isTrue();
		assertThat(candidate.realtimeDepartureTime()).isEqualTo(realtimeDeparture);
		assertThat(candidate.realtimeArrivalTime()).isEqualTo(realtimeArrival);
	}

	@Test
	void rejectsInvalidIdentityTimesMetricsModeAndCollections() {
		assertThatThrownBy(() -> candidate(" ", JourneyCandidate.TimeSource.TIMETABLE, null, null, 300, 0, 50))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyCandidate(
			"journey-1", ARRIVAL, DEPARTURE, null, null, 300, 0, 50,
			JourneyCandidate.TimeSource.TIMETABLE, accessibility(), legs(null, null)
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> candidate(
			"journey-1", JourneyCandidate.TimeSource.TIMETABLE, DEPARTURE, null, 300, 0, 50
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> candidate(
			"journey-1", JourneyCandidate.TimeSource.REALTIME, null, null, 300, 0, 50
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> candidate(
			"journey-1", JourneyCandidate.TimeSource.TIMETABLE, null, null, -1, 0, 50
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> candidate(
			"journey-1", JourneyCandidate.TimeSource.TIMETABLE, null, null, 300, 4, 50
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> candidate(
			"journey-1", JourneyCandidate.TimeSource.TIMETABLE, null, null, 300, 0, -1
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyCandidate(
			"journey-1", DEPARTURE, ARRIVAL, null, null, 300, 0, 50,
			JourneyCandidate.TimeSource.TIMETABLE, accessibility(), List.of()
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsInvalidAccessibilityAndLegFields() {
		assertThatThrownBy(() -> new JourneyCandidate.Accessibility(true, List.of("A", "A")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyCandidate.Accessibility(true, List.of(" ")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyCandidate.Entry(" ", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyCandidate.Transfer("station-a", "station-b", -1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyCandidate.Ride(
			"line-1", "trip-1", "station-direction", "station-origin", "station-destination",
			ARRIVAL, DEPARTURE, null, null
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyCandidate.Ride(
			"line-1", "trip-1", "station-direction", "station-origin", "station-destination",
			DEPARTURE, ARRIVAL, DEPARTURE, null
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsCandidateAndRideRealtimeModeDrift() {
		Instant realtimeDeparture = DEPARTURE.plusSeconds(30);
		Instant realtimeArrival = ARRIVAL.plusSeconds(30);
		assertThatThrownBy(() -> new JourneyCandidate(
			"journey-1", DEPARTURE, ARRIVAL, null, null, 300, 0, 50,
			JourneyCandidate.TimeSource.TIMETABLE,
			accessibility(),
			legs(realtimeDeparture, realtimeArrival)
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyCandidate(
			"journey-1", DEPARTURE, ARRIVAL, realtimeDeparture, realtimeArrival, 300, 0, 50,
			JourneyCandidate.TimeSource.REALTIME,
			accessibility(),
			legs(null, null)
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsInvalidPortIdentityAndPreservesPlanResultBytes() {
		assertThatThrownBy(() -> new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", "BAD", "timetable-1", "accessibility-1", 1,
			new TestRuntimeView("BAD", 1), ARRIVAL, true,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0)
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot-1", "bundle-1", "a".repeat(64), "timetable-1", "accessibility-1", 0,
			new TestRuntimeView("a".repeat(64), 0), ARRIVAL, true,
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0)
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyRealtimePort.RealtimeObservation(
			"realtime-1", "BAD", new TestRealtimeView("realtime-1", "BAD", 1), ARRIVAL, true
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyRaptorPort.PlanResult(" ", List.of(),
			new JourneyRaptorPort.ScanMetrics(1, 2, 3), JourneyRaptorPort.RouteBoundaryReceipt.observed(0)))
			.isInstanceOf(IllegalArgumentException.class);

		List<JourneyCandidate> candidates = new ArrayList<>(List.of(candidate(
			"journey-1", JourneyCandidate.TimeSource.TIMETABLE, null, null, 300, 0, 50
		)));
		JourneyRaptorPort.PlanResult result = new JourneyRaptorPort.PlanResult("query-1", candidates,
			new JourneyRaptorPort.ScanMetrics(1, 2, 3), JourneyRaptorPort.RouteBoundaryReceipt.observed(0));
		candidates.clear();
		assertThat(result.candidates()).extracting(JourneyCandidate::journeyId).containsExactly("journey-1");
		assertThatThrownBy(() -> result.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);

		assertThatThrownBy(() -> new ActiveJourneySnapshotPort.SnapshotBoundaryReceipt(
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.Status.UNOBSERVABLE, 0L, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ActiveJourneySnapshotPort.SnapshotBoundaryReceipt(
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.Status.OBSERVED, null, 0L))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyRaptorPort.RouteBoundaryReceipt(
			JourneyRaptorPort.RouteBoundaryReceipt.Status.UNOBSERVABLE, 0L))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JourneyRaptorPort.RouteBoundaryReceipt(
			JourneyRaptorPort.RouteBoundaryReceipt.Status.OBSERVED, -1L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static JourneyCandidate candidate(
		String id,
		JourneyCandidate.TimeSource source,
		Instant realtimeDeparture,
		Instant realtimeArrival,
		long duration,
		int transfers,
		long walkingDistance
	) {
		return new JourneyCandidate(
			id, DEPARTURE, ARRIVAL, realtimeDeparture, realtimeArrival, duration, transfers, walkingDistance,
			source, accessibility(), legs(realtimeDeparture, realtimeArrival)
		);
	}

	private static JourneyCandidate.Accessibility accessibility() {
		return new JourneyCandidate.Accessibility(true, List.of("STEP_FREE_PATH"));
	}

	private static List<JourneyCandidate.Leg> legs(Instant realtimeDeparture, Instant realtimeArrival) {
		return List.of(new JourneyCandidate.Ride(
			"line-1", "trip-1", "station-direction", "station-origin", "station-destination",
			DEPARTURE, ARRIVAL, realtimeDeparture, realtimeArrival
		));
	}

	private record TestRuntimeView(String routeBundleSha256, long generation)
		implements JourneyRaptorRuntimeView {
	}

	private record TestRealtimeView(String identity, String routeBundleSha256, long generation)
		implements JourneyRaptorRealtimeView {
	}
}
