package com.easysubway.journey.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
			raptor((query, snapshot, realtime, limits) -> planned(query, new JourneyProfileRaptorPort.DepartureWindowPlan(
				(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of(
					new JourneyProfileRaptorPort.DeparturePoint(LocalDate.of(2026, 9, 1), NOW,
						List.of(itinerary(NOW.plusSeconds(600))), new JourneyRaptorPort.ScanMetrics(1, 1, 1)))))),
			Clock.fixed(NOW, ZoneOffset.UTC));
		var latestReadyAt = NOW.plus(policy().maxTemporalWindow());

		var result = service.execute(query(new JourneyRaptorQuery.DepartBetween(NOW, latestReadyAt)), policy());

		assertThat(result).isInstanceOf(JourneyProfileExecutionResult.Success.class);
		assertThat(reference.get()).isEqualTo(latestReadyAt);
		assertThat(((JourneyProfileExecutionResult.Success) result).resourcePolicyIdentity())
			.isEqualTo(policy().identity());
		assertThat(((JourneyProfileExecutionResult.Success) result).countSnapshot().requestId())
			.isEqualTo(REQUEST_ID);
	}

	@Test
	void rejectsOversizedWindowsBeforeSnapshotAcquisitionAndAcceptsTheExactPolicyBoundary() {
		var snapshotCalls = new AtomicInteger();
		var service = new JourneyProfileApplicationService((query, reference, measurement) -> {
			snapshotCalls.incrementAndGet();
			throw new IllegalStateException("no active snapshot in this fixture");
		}, raptor((query, snapshot, realtime, limits) -> {
			throw new AssertionError("planner must not run without an active snapshot");
		}), Clock.fixed(NOW, ZoneOffset.UTC));
		var boundary = NOW.plus(policy().maxTemporalWindow());
		for (var temporal : List.of(new JourneyRaptorQuery.DepartBetween(NOW, boundary.plusSeconds(1)),
			new JourneyRaptorQuery.ArriveBy(NOW, boundary.plusNanos(1)))) {
			assertThat(service.execute(query(temporal), policy())).isEqualTo(
				new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.TEMPORAL_WINDOW_TOO_LARGE));
		}
		assertThat(snapshotCalls).hasValue(0);
		for (var temporal : List.of(new JourneyRaptorQuery.DepartBetween(NOW, boundary),
			new JourneyRaptorQuery.ArriveBy(NOW, boundary))) {
			assertThat(service.execute(query(temporal), policy())).isEqualTo(
				new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE));
		}
		assertThat(snapshotCalls).hasValue(2);
	}

	@Test
	void enforcesInputServiceDayCountAtTheCutoffAndAdmitsOneLastConnectionDay() {
		var snapshotCalls = new AtomicInteger();
		var service = new JourneyProfileApplicationService((query, reference, measurement) -> {
			snapshotCalls.incrementAndGet();
			throw new IllegalStateException("no active snapshot in this fixture");
		}, raptor((query, snapshot, realtime, limits) -> {
			throw new AssertionError("planner must not run without an active snapshot");
		}), Clock.fixed(NOW, ZoneOffset.UTC));
		// 2024-01-02 03:00 KST: 자정이 아니라 계약상 cutoff에서 입력 운행일이 바뀐다.
		var cutoff = Instant.parse("2024-01-01T18:00:00Z");
		var base = policy();
		var oneDay = new JourneyProfileResourcePolicy(base.identity(), base.maxTemporalWindow(), 1,
			base.maxEstimatedWork(), base.maxLabelsPerState(), base.maxDestinationProfileLabels(),
			base.maxProfileBreakpoints(), base.realtimeApplicableFutureHorizon(), base.pointSearchDeadline(),
			base.profileSearchDeadline(), base.lastConnectionDeadline(), base.pointSearchCostUnits(),
			base.shortDepartureProfileCostUnits(), base.arriveByProfileCostUnits(), base.lastConnectionCostUnits(),
			base.maxCostUnitsPerSession());
		var crossing = List.of(new JourneyRaptorQuery.DepartBetween(cutoff.minusSeconds(1), cutoff),
			new JourneyRaptorQuery.ArriveBy(cutoff.minusSeconds(1), cutoff));
		for (var temporal : crossing) {
			assertThat(service.execute(query(temporal), oneDay)).isEqualTo(
				new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.TEMPORAL_WINDOW_TOO_LARGE));
		}
		assertThat(snapshotCalls).hasValue(0);
		for (var temporal : crossing) {
			assertThat(service.execute(query(temporal), base)).isEqualTo(
				new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE));
		}
		for (var temporal : List.of(new JourneyRaptorQuery.DepartBetween(cutoff.minusSeconds(2), cutoff.minusSeconds(1)),
			new JourneyRaptorQuery.ArriveBy(cutoff.minusSeconds(2), cutoff.minusSeconds(1)),
			new JourneyRaptorQuery.LastConnection(LocalDate.of(2024, 1, 1)))) {
			assertThat(service.execute(query(temporal), oneDay)).isEqualTo(
				new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE));
		}
		assertThat(snapshotCalls).hasValue(5);
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
				raptor((query, snapshot, realtime, limits) -> new JourneyProfileRaptorPort.PlanningResult.Planned(
					terminal.plan(), counts, planningMetrics())),
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
			raptor((query, snapshot, realtime, limits) -> planned(query, new JourneyProfileRaptorPort.LastConnectionPlan(lastConnection,
				new JourneyProfileRaptorPort.ReversePlan.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.NO_OD_CONNECTION),
				validUntil))),
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
			raptor((query, snapshot, realtime, limits) -> planned(query, new JourneyProfileRaptorPort.ArriveByPlan(arriveBy,
					new JourneyProfileRaptorPort.ReversePlan.Found(List.of(
						itinerary(NOW.plusSeconds(60)), itinerary(NOW.plusSeconds(180))))))),
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
			raptor((query, snapshot, realtime, limits) -> planned(query,
				new JourneyProfileRaptorPort.DepartureWindowPlan(different, List.of()))),
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
			raptor((query, snapshot, realtime, limits) -> {
				calls.incrementAndGet();
				return planned(query, new JourneyProfileRaptorPort.DepartureWindowPlan(
					(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of()));
			}),
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(service.execute(realtimeQuery, policy())).isEqualTo(new JourneyProfileExecutionResult.Failure(
			JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE));
		assertThat(calls).hasValue(0);
	}

	@Test
	void rejectsRealtimeTemporalProfilesOutsideTheApplicableFutureHorizonBeforeSnapshotOrPlanning() {
		var snapshotCalls = new AtomicInteger();
		var plannerCalls = new AtomicInteger();
		var policy = policy();
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> {
				snapshotCalls.incrementAndGet();
				return snapshot(NOW.plusSeconds(1_800));
			},
			raptor((query, snapshot, realtime, limits) -> {
				plannerCalls.incrementAndGet();
				return planned(query, new JourneyProfileRaptorPort.DepartureWindowPlan(
					(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of()));
			}),
			Clock.fixed(NOW, ZoneOffset.UTC));
		var horizon = NOW.plus(policy.realtimeApplicableFutureHorizon());
		var earliestReadyAt = horizon.minusSeconds(1);

		for (var temporal : List.of(
			new JourneyRaptorQuery.DepartBetween(earliestReadyAt, horizon),
			new JourneyRaptorQuery.ArriveBy(earliestReadyAt, horizon)
		)) {
			assertThat(service.execute(realtimeRequired(query(temporal)), policy)).isEqualTo(
				new JourneyProfileExecutionResult.Failure(JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE));
		}
		for (var temporal : List.of(
			new JourneyRaptorQuery.DepartBetween(earliestReadyAt, horizon.plusSeconds(1)),
			new JourneyRaptorQuery.ArriveBy(earliestReadyAt, horizon.plusNanos(1))
		)) {
			assertThat(service.execute(realtimeRequired(query(temporal)), policy)).isEqualTo(
				new JourneyProfileExecutionResult.Failure(
					JourneyProfileExecutionResult.Reason.REALTIME_NOT_APPLICABLE));
		}
		assertThat(snapshotCalls).hasValue(0);
		assertThat(plannerCalls).hasValue(0);
	}

	@Test
	void classifiesRealtimeLastConnectionFromThePreparedNativeTerminalWithoutRouteOrProviderPreflight() {
		Instant calculatedAt = Instant.parse("2026-09-01T18:00:00Z");
		var policy = policy();
		var preparationCalls = new AtomicInteger();
		var fullRouteCalls = new AtomicInteger();
		var snapshotCalls = new AtomicInteger();
		var temporal = new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1));
		Instant horizon = calculatedAt.plus(policy.realtimeApplicableFutureHorizon());
		for (var terminal : List.of(horizon.minusNanos(1), horizon,
			calculatedAt.plus(policy.realtimeApplicableFutureHorizon()).plusNanos(1))) {
			var service = new JourneyProfileApplicationService(
				(query, freshnessReference, measurement) -> {
					snapshotCalls.incrementAndGet();
					return snapshot(terminal.plusSeconds(1));
				},
				raptor((query, snapshot, realtime, limits) -> {
					fullRouteCalls.incrementAndGet();
					throw new AssertionError("realtime applicability must not execute a full route search");
				}, (query, snapshot, limits) -> {
					preparationCalls.incrementAndGet();
					return preparedTerminal(query, terminal);
				}),
				Clock.fixed(calculatedAt, ZoneOffset.UTC));

			assertThat(((JourneyProfileExecutionResult.Failure) service.execute(realtimeRequired(query(temporal)), policy)).reason())
				.isEqualTo(terminal.isAfter(horizon) ? JourneyProfileExecutionResult.Reason.REALTIME_NOT_APPLICABLE
					: JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE);
		}
		var noTerminal = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> {
				snapshotCalls.incrementAndGet();
				return snapshot(horizon.plusSeconds(1));
			},
			raptor((query, snapshot, realtime, limits) -> {
				fullRouteCalls.incrementAndGet();
				throw new AssertionError("realtime applicability must not execute a full route search");
			}, (query, snapshot, limits) -> {
				preparationCalls.incrementAndGet();
				return new JourneyProfileRaptorPort.LastConnectionPreparation.Prepared(
					new JourneyProfileRaptorPort.Terminal.NotFound(
						JourneyProfileRaptorPort.ReversePlan.Outcome.NO_ACTIVE_SERVICE), null,
					snapshot(query), planningMetrics());
			}),
			Clock.fixed(calculatedAt, ZoneOffset.UTC));

		assertThat(((JourneyProfileExecutionResult.Failure) noTerminal.execute(realtimeRequired(query(temporal)), policy)).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE);
		assertThat(snapshotCalls).hasValue(4);
		assertThat(preparationCalls).hasValue(4);
		assertThat(fullRouteCalls).hasValue(0);
	}

	@Test
	void containsRealtimeLastConnectionPreparationExceptionsWithCancellationPrecedence() {
		for (boolean cancelDuringPreparation : List.of(false, true)) {
			var cancelled = new AtomicBoolean();
			var original = query(new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1)));
			var requested = new JourneyRaptorQuery(original.requestId(), original.originStationId(),
				original.destinationStationId(), original.temporalQuery(), JourneyRequest.TimePolicy.REALTIME_REQUIRED,
				original.walkingPace(), original.mobilityProfile(), original.constraintMode(), original.maxTransfers(),
				original.alternativeCount(), cancelled::get);
			var preparationCalls = new AtomicInteger();
			var fullPlanCalls = new AtomicInteger();
			var service = new JourneyProfileApplicationService(
				(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(86_400)),
				raptor((query, snapshot, realtime, limits) -> {
					fullPlanCalls.incrementAndGet();
					throw new AssertionError("realtime last-connection classification must not plan a route");
				}, (query, snapshot, limits) -> {
					preparationCalls.incrementAndGet();
					cancelled.set(cancelDuringPreparation);
					throw new IllegalStateException("preparation failure in this fixture");
				}), Clock.fixed(NOW, ZoneOffset.UTC));

			assertThat(((JourneyProfileExecutionResult.Failure) service.execute(requested, policy())).reason()).isEqualTo(
				cancelDuringPreparation ? JourneyProfileExecutionResult.Reason.CANCELLED
					: JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
			assertThat(preparationCalls).hasValue(1);
			assertThat(fullPlanCalls).hasValue(0);
		}
	}

	@Test
	void rejectsInvalidRealtimeLastConnectionPreparationEvidenceWithoutPlanningARoute() {
		var requested = realtimeRequired(query(new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1))));
		var fullPlanCalls = new AtomicInteger();
		for (LastConnectionPreparer preparer : List.<LastConnectionPreparer>of(
			(query, snapshot, limits) -> null,
			(query, snapshot, limits) -> new JourneyProfileRaptorPort.LastConnectionPreparation.Prepared(
				new JourneyProfileRaptorPort.Terminal.Found(), NOW.plusSeconds(60),
				snapshot("01ARZ3NDEKTSV4RRFFQ69G5FAA", JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR),
				planningMetrics())
		)) {
			var service = new JourneyProfileApplicationService(
				(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(86_400)),
				raptor((query, snapshot, realtime, limits) -> {
					fullPlanCalls.incrementAndGet();
					throw new AssertionError("realtime last-connection classification must not plan a route");
				}, preparer), Clock.fixed(NOW, ZoneOffset.UTC));

			assertThat(((JourneyProfileExecutionResult.Failure) service.execute(requested, policy())).reason())
				.isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		assertThat(fullPlanCalls).hasValue(0);
	}

	@Test
	void mapsRealtimeLastConnectionPreparationOutcomesWithoutPlanningAFullRoute() {
		var temporal = new JourneyRaptorQuery.LastConnection(LocalDate.of(2026, 9, 1));
		var requested = realtimeRequired(query(temporal));
		var matchingCounts = snapshot(requested);
		var cancelledCounts = snapshot(requested);
		var fullPlanCalls = new AtomicInteger();
		var cancelled = new AtomicBoolean();
		var cancelledRequest = new JourneyRaptorQuery(requested.requestId(), requested.originStationId(),
			requested.destinationStationId(), requested.temporalQuery(), requested.timePolicy(), requested.walkingPace(),
			requested.mobilityProfile(), requested.constraintMode(), requested.maxTransfers(), requested.alternativeCount(),
			cancelled::get);
		var admissionService = realtimeLastConnectionService(fullPlanCalls, NOW.plusSeconds(86_400),
			(query, snapshot, limits) -> new JourneyProfileRaptorPort.LastConnectionPreparation.AdmissionRejected(
				1_001, 1_000, matchingCounts, planningMetrics()));
		var capacityService = realtimeLastConnectionService(fullPlanCalls, NOW.plusSeconds(86_400),
			(query, snapshot, limits) -> new JourneyProfileRaptorPort.LastConnectionPreparation.CapacityExceeded(
				JourneyProfileRaptorPort.PlanningCapacity.MAX_LABELS_PER_STATE, 9, 8, matchingCounts, planningMetrics()));
		var cancelledService = realtimeLastConnectionService(fullPlanCalls, NOW.plusSeconds(86_400),
			(query, snapshot, limits) -> {
				cancelled.set(true);
				return new JourneyProfileRaptorPort.LastConnectionPreparation.Prepared(
					new JourneyProfileRaptorPort.Terminal.Found(), NOW.plusSeconds(60), cancelledCounts, planningMetrics());
			});
		var expiryBoundary = NOW.plusSeconds(68_400);
		var staleService = realtimeLastConnectionService(fullPlanCalls, expiryBoundary,
			(query, snapshot, limits) -> preparedTerminal(query, expiryBoundary));
		var nativeCancelledService = realtimeLastConnectionService(fullPlanCalls, NOW.plusSeconds(86_400),
			(query, snapshot, limits) -> new JourneyProfileRaptorPort.LastConnectionPreparation.Prepared(
				new JourneyProfileRaptorPort.Terminal.NotFound(
					JourneyProfileRaptorPort.ReversePlan.Outcome.CANCELLED), null, matchingCounts, planningMetrics()));

		assertThat(((JourneyProfileExecutionResult.Failure) admissionService.execute(requested, policy())).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.TEMPORAL_QUERY_TOO_COMPLEX);
		assertThat(((JourneyProfileExecutionResult.Failure) capacityService.execute(requested, policy())).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FRONTIER_CAPACITY_EXCEEDED);
		var cancellation = (JourneyProfileExecutionResult.Failure) cancelledService.execute(cancelledRequest, policy());
		assertThat(cancellation.reason()).isEqualTo(JourneyProfileExecutionResult.Reason.CANCELLED);
		assertThat(cancellation.countSnapshot()).isSameAs(cancelledCounts);
		assertThat(((JourneyProfileExecutionResult.Failure) staleService.execute(requested, policy())).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE);
		assertThat(((JourneyProfileExecutionResult.Failure) nativeCancelledService.execute(requested, policy())).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.CANCELLED);
		assertThat(fullPlanCalls).hasValue(0);
	}

	private static JourneyProfileApplicationService realtimeLastConnectionService(
		AtomicInteger fullPlanCalls,
		Instant snapshotValidUntil,
		LastConnectionPreparer preparer
	) {
		return new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(snapshotValidUntil),
			raptor((query, snapshot, realtime, limits) -> {
				fullPlanCalls.incrementAndGet();
				throw new AssertionError("realtime last-connection classification must not plan a route");
			}, preparer), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void keepsTimetableProfilesIndependentOfRealtimeFutureHorizon() {
		var policy = policy();
		var end = NOW.plus(policy.realtimeApplicableFutureHorizon()).plusSeconds(1);
		var temporal = new JourneyRaptorQuery.DepartBetween(
			NOW.plus(policy.realtimeApplicableFutureHorizon()),
			end);
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(end.plusSeconds(1)),
			raptor((query, snapshot, realtime, limits) -> planned(query, new JourneyProfileRaptorPort.DepartureWindowPlan(
				(JourneyRaptorQuery.DepartBetween) query.temporalQuery(), List.of()))),
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(((JourneyProfileExecutionResult.Failure) service.execute(query(temporal), policy)).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.NO_SERVICE_IN_DEPARTURE_WINDOW);
	}

	@Test
	void exposesAPlannerCapacityLimitAsAnExactFailClosedResult() {
		var requested = query(new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600)));
		var capacityCounts = capacitySnapshot(requested);
		JourneyProfileRaptorPort raptor = raptor((
				JourneyRaptorQuery query,
				ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
				JourneyRealtimePort.RealtimeObservation realtime,
				JourneyProfileResourcePolicy.ProfilePlanningLimits limits
			) -> new JourneyProfileRaptorPort.PlanningResult.CapacityExceeded(
				JourneyProfileRaptorPort.PlanningCapacity.MAX_LABELS_PER_STATE, 9, 8, capacityCounts, planningMetrics()));
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
	void containsPlannerExceptionsAndPreservesCancellationPrecedence() {
		for (boolean cancelDuringPlanning : List.of(false, true)) {
			var cancelled = new AtomicBoolean();
			var original = query(new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600)));
			var requested = new JourneyRaptorQuery(original.requestId(), original.originStationId(),
				original.destinationStationId(), original.temporalQuery(), original.timePolicy(),
				original.walkingPace(), original.mobilityProfile(), original.constraintMode(),
				original.maxTransfers(), original.alternativeCount(), cancelled::get);
			var plannerCalls = new AtomicInteger();
			var service = new JourneyProfileApplicationService(
				(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(1_800)),
				raptor((query, snapshot, realtime, limits) -> {
					plannerCalls.incrementAndGet();
					cancelled.set(cancelDuringPlanning);
					throw new IllegalStateException("planner failure in this fixture");
				}), Clock.fixed(NOW, ZoneOffset.UTC));

			assertThat(service.execute(requested, policy())).isEqualTo(
				new JourneyProfileExecutionResult.Failure(cancelDuringPlanning
					? JourneyProfileExecutionResult.Reason.CANCELLED
					: JourneyProfileExecutionResult.Reason.RAPTOR_FAILED));
			assertThat(plannerCalls).hasValue(1);
		}
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
				raptor((query, snapshot, realtime, limits) ->
					new JourneyProfileRaptorPort.PlanningResult.Planned(plan, invalid, planningMetrics())),
				Clock.fixed(NOW, ZoneOffset.UTC));

			var failure = (JourneyProfileExecutionResult.Failure) service.execute(requested, policy());
			assertThat(failure.reason()).isEqualTo(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
			assertThat(failure.countSnapshot()).isNull();
		}
	}

	@Test
	void passesExactPlanningLimitsAndMapsWorkAdmissionRejection() {
		var captured = new AtomicReference<JourneyProfileResourcePolicy.ProfilePlanningLimits>();
		JourneyProfileRaptorPort raptor = raptor((query, snapshot, realtime, limits) -> {
			captured.set(limits);
			return new JourneyProfileRaptorPort.PlanningResult.AdmissionRejected(1_001, 1_000, snapshot(query), planningMetrics());
		});
		var service = new JourneyProfileApplicationService(
			(query, freshnessReference, measurement) -> snapshot(NOW.plusSeconds(1_800)),
			raptor,
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(((JourneyProfileExecutionResult.Failure) service.execute(
			query(new JourneyRaptorQuery.DepartBetween(NOW, NOW.plusSeconds(600))), policy())).reason())
			.isEqualTo(JourneyProfileExecutionResult.Reason.TEMPORAL_QUERY_TOO_COMPLEX);
		assertThat(captured.get()).isEqualTo(policy().profilePlanningLimits());
	}

	private static JourneyProfileRaptorPort raptor(Planning planning) {
		return raptor(planning, (query, snapshot, limits) -> {
			throw new AssertionError("last-connection preparation is not expected by this fixture");
		});
	}

	private static JourneyProfileRaptorPort raptor(
		Planning planning,
		LastConnectionPreparer preparer
	) {
		return new JourneyProfileRaptorPort() {
			@Override
			public PlanningResult plan(
				JourneyRaptorQuery query,
				ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
				JourneyRealtimePort.RealtimeObservation realtime,
				JourneyProfileResourcePolicy.ProfilePlanningLimits limits
			) {
				return planning.plan(query, snapshot, realtime, limits);
			}

			@Override
			public LastConnectionPreparation prepareLastConnection(
				JourneyRaptorQuery query,
				ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
				JourneyProfileResourcePolicy.ProfilePlanningLimits limits
			) {
				return preparer.prepare(query, snapshot, limits);
			}
		};
	}

	@FunctionalInterface
	private interface Planning {
		JourneyProfileRaptorPort.PlanningResult plan(
			JourneyRaptorQuery query,
			ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
			JourneyRealtimePort.RealtimeObservation realtime,
			JourneyProfileResourcePolicy.ProfilePlanningLimits limits
		);
	}

	@FunctionalInterface
	private interface LastConnectionPreparer {
		JourneyProfileRaptorPort.LastConnectionPreparation prepare(
			JourneyRaptorQuery query,
			ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
			JourneyProfileResourcePolicy.ProfilePlanningLimits limits
		);
	}

	private static JourneyProfileRaptorPort.LastConnectionPreparation.Prepared preparedTerminal(
		JourneyRaptorQuery query,
		Instant terminal
	) {
		return new JourneyProfileRaptorPort.LastConnectionPreparation.Prepared(
			new JourneyProfileRaptorPort.Terminal.Found(), terminal, snapshot(query), planningMetrics());
	}

	private static JourneyProfileResourcePolicy policy() {
		return JourneyProfileResourcePolicyTest.policy(Duration.ofSeconds(1));
	}

	private static JourneyRaptorQuery query(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return new JourneyRaptorQuery(REQUEST_ID, "station-a", "station-b", temporalQuery,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD, JourneyRequest.ConstraintMode.NONE, 0, 1, () -> false);
	}

	private static JourneyRaptorQuery realtimeRequired(JourneyRaptorQuery query) {
		return new JourneyRaptorQuery(query.requestId(), query.originStationId(), query.destinationStationId(),
			query.temporalQuery(), JourneyRequest.TimePolicy.REALTIME_REQUIRED, query.walkingPace(),
			query.mobilityProfile(), query.constraintMode(), query.maxTransfers(), query.alternativeCount(),
			query.cancellationSignal());
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
