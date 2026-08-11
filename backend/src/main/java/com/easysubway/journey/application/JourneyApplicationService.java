package com.easysubway.journey.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class JourneyApplicationService {
	private final ActiveJourneySnapshotPort activeSnapshotPort;
	private final JourneyRealtimePort realtimePort;
	private final JourneyRaptorPort raptorPort;
	private final Clock clock;

	public JourneyApplicationService(
		ActiveJourneySnapshotPort activeSnapshotPort,
		JourneyRealtimePort realtimePort,
		JourneyRaptorPort raptorPort,
		Clock clock
	) {
		this.activeSnapshotPort = Objects.requireNonNull(activeSnapshotPort, "activeSnapshotPort");
		this.realtimePort = Objects.requireNonNull(realtimePort, "realtimePort");
		this.raptorPort = Objects.requireNonNull(raptorPort, "raptorPort");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public JourneyExecutionResult execute(JourneyRequest request) {
		Objects.requireNonNull(request, "request");
		Instant capturedInstant = clock.instant();
		Instant effectiveInstant = request.departure() instanceof JourneyRequest.Departure.Scheduled scheduled
			? scheduled.requestedAt()
			: capturedInstant;
		if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);

		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot;
		try {
			snapshot = activeSnapshotPort.requireActive(effectiveInstant);
		} catch (RuntimeException exception) {
			if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
			return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
		}
		if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
		if (snapshot == null) return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
		if (!snapshot.fresh() || !isCurrent(snapshot.validUntil(), capturedInstant, effectiveInstant)) {
			return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_STALE);
		}

		JourneyRealtimePort.RealtimeObservation realtime = null;
		if (request.timePolicy() == JourneyRequest.TimePolicy.REALTIME_REQUIRED) {
			try {
				realtime = realtimePort.requireFresh(request, snapshot, effectiveInstant);
			} catch (RuntimeException exception) {
				if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
				return failure(JourneyExecutionFailure.Reason.REALTIME_UNAVAILABLE);
			}
			if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
			if (realtime == null) return failure(JourneyExecutionFailure.Reason.REALTIME_UNAVAILABLE);
			if (!realtime.fresh() || !isCurrent(realtime.validUntil(), capturedInstant, effectiveInstant)) {
				return failure(JourneyExecutionFailure.Reason.REALTIME_STALE);
			}
			if (!snapshot.routeBundleSha256().equals(realtime.routeBundleSha256())) {
				return failure(JourneyExecutionFailure.Reason.REALTIME_IDENTITY_MISMATCH);
			}
		}

		JourneyRaptorPort.PlanResult plan;
		try {
			plan = raptorPort.plan(request, snapshot, effectiveInstant, realtime);
		} catch (RuntimeException exception) {
			if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		}
		if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
		if (plan == null) return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		if (plan.candidates().isEmpty()) return failure(JourneyExecutionFailure.Reason.NO_ROUTE);

		try {
			Instant validUntil = realtime == null || snapshot.validUntil().isBefore(realtime.validUntil())
				? snapshot.validUntil() : realtime.validUntil();
			var result = new JourneyExecutionResult.Success(
				request.requestId(),
				plan.queryId(),
				capturedInstant,
				validUntil,
				effectiveInstant,
				effectiveInstant.atZone(JourneyExecutionResult.SERVICE_ZONE).toLocalDate(),
				new JourneyExecutionResult.SourceIdentity(
					snapshot.routeBundleId(),
					snapshot.routeBundleSha256(),
					snapshot.timetableSnapshotId(),
					snapshot.accessibilitySnapshotId(),
					realtime == null ? null : realtime.identity()
				),
				new JourneyExecutionResult.RequestPolicy(
					request.timePolicy(),
					request.mobilityProfile(),
					request.constraintMode(),
					request.maxTransfers(),
					request.alternativeCount()
				),
				plan.candidates()
			);
			return request.isCancelled() ? failure(JourneyExecutionFailure.Reason.CANCELLED) : result;
		} catch (RuntimeException exception) {
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		}
	}

	private static boolean isCurrent(Instant validUntil, Instant capturedInstant, Instant effectiveInstant) {
		return validUntil.isAfter(capturedInstant) && validUntil.isAfter(effectiveInstant);
	}

	private static JourneyExecutionFailure failure(JourneyExecutionFailure.Reason reason) {
		return new JourneyExecutionFailure(reason);
	}
}
