package com.easysubway.journey.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Executes one timetable-only temporal profile against one captured active snapshot. */
public final class JourneyProfileApplicationService {

	private final JourneyProfileSnapshotPort snapshotPort;
	private final JourneyProfileRaptorPort raptorPort;
	private final Clock clock;

	public JourneyProfileApplicationService(
		JourneyProfileSnapshotPort snapshotPort,
		JourneyProfileRaptorPort raptorPort,
		Clock clock
	) {
		this.snapshotPort = Objects.requireNonNull(snapshotPort, "snapshotPort");
		this.raptorPort = Objects.requireNonNull(raptorPort, "raptorPort");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public JourneyProfileExecutionResult execute(
		JourneyRaptorQuery query,
		JourneyProfileResourcePolicy resourcePolicy
	) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query");
		JourneyProfileResourcePolicy requiredPolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy");
		Instant calculatedAt = clock.instant();
		if (requiredQuery.isCancelled()) return failure(JourneyProfileExecutionResult.Reason.CANCELLED);
		Duration temporalWindow = switch (requiredQuery.temporalQuery()) {
			case JourneyRaptorQuery.DepartBetween range -> Duration.between(range.earliestReadyAt(), range.latestReadyAt());
			case JourneyRaptorQuery.ArriveBy arriveBy -> Duration.between(arriveBy.earliestReadyAt(), arriveBy.arrivalDeadline());
			// 막차는 입력 시간 범위가 아니라 선택된 service day의 실제 시간표로 제한한다.
			case JourneyRaptorQuery.LastConnection ignored -> Duration.ZERO;
			case JourneyRaptorQuery.DepartAt ignored -> throw new IllegalArgumentException(
				"profile execution requires a temporal profile query");
		};
		if (temporalWindow.compareTo(requiredPolicy.maxTemporalWindow()) > 0
			|| inputServiceDayCount(requiredQuery.temporalQuery()) > requiredPolicy.maxServiceDayCount()) {
			return failure(JourneyProfileExecutionResult.Reason.TEMPORAL_WINDOW_TOO_LARGE);
		}
		boolean realtimeRequired = requiredQuery.timePolicy() != JourneyRequest.TimePolicy.TIMETABLE_REQUIRED;
		if (realtimeRequired && !(requiredQuery.temporalQuery() instanceof JourneyRaptorQuery.LastConnection)) {
			if (realtimeNotApplicable(requiredQuery.temporalQuery(), calculatedAt, requiredPolicy)) {
				return failure(JourneyProfileExecutionResult.Reason.REALTIME_NOT_APPLICABLE);
			}
			return failure(JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE);
		}
		Instant freshnessReference = freshnessReference(requiredQuery.temporalQuery());
		var measurement = new JourneyRequestMeasurement(requiredQuery.requestId());
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot;
		try {
			snapshot = snapshotPort.requireActive(requiredQuery, freshnessReference, measurement);
		} catch (RuntimeException exception) {
			return requiredQuery.isCancelled() ? failure(JourneyProfileExecutionResult.Reason.CANCELLED)
				: failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
		}
		if (requiredQuery.isCancelled()) return failure(JourneyProfileExecutionResult.Reason.CANCELLED);
		if (!fresh(snapshot, calculatedAt, freshnessReference)) {
			return failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE);
		}
		if (realtimeRequired) {
			return classifyRealtimeLastConnection(requiredQuery, requiredPolicy, snapshot, calculatedAt);
		}

		JourneyProfileRaptorPort.PlanningResult planning;
		try {
			planning = raptorPort.plan(requiredQuery, snapshot, null, requiredPolicy.profilePlanningLimits());
		} catch (RuntimeException exception) {
			return requiredQuery.isCancelled() ? failure(JourneyProfileExecutionResult.Reason.CANCELLED)
				: failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		if (planning == null || !matches(requiredQuery, planning.countSnapshot())) {
			return failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		if (requiredQuery.isCancelled()) {
			return failure(JourneyProfileExecutionResult.Reason.CANCELLED, planning.countSnapshot());
		}
		if (planning instanceof JourneyProfileRaptorPort.PlanningResult.AdmissionRejected rejected) {
			return failure(JourneyProfileExecutionResult.Reason.TEMPORAL_QUERY_TOO_COMPLEX, rejected.countSnapshot());
		}
		if (planning instanceof JourneyProfileRaptorPort.PlanningResult.CapacityExceeded exceeded) {
			return failure(JourneyProfileExecutionResult.Reason.RAPTOR_FRONTIER_CAPACITY_EXCEEDED,
				exceeded.countSnapshot());
		}
		if (!(planning instanceof JourneyProfileRaptorPort.PlanningResult.Planned planned)) {
			return failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		JourneyProfileRaptorPort.TemporalPlan plan = planned.temporalPlan();
		if (plan == null || !requiredQuery.temporalQuery().equals(plan.temporalQuery())) {
			return failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED, planned.countSnapshot());
		}
		Instant completedAt = clock.instant();
		if (!postvalid(snapshot.validUntil(), completedAt, plan)) {
			return failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE, planned.countSnapshot());
		}
		JourneyProfileExecutionResult.Reason terminalFailure = terminalFailure(plan);
		if (terminalFailure != null) {
			return failure(terminalFailure, planned.countSnapshot());
		}
		return new JourneyProfileExecutionResult.Success(calculatedAt, snapshot.validUntil(), source(snapshot),
			requiredPolicy.identity(), plan, planned.countSnapshot());
	}

	private JourneyProfileExecutionResult classifyRealtimeLastConnection(
		JourneyRaptorQuery query,
		JourneyProfileResourcePolicy resourcePolicy,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		Instant calculatedAt
	) {
		JourneyProfileRaptorPort.LastConnectionPreparation preparation;
		try {
			preparation = raptorPort.prepareLastConnection(query, snapshot, resourcePolicy.profilePlanningLimits());
		} catch (RuntimeException exception) {
			return query.isCancelled() ? failure(JourneyProfileExecutionResult.Reason.CANCELLED)
				: failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		if (preparation == null || !matches(query, preparation.countSnapshot())) {
			return failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		if (query.isCancelled()) {
			return failure(JourneyProfileExecutionResult.Reason.CANCELLED, preparation.countSnapshot());
		}
		if (preparation instanceof JourneyProfileRaptorPort.LastConnectionPreparation.AdmissionRejected rejected) {
			return failure(JourneyProfileExecutionResult.Reason.TEMPORAL_QUERY_TOO_COMPLEX, rejected.countSnapshot());
		}
		if (preparation instanceof JourneyProfileRaptorPort.LastConnectionPreparation.CapacityExceeded exceeded) {
			return failure(JourneyProfileExecutionResult.Reason.RAPTOR_FRONTIER_CAPACITY_EXCEEDED,
				exceeded.countSnapshot());
		}
		if (!(preparation instanceof JourneyProfileRaptorPort.LastConnectionPreparation.Prepared prepared)) {
			return failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		Instant completedAt = clock.instant();
		if (!postvalid(snapshot.validUntil(), completedAt, prepared)) {
			return failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE, prepared.countSnapshot());
		}
		JourneyProfileExecutionResult.Reason terminalFailure = terminalFailure(prepared.terminal(),
			JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE);
		if (terminalFailure != null) return failure(terminalFailure, prepared.countSnapshot());
		if (exceedsRealtimeFutureHorizon(calculatedAt, prepared.terminalArrivalAtDestination(), resourcePolicy)) {
			return failure(JourneyProfileExecutionResult.Reason.REALTIME_NOT_APPLICABLE, prepared.countSnapshot());
		}
		return failure(JourneyProfileExecutionResult.Reason.REALTIME_UNAVAILABLE, prepared.countSnapshot());
	}

	private static long inputServiceDayCount(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		// 입력 범위의 운행일 수만 제한한다. 이전 운행일의 24:xx+ 열차 선택은 planner가 보존한다.
		return switch (temporalQuery) {
			case JourneyRaptorQuery.DepartBetween range -> inclusiveServiceDays(
				range.earliestReadyAt(), range.latestReadyAt());
			case JourneyRaptorQuery.ArriveBy arriveBy -> inclusiveServiceDays(
				arriveBy.earliestReadyAt(), arriveBy.arrivalDeadline());
			case JourneyRaptorQuery.LastConnection ignored -> 1;
			case JourneyRaptorQuery.DepartAt ignored -> throw new IllegalArgumentException(
				"profile execution requires a temporal profile query");
		};
	}

	private static long inclusiveServiceDays(Instant earliest, Instant latest) {
		return ChronoUnit.DAYS.between(ServiceDayResolver.resolve(earliest).serviceDate(),
			ServiceDayResolver.resolve(latest).serviceDate()) + 1;
	}

	private static boolean realtimeNotApplicable(
		JourneyRaptorQuery.TemporalQuery temporalQuery,
		Instant calculatedAt,
		JourneyProfileResourcePolicy resourcePolicy
	) {
		return switch (temporalQuery) {
			case JourneyRaptorQuery.DepartBetween range -> exceedsRealtimeFutureHorizon(
				calculatedAt, range.latestReadyAt(), resourcePolicy);
			case JourneyRaptorQuery.ArriveBy arriveBy -> exceedsRealtimeFutureHorizon(
				calculatedAt, arriveBy.arrivalDeadline(), resourcePolicy);
			// 막차는 실제 시간표 terminal horizon이 필요하며, 현재는 실시간 unavailable을 유지한다.
			case JourneyRaptorQuery.LastConnection ignored -> false;
			case JourneyRaptorQuery.DepartAt ignored -> throw new IllegalArgumentException(
				"profile execution requires a temporal profile query");
		};
	}

	private static boolean exceedsRealtimeFutureHorizon(
		Instant calculatedAt,
		Instant end,
		JourneyProfileResourcePolicy resourcePolicy
	) {
		return Duration.between(calculatedAt, end)
			.compareTo(resourcePolicy.realtimeApplicableFutureHorizon()) > 0;
	}

	private static JourneyProfileExecutionResult.Reason terminalFailure(
		JourneyProfileRaptorPort.TemporalPlan plan
	) {
		return switch (plan) {
			case JourneyProfileRaptorPort.DepartureWindowPlan departure -> departure.points().stream()
				.allMatch(point -> point.itineraries().isEmpty())
				? JourneyProfileExecutionResult.Reason.NO_SERVICE_IN_DEPARTURE_WINDOW : null;
			case JourneyProfileRaptorPort.ArriveByPlan arriveBy -> reverseTerminalFailure(arriveBy.result(),
				JourneyProfileExecutionResult.Reason.NO_ROUTE_ARRIVING_BY_DEADLINE);
			case JourneyProfileRaptorPort.LastConnectionPlan lastConnection -> reverseTerminalFailure(
				lastConnection.result(), JourneyProfileExecutionResult.Reason.NO_LAST_CONNECTION);
		};
	}

	private static JourneyProfileExecutionResult.Reason reverseTerminalFailure(
		JourneyProfileRaptorPort.ReversePlan result,
		JourneyProfileExecutionResult.Reason notFoundReason
	) {
		if (!(result instanceof JourneyProfileRaptorPort.ReversePlan.NotFound notFound)) return null;
		return notFound.outcome() == JourneyProfileRaptorPort.ReversePlan.Outcome.CANCELLED
			? JourneyProfileExecutionResult.Reason.CANCELLED : notFoundReason;
	}

	private static JourneyProfileExecutionResult.Reason terminalFailure(
		JourneyProfileRaptorPort.Terminal terminal,
		JourneyProfileExecutionResult.Reason notFoundReason
	) {
		if (!(terminal instanceof JourneyProfileRaptorPort.Terminal.NotFound notFound)) return null;
		return notFound.outcome() == JourneyProfileRaptorPort.ReversePlan.Outcome.CANCELLED
			? JourneyProfileExecutionResult.Reason.CANCELLED : notFoundReason;
	}

	private static boolean fresh(ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot, Instant calculatedAt,
		Instant freshnessReference) {
		return snapshot != null && snapshot.fresh()
			&& snapshot.boundaryReceipt().status() == ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.Status.OBSERVED
			&& snapshot.validUntil().isAfter(calculatedAt) && snapshot.validUntil().isAfter(freshnessReference);
	}

	private static boolean postvalid(Instant validUntil, Instant completedAt,
		JourneyProfileRaptorPort.TemporalPlan plan) {
		if (!validUntil.isAfter(completedAt)) return false;
		List<Instant> arrivals = new ArrayList<>();
		switch (plan) {
			case JourneyProfileRaptorPort.DepartureWindowPlan departure -> departure.points().forEach(point ->
				point.itineraries().forEach(itinerary -> arrivals.add(itinerary.plannedArrivalAtDestination())));
			case JourneyProfileRaptorPort.ArriveByPlan arriveBy -> addReverseArrival(arrivals, arriveBy.result());
			case JourneyProfileRaptorPort.LastConnectionPlan lastConnection -> {
				addReverseArrival(arrivals, lastConnection.result());
				if (lastConnection.terminalArrivalAtDestination() != null) {
					arrivals.add(lastConnection.terminalArrivalAtDestination());
				}
			}
		}
		return arrivals.stream().allMatch(validUntil::isAfter);
	}

	private static boolean postvalid(
		Instant validUntil,
		Instant completedAt,
		JourneyProfileRaptorPort.LastConnectionPreparation.Prepared preparation
	) {
		if (!validUntil.isAfter(completedAt)) return false;
		return !(preparation.terminal() instanceof JourneyProfileRaptorPort.Terminal.Found)
			|| validUntil.isAfter(preparation.terminalArrivalAtDestination());
	}

	private static void addReverseArrival(List<Instant> arrivals, JourneyProfileRaptorPort.ReversePlan result) {
		if (result instanceof JourneyProfileRaptorPort.ReversePlan.Found found) {
			found.itineraries().forEach(itinerary -> arrivals.add(itinerary.plannedArrivalAtDestination()));
		}
	}

	private static Instant freshnessReference(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return switch (temporalQuery) {
			case JourneyRaptorQuery.DepartBetween range -> range.latestReadyAt();
			case JourneyRaptorQuery.ArriveBy arriveBy -> arriveBy.arrivalDeadline();
			case JourneyRaptorQuery.LastConnection lastConnection -> lastConnection.serviceDate()
				.atTime(LocalTime.parse(ServiceDayResolver.CUTOFF_LOCAL_TIME)).atZone(ServiceDayResolver.ZONE).toInstant();
			case JourneyRaptorQuery.DepartAt ignored -> throw new IllegalArgumentException(
				"profile execution requires a temporal profile query");
		};
	}

	private static JourneyProfileExecutionResult.SourceIdentity source(
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot
	) {
		return new JourneyProfileExecutionResult.SourceIdentity(snapshot.routeBundleId(), snapshot.routeBundleSha256(),
			snapshot.timetableSnapshotId(), snapshot.accessibilitySnapshotId(), snapshot.generation());
	}

	private static JourneyProfileExecutionResult.Failure failure(JourneyProfileExecutionResult.Reason reason) {
		return new JourneyProfileExecutionResult.Failure(reason);
	}

	private static JourneyProfileExecutionResult.Failure failure(
		JourneyProfileExecutionResult.Reason reason,
		JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot
	) {
		return new JourneyProfileExecutionResult.Failure(reason, countSnapshot);
	}

	private static boolean matches(
		JourneyRaptorQuery query,
		JourneyRaptorPruningInventoryV1.CountSnapshot countSnapshot
	) {
		if (countSnapshot == null || !query.requestId().equals(countSnapshot.requestId())) return false;
		JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity expected = switch (query.temporalQuery()) {
			case JourneyRaptorQuery.DepartBetween ignored -> JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR;
			case JourneyRaptorQuery.ArriveBy ignored ->
				JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR;
			case JourneyRaptorQuery.LastConnection ignored ->
				JourneyRaptorPruningInventoryV1.REVERSE_RANGE_RAPTOR;
			case JourneyRaptorQuery.DepartAt ignored -> null;
		};
		return expected != null && expected.equals(countSnapshot.algorithmIdentity());
	}

}
