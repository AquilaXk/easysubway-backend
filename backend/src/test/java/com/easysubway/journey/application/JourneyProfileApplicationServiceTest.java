package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JourneyProfileApplicationServiceTest {

	private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
	private static final String SHA = "a".repeat(64);
	private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

	@Test
	void bindsSnapshotFreshnessToLatestDepartureReadyTime() {
		var reference = new AtomicReference<Instant>();
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> {
				reference.set(freshnessReference);
				return snapshot(Instant.parse("2026-09-01T02:00:00Z"));
			},
			(query, snapshot, realtime) -> new JourneyProfileRaptorPort.DepartureWindowPlan(
				(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of()),
			Clock.fixed(NOW, ZoneOffset.UTC));
		var latestReadyAt = Instant.parse("2026-09-01T01:30:00Z");

		var result = service.execute(query(new JourneyRaptorQuery.DepartBetween(NOW, latestReadyAt)));

		assertThat(result).isInstanceOf(JourneyProfileExecutionResult.Success.class);
		assertThat(reference.get()).isEqualTo(latestReadyAt);
	}

	@Test
	void rejectsLastConnectionWhenVerifiedTerminalHorizonExpiresTheSnapshotEvenWithoutOdJourney() {
		Instant validUntil = Instant.parse("2026-09-01T01:00:00Z");
		var lastConnection = new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1));
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(validUntil),
			(query, snapshot, realtime) -> new JourneyProfileRaptorPort.LastConnectionPlan(lastConnection,
				new JourneyProfileRaptorPort.ReversePlan.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.NO_OD_CONNECTION),
				validUntil),
			Clock.fixed(NOW, ZoneOffset.UTC));

		var result = service.execute(query(lastConnection));

		assertThat(result).isEqualTo(new JourneyProfileExecutionResult.Failure(
			JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE));
	}

	@Test
	void rejectsAPlanForAnotherTemporalQuery() {
		var requested = new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600));
		var different = new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(900));
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(1_800)),
			(query, snapshot, realtime) -> new JourneyProfileRaptorPort.DepartureWindowPlan(different, List.of()),
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(service.execute(query(requested))).isEqualTo(new JourneyProfileExecutionResult.Failure(
			JourneyProfileExecutionResult.Reason.RAPTOR_FAILED));
	}

	@Test
	void rejectsRealtimeBeforeReadingTheSnapshotOrCallingRaptor() {
		var calls = new AtomicInteger();
		var timetableQuery = query(new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600)));
		var realtimeQuery = new JourneyRaptorQuery(timetableQuery.requestId(), timetableQuery.originStationId(),
			timetableQuery.destinationStationId(), timetableQuery.temporalQuery(),
			JourneyRequest.TimePolicy.REALTIME_REQUIRED, timetableQuery.walkingPace(),
			timetableQuery.mobilityProfile(), timetableQuery.constraintMode(), timetableQuery.maxTransfers(),
			timetableQuery.alternativeCount(), timetableQuery.cancellationSignal());
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> {
				calls.incrementAndGet();
				return snapshot(NOW.plusSeconds(1_800));
			},
			(query, snapshot, realtime) -> {
				calls.incrementAndGet();
				return new JourneyProfileRaptorPort.DepartureWindowPlan(
					(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of());
			},
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(service.execute(realtimeQuery)).isEqualTo(new JourneyProfileExecutionResult.Failure(
			JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE));
		assertThat(calls).hasValue(0);
	}

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return new JourneyRaptorQuery(REQUEST_ID, "station-a", "station-b", temporalQuery,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
	}

	private static ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot(Instant validUntil) {
		JourneyRaptorRuntimeView runtime = new JourneyRaptorRuntimeView() {
			@Override public String routeBundleSha256() { return SHA; }
			@Override public long generation() { return 1; }
		};
		return new ActiveJourneySnapshotPort.ActiveJourneySnapshot(
			"snapshot", "bundle", SHA, "timetable", "accessibility", 1, runtime, validUntil, true,
			ActiveJourneySnapshotPort.ActiveServingEvidence.unobservable(),
			ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.observed(0, 0));
	}
}
