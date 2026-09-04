package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
			(query, snapshot, realtime, limits) -> planned(query, new JourneyProfileRaptorPort.DepartureWindowPlan(
				(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of())),
			Clock.fixed(NOW, ZoneOffset.UTC));
		var latestReadyAt = Instant.parse("2026-09-01T01:30:00Z");

		var result = service.execute(query(new JourneyRaptorQuery.DepartBetween(NOW, latestReadyAt)), policy());

		assertThat(result).isInstanceOf(JourneyProfileExecutionResult.Success.class);
		assertThat(reference.get()).isEqualTo(latestReadyAt);
		assertThat(((JourneyProfileExecutionResult.Success) result).resourcePolicyIdentity())
			.isEqualTo(policy().identity());
		assertThat(((JourneyProfileExecutionResult.Success) result).countSnapshot().requestId())
			.isEqualTo(REQUEST_ID);
	}

	@Test
	void mapsTerminalTemporalPlansToFailuresBeforePublishingSuccess() {
		var departBetween = new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600));
		var arriveBy = new JourneyRaptorQuery.ArriveBy(NOW, NOW.plusSeconds(600));
		var lastConnection = new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1));
		var cases = List.of(
			new TerminalPlanCase(departBetween, new JourneyProfileRaptorPort.DepartureWindowPlan(departBetween,
				List.of(emptyDeparturePoint())), JourneyProfileExecutionResult.Reason.NO_SERVICE_IN_DEPARTURE_WINDOW),
			new TerminalPlanCase(arriveBy, new JourneyProfileRaptorPort.ArriveByPlan(arriveBy,
				new JourneyProfileRaptorPort.ReversePlan.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.NO_OD_CONNECTION)),
				JourneyProfileExecutionResult.Reason.NO_ROUTE_ARRIVING_BY_DEADLINE),
			new TerminalPlanCase(lastConnection, new JourneyProfileRaptorPort.LastConnectionPlan(lastConnection,
				new JourneyProfileRaptorPort.ReversePlan.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.NO_OD_CONNECTION), null),
				JourneyProfileExecutionResult.Reason.NO_LAST_CONNECTION),
			new TerminalPlanCase(arriveBy, new JourneyProfileRaptorPort.ArriveByPlan(arriveBy,
				new JourneyProfileRaptorPort.ReversePlan.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.CANCELLED)),
				JourneyProfileExecutionResult.Reason.CANCELLED),
			new TerminalPlanCase(lastConnection, new JourneyProfileRaptorPort.LastConnectionPlan(lastConnection,
				new JourneyProfileRaptorPort.ReversePlan.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.CANCELLED), null),
				JourneyProfileExecutionResult.Reason.CANCELLED));

		for (var terminal : cases) {
			var requested = query(terminal.temporalQuery());
			var counts = snapshot(requested);
			var service = new JourneyProfileApplicationService(
				(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(1_800)),
				(query, snapshot, realtime, limits) -> new JourneyProfileRaptorPort.PlanningResult.Planned(
					terminal.plan(), counts, planningMetrics()),
				Clock.fixed(NOW, ZoneOffset.UTC));

			var result = service.execute(requested, policy());

			assertThat(result).isNotInstanceOf(JourneyProfileExecutionResult.Success.class);
			var failure = (JourneyProfileExecutionResult.Failure) result;
			assertThat(failure.reason()).isEqualTo(terminal.reason());
			assertThat(failure.countSnapshot()).isSameAs(counts);
		}
	}

	@Test
	void rejectsLastConnectionWhenVerifiedTerminalHorizonExpiresTheSnapshotEvenWithoutOdJourney() {
		Instant validUntil = Instant.parse("2026-09-01T01:00:00Z");
		var lastConnection = new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1));
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(validUntil),
			(query, snapshot, realtime, limits) -> planned(query, new JourneyProfileRaptorPort.LastConnectionPlan(lastConnection,
				new JourneyProfileRaptorPort.ReversePlan.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.NO_OD_CONNECTION),
				validUntil)),
			Clock.fixed(NOW, ZoneOffset.UTC));

		var result = service.execute(query(lastConnection), policy());

		var failure = (JourneyProfileExecutionResult.Failure) result;
		assertThat(failure.reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE);
		assertThat(failure.countSnapshot().requestId()).isEqualTo(REQUEST_ID);
	}

	@Test
	void rejectsReverseFrontierWhenAnyReturnedItineraryOutlivesTheCapturedSnapshot() {
		Instant validUntil = NOW.plusSeconds(120);
		var arriveBy = new JourneyRaptorQuery.ArriveBy(NOW, NOW.plusSeconds(60));
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(validUntil),
			(query, snapshot, realtime, limits) -> planned(query, new JourneyProfileRaptorPort.ArriveByPlan(arriveBy,
					new JourneyProfileRaptorPort.ReversePlan.Found(List.of(
						itinerary(NOW.plusSeconds(60)), itinerary(NOW.plusSeconds(180)))))),
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(((JourneyProfileExecutionResult.Failure) service.execute(query(arriveBy), policy())).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE);
	}

	@Test
	void rejectsAPlanForAnotherTemporalQuery() {
		var requested = new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600));
		var different = new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(900));
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(1_800)),
			(query, snapshot, realtime, limits) -> planned(query,
				new JourneyProfileRaptorPort.DepartureWindowPlan(different, List.of())),
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(((JourneyProfileExecutionResult.Failure) service.execute(query(requested), policy())).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
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
			(query, snapshot, realtime, limits) -> {
				calls.incrementAndGet();
				return planned(query, new JourneyProfileRaptorPort.DepartureWindowPlan(
					(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of()));
			},
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(service.execute(realtimeQuery, policy())).isEqualTo(new JourneyProfileExecutionResult.Failure(
			JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE));
		assertThat(calls).hasValue(0);
	}

	@Test
	void exposesAPlannerCapacityLimitAsAnExactFailClosedResult() {
		var requested = query(new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600)));
		var capacityCounts = capacitySnapshot(requested);
		JourneyProfileRaptorPort raptor = (
				JourneyRaptorQuery query,
				ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
				JourneyRealtimePort.RealtimeObservation realtime,
				JourneyProfileResourcePolicy.ProfilePlanningLimits limits
			) -> new JourneyProfileRaptorPort.PlanningResult.CapacityExceeded(
				JourneyProfileRaptorPort.PlanningCapacity.MAX_LABELS_PER_STATE, 9, 8, capacityCounts, planningMetrics());
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(1_800)),
			raptor,
			Clock.fixed(NOW, ZoneOffset.UTC));

		var failure = (JourneyProfileExecutionResult.Failure) service.execute(requested, policy());
		assertThat(failure.reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FRONTIER_CAPACITY_EXCEEDED);
		assertThat(failure.countSnapshot()).isSameAs(capacityCounts);
		assertThat(failure.countSnapshot().countsByRuleId()
			.get("FAIL_CLOSED_FRONTIER_CAPACITY_V1")).isEqualTo(1L);
	}

	@Test
	void rejectsPlanningEvidenceBoundToAnotherRequestOrTemporalAlgorithm() {
		var temporalQuery = new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600));
		var requested = query(temporalQuery);
		var plan = new JourneyProfileRaptorPort.DepartureWindowPlan(temporalQuery, List.of());
		var forward = JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR;
		var reverse = JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR;
		for (var invalid : List.of(snapshot("01ARZ3NDEKTSV4RRFFQ69G5FAA", forward),
			snapshot(REQUEST_ID, reverse))) {
			var service = new JourneyProfileApplicationService(
				(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(1_800)),
				(query, snapshot, realtime, limits) ->
					new JourneyProfileRaptorPort.PlanningResult.Planned(plan, invalid, planningMetrics()),
				Clock.fixed(NOW, ZoneOffset.UTC));

			var failure = (JourneyProfileExecutionResult.Failure) service.execute(requested, policy());
			assertThat(failure.reason()).isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
			assertThat(failure.countSnapshot()).isNull();
		}
	}

	@Test
	void passesExactPlanningLimitsAndMapsWorkAdmissionRejection() {
		var captured = new AtomicReference<JourneyProfileResourcePolicy.ProfilePlanningLimits>();
		JourneyProfileRaptorPort raptor = (query, snapshot, realtime, limits) -> {
			captured.set(limits);
			return new JourneyProfileRaptorPort.PlanningResult.AdmissionRejected(1_001, 1_000, snapshot(query), planningMetrics());
		};
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(1_800)),
			raptor,
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(((JourneyProfileExecutionResult.Failure) service.execute(
			query(new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600))), policy())).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.TEMPORAL_QUERY_TOO_COMPLEX);
		assertThat(captured.get()).isEqualTo(policy().profilePlanningLimits());
	}

	private static JourneyProfileResourcePolicy policy() {
		return JourneyProfileResourcePolicyTest.policy(Duration.ofSeconds(1));
	}

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return new JourneyRaptorQuery(REQUEST_ID, "station-a", "station-b", temporalQuery,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
	}

	private static JourneyProfileRaptorPort.PlanningResult.Planned planned(
		JourneyRaptorQuery query,
		JourneyProfileRaptorPort.TemporalPlan plan
	) {
		return new JourneyProfileRaptorPort.PlanningResult.Planned(plan, snapshot(query), planningMetrics());
	}

	private static JourneyProfileRaptorPort.PlanningMetrics planningMetrics() {
		return new JourneyProfileRaptorPort.PlanningMetrics(0, 0, 0, 0);
	}

	private static JourneyRaptorPruningInventoryV1.CountSnapshot snapshot(JourneyRaptorQuery query) {
		var identity = query.temporalQuery() instanceof JourneyRaptorQuery.DepartBetween
			? JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR
			: JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR;
		return snapshot(query.requestId(), identity);
	}

	private static JourneyRaptorPruningInventoryV1.CountSnapshot snapshot(
		String requestId,
		JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity identity
	) {
		return new JourneyRaptorPruningInventoryV1.CountSnapshot(requestId, identity,
			JourneyRaptorPruningInventoryV1.activeRuleIds(identity).stream()
				.collect(java.util.stream.Collectors.toMap(rule -> rule, ignored -> 0L)));
	}

	private static JourneyRaptorPruningInventoryV1.CountSnapshot capacitySnapshot(JourneyRaptorQuery query) {
		var observed = snapshot(query);
		var counts = new LinkedHashMap<>(observed.countsByRuleId());
		counts.put("FAIL_CLOSED_FRONTIER_CAPACITY_V1", 1L);
		return new JourneyRaptorPruningInventoryV1.CountSnapshot(
			observed.requestId(), observed.algorithmIdentity(), counts);
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

	private static JourneyProfileRaptorPort.Itinerary itinerary(Instant arrivalAtDestination) {
		return new JourneyProfileRaptorPort.Itinerary(LocalDate.of(2026, 9, 1), NOW, arrivalAtDestination,
			null, null, new JourneyProfileRaptorPort.ItineraryMetrics(
				0, 0, 0, 0, new JourneyProfileRaptorPort.NoTransfer()),
			List.of(new JourneyProfileRaptorPort.AccessLeg(JourneyProfileRaptorPort.AccessKind.ENTRY,
				"station-a", "station-a", 0, 0, false, true, "VERIFIED")));
	}

	private static JourneyProfileRaptorPort.DeparturePoint emptyDeparturePoint() {
		return new JourneyProfileRaptorPort.DeparturePoint(LocalDate.of(2026, 9, 1), NOW, List.of(),
			new JourneyRaptorPort.ScanMetrics(1, 1, 1));
	}

	private record TerminalPlanCase(
		JourneyRaptorQuery.TemporalQuery temporalQuery,
		JourneyProfileRaptorPort.TemporalPlan plan,
		JourneyProfileExecutionResult.Reason reason
	) {
	}
}
