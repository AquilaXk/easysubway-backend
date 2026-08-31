package com.easysubway.journey.application;

import java.util.Objects;

public record JourneyExecutionFailure(Reason reason, JourneyExecutionResult.ExecutionObservation executionObservation)
	implements JourneyExecutionResult {
	public JourneyExecutionFailure {
		reason = Objects.requireNonNull(reason, "reason");
		if (executionObservation != null && (reason != Reason.NO_ROUTE
			|| executionObservation.activeReadinessIdentity() == null
			|| executionObservation.activeServingIdentity().status()
				!= JourneyExecutionResult.ActiveServingIdentity.Status.OBSERVED
			|| executionObservation.boundaryObservation().status()
				!= JourneyExecutionResult.BoundaryObservation.Status.OBSERVED)) {
			throw new IllegalArgumentException("only an observed NO_ROUTE may include an execution observation");
		}
	}

	public JourneyExecutionFailure(Reason reason) {
		this(reason, null);
	}

	public enum Reason {
		ACTIVE_SNAPSHOT_UNAVAILABLE,
		ACTIVE_SNAPSHOT_STALE,
		REALTIME_UNAVAILABLE,
		REALTIME_STALE,
		REALTIME_IDENTITY_MISMATCH,
		CANCELLED,
		RAPTOR_FAILED,
		NO_ROUTE
	}
}
