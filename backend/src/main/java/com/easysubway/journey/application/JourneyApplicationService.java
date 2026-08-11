package com.easysubway.journey.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
		Instant effectiveInstant = clock.instant();
		if (request.isCancelled()) {
			return failure(JourneyExecutionFailure.Reason.CANCELLED);
		}

		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot;
		try {
			snapshot = activeSnapshotPort.requireActive(effectiveInstant);
		} catch (RuntimeException exception) {
			return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
		}
		if (request.isCancelled()) {
			return failure(JourneyExecutionFailure.Reason.CANCELLED);
		}
		if (snapshot == null) {
			return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
		}
		if (!snapshot.fresh()) {
			return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_STALE);
		}

		JourneyRealtimePort.RealtimeObservation realtime = null;
		if (request.mode() == JourneyRequest.Mode.REALTIME_REQUIRED) {
			try {
				realtime = realtimePort.requireFresh(request, snapshot, effectiveInstant);
			} catch (RuntimeException exception) {
				return failure(JourneyExecutionFailure.Reason.REALTIME_UNAVAILABLE);
			}
			if (request.isCancelled()) {
				return failure(JourneyExecutionFailure.Reason.CANCELLED);
			}
			if (realtime == null) {
				return failure(JourneyExecutionFailure.Reason.REALTIME_UNAVAILABLE);
			}
			if (!realtime.fresh()) {
				return failure(JourneyExecutionFailure.Reason.REALTIME_STALE);
			}
			if (!snapshot.bundleIdentity().equals(realtime.bundleIdentity())) {
				return failure(JourneyExecutionFailure.Reason.REALTIME_IDENTITY_MISMATCH);
			}
		}

		List<String> candidates;
		try {
			candidates = raptorPort.plan(request, snapshot, effectiveInstant, realtime);
		} catch (RuntimeException exception) {
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		}
		if (request.isCancelled()) {
			return failure(JourneyExecutionFailure.Reason.CANCELLED);
		}
		if (candidates == null) {
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		}
		if (candidates.isEmpty()) {
			return failure(JourneyExecutionFailure.Reason.NO_ROUTE);
		}
		try {
			return new JourneyExecutionResult.Success(
				JourneyExecutionResult.Source.SERVER_TIMETABLE_RAPTOR,
				snapshot.bundleIdentity(),
				realtime == null ? null : realtime.identity(),
				candidates
			);
		} catch (RuntimeException exception) {
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		}
	}

	private static JourneyExecutionFailure failure(JourneyExecutionFailure.Reason reason) {
		return new JourneyExecutionFailure(reason);
	}
}
