package com.easysubway.journey.application;

import java.util.Objects;

/** Closed public/internal classification for application failures only. */
public sealed interface JourneyProfileExecutionDisposition
	permits JourneyProfileExecutionDisposition.PublicFailure,
	JourneyProfileExecutionDisposition.Cancelled,
	JourneyProfileExecutionDisposition.InternalFailure {

	static JourneyProfileExecutionDisposition from(JourneyProfileExecutionResult.Failure failure) {
		Objects.requireNonNull(failure, "failure");
		return switch (failure.reason()) {
			case ACTIVE_SNAPSHOT_UNAVAILABLE -> publicFailure(MachineCode.ROUTING_BUNDLE_UNAVAILABLE);
			case ACTIVE_SNAPSHOT_STALE -> publicFailure(MachineCode.ROUTING_BUNDLE_STALE);
			case REALTIME_UNAVAILABLE -> publicFailure(MachineCode.REALTIME_REQUIRED_UNAVAILABLE);
			case CANCELLED -> new Cancelled();
			case RAPTOR_FAILED -> new InternalFailure(failure.reason());
		};
	}

	private static PublicFailure publicFailure(MachineCode machineCode) {
		return new PublicFailure(503, machineCode, false);
	}

	record PublicFailure(int httpStatus, MachineCode machineCode, boolean retryable)
		implements JourneyProfileExecutionDisposition {
		public PublicFailure {
			machineCode = Objects.requireNonNull(machineCode, "machineCode");
			if (httpStatus != 503) throw new IllegalArgumentException("profile failure status must be 503");
			if (retryable) throw new IllegalArgumentException("retryable must be false");
		}
	}

	record Cancelled() implements JourneyProfileExecutionDisposition {
	}

	record InternalFailure(JourneyProfileExecutionResult.Reason reason)
		implements JourneyProfileExecutionDisposition {
		public InternalFailure {
			if (reason != JourneyProfileExecutionResult.Reason.RAPTOR_FAILED) {
				throw new IllegalArgumentException("only RAPTOR_FAILED is an unclassified internal failure");
			}
		}
	}

	enum MachineCode {
		ROUTING_BUNDLE_UNAVAILABLE,
		ROUTING_BUNDLE_STALE,
		REALTIME_REQUIRED_UNAVAILABLE
	}
}
