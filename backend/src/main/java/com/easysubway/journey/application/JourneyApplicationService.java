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
		var measurement = new JourneyRequestMeasurement(request.requestId());

		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot;
		try {
			snapshot = activeSnapshotPort.requireActive(request, effectiveInstant, measurement);
		} catch (RuntimeException exception) {
			if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
			return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
		}
		if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
		if (snapshot == null) return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_UNAVAILABLE);
		if (!snapshot.fresh() || !isCurrent(snapshot.validUntil(), capturedInstant, effectiveInstant)) {
			return failure(JourneyExecutionFailure.Reason.ACTIVE_SNAPSHOT_STALE);
		}
		if (request.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED
			&& snapshot.boundaryReceipt().status()
				!= ActiveJourneySnapshotPort.SnapshotBoundaryReceipt.Status.OBSERVED) {
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
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
			plan = raptorPort.plan(request, snapshot, effectiveInstant, realtime, measurement);
		} catch (RuntimeException exception) {
			if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		}
		if (request.isCancelled()) return failure(JourneyExecutionFailure.Reason.CANCELLED);
		if (plan == null) return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		if (request.timePolicy() == JourneyRequest.TimePolicy.TIMETABLE_REQUIRED
			&& plan.boundaryReceipt().status() != JourneyRaptorPort.RouteBoundaryReceipt.Status.OBSERVED) {
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		}
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
				ServiceDayResolver.resolve(effectiveInstant).serviceDate(),
				snapshot.generation(),
				plan.scanMetrics(),
				new JourneyExecutionResult.SourceIdentity(
					snapshot.routeBundleId(),
					snapshot.routeBundleSha256(),
					snapshot.timetableSnapshotId(),
					snapshot.accessibilitySnapshotId(),
					realtime == null ? null : realtime.identity()
				),
				new JourneyExecutionResult.RequestPolicy(
					request.timePolicy(),
					request.walkingPace(),
					request.mobilityProfile(),
					request.constraintMode(),
					request.maxTransfers(),
					request.alternativeCount()
				),
				plan.candidates(),
				boundaryObservation(request, snapshot, plan),
				requestMeasurement(request, snapshot, plan, measurement)
			);
			return request.isCancelled() ? failure(JourneyExecutionFailure.Reason.CANCELLED) : result;
		} catch (RuntimeException exception) {
			return failure(JourneyExecutionFailure.Reason.RAPTOR_FAILED);
		}
	}

	private static JourneyExecutionResult.RequestMeasurement requestMeasurement(
		JourneyRequest request,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		JourneyRaptorPort.PlanResult plan,
		JourneyRequestMeasurement measurement
	) {
		if (request.timePolicy() != JourneyRequest.TimePolicy.TIMETABLE_REQUIRED) {
			return JourneyExecutionResult.RequestMeasurement.unobservable();
		}
		var snapshotMeasurement = snapshot.measurementReceipt();
		var routeMeasurement = plan.measurementReceipt();
		var recorded = measurement.complete(request, snapshot);
		if (snapshotMeasurement.status() != ActiveJourneySnapshotPort.SnapshotMeasurementReceipt.Status.OBSERVED
			|| routeMeasurement.status() != JourneyRaptorPort.RouteMeasurementReceipt.Status.OBSERVED
			|| recorded.status() != JourneyExecutionResult.RequestMeasurement.Status.OBSERVED
			|| !snapshotMeasurement.identity().equals(routeMeasurement.identity())
			|| !recorded.identity().equals(snapshotMeasurement.identity())) {
			return JourneyExecutionResult.RequestMeasurement.unobservable();
		}
		var identity = snapshotMeasurement.identity();
		var serving = identity.activeServingIdentity();
		var servingEvidence = snapshot.servingEvidence();
		if (!request.requestId().equals(identity.requestId())
			|| !snapshot.routeBundleSha256().equals(identity.routeBundleSha256())
			|| snapshot.generation() != identity.generation()
			|| servingEvidence.status() != ActiveJourneySnapshotPort.ActiveServingEvidence.Status.OBSERVED
			|| !servingEvidence.descriptorSha256().equals(serving.descriptorSha256())
			|| !servingEvidence.publicationReceiptSha256().equals(serving.receiptSha256())) {
			return JourneyExecutionResult.RequestMeasurement.unobservable();
		}
		var boundary = JourneyExecutionResult.BoundaryObservation.observed(
			snapshotMeasurement.providerCalls(), snapshotMeasurement.cacheHits(),
			snapshotMeasurement.staleArtifactUses(), routeMeasurement.fallbackUses());
		return boundary.equals(recorded.boundaryObservation()) ? recorded
			: JourneyExecutionResult.RequestMeasurement.unobservable();
	}

	private static JourneyExecutionResult.BoundaryObservation boundaryObservation(
		JourneyRequest request,
		ActiveJourneySnapshotPort.ActiveJourneySnapshot snapshot,
		JourneyRaptorPort.PlanResult plan
	) {
		if (request.timePolicy() != JourneyRequest.TimePolicy.TIMETABLE_REQUIRED) {
			return JourneyExecutionResult.BoundaryObservation.unobservable();
		}
		return JourneyExecutionResult.BoundaryObservation.observed(
			0,
			snapshot.boundaryReceipt().cacheHits(),
			snapshot.boundaryReceipt().staleArtifactUses(),
			plan.boundaryReceipt().fallbackUses());
	}

	private static boolean isCurrent(Instant validUntil, Instant capturedInstant, Instant effectiveInstant) {
		return validUntil.isAfter(capturedInstant) && validUntil.isAfter(effectiveInstant);
	}

	private static JourneyExecutionFailure failure(JourneyExecutionFailure.Reason reason) {
		return new JourneyExecutionFailure(reason);
	}
}
