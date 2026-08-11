package com.easysubway.journey.application;

import java.util.Objects;

public record JourneyExecutionFailure(Reason reason) implements JourneyExecutionResult {
	public JourneyExecutionFailure {
		reason = Objects.requireNonNull(reason, "reason");
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
