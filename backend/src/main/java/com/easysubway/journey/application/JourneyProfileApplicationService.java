package com.easysubway.journey.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
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

	public JourneyProfileExecutionResult execute(JourneyRaptorQuery query) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query");
		Instant calculatedAt = clock.instant();
		if (requiredQuery.isCancelled()) return failure(JourneyProfileExecutionResult.Reason.CANCELLED);
		if (requiredQuery.timePolicy() != JourneyRequest.TimePolicy.TIMETABLE_REQUIRED) {
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

		JourneyProfileRaptorPort.TemporalPlan plan;
		try {
			plan = raptorPort.plan(requiredQuery, snapshot, null);
		} catch (RuntimeException exception) {
			return requiredQuery.isCancelled() ? failure(JourneyProfileExecutionResult.Reason.CANCELLED)
				: failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		if (requiredQuery.isCancelled()) return failure(JourneyProfileExecutionResult.Reason.CANCELLED);
		if (plan == null || !requiredQuery.temporalQuery().equals(plan.temporalQuery())) {
			return failure(JourneyProfileExecutionResult.Reason.RAPTOR_FAILED);
		}
		Instant completedAt = clock.instant();
		if (!postvalid(snapshot.validUntil(), completedAt, plan)) {
			return failure(JourneyProfileExecutionResult.Reason.ACTIVE_SNAPSHOT_STALE);
		}
		return new JourneyProfileExecutionResult.Success(calculatedAt, snapshot.validUntil(), source(snapshot), plan);
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

	private static void addReverseArrival(List<Instant> arrivals, JourneyProfileRaptorPort.ReversePlan result) {
		if (result instanceof JourneyProfileRaptorPort.ReversePlan.Found found) {
			arrivals.add(found.arrivalAtDestination());
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
}
