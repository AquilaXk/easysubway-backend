package com.easysubway.journey.application;

import java.util.Objects;

public sealed interface JourneyExecutionDisposition
	permits JourneyExecutionDisposition.PublicFailure, JourneyExecutionDisposition.Cancelled {

	static JourneyExecutionDisposition from(JourneyExecutionFailure failure) {
		Objects.requireNonNull(failure, "failure");

		return switch (failure.reason()) {
			case ACTIVE_SNAPSHOT_UNAVAILABLE -> publicFailure(503, MachineCode.ROUTING_BUNDLE_UNAVAILABLE);
			case ACTIVE_SNAPSHOT_STALE -> publicFailure(503, MachineCode.ROUTING_BUNDLE_STALE);
			case REALTIME_UNAVAILABLE, REALTIME_STALE -> publicFailure(503,
				MachineCode.REALTIME_REQUIRED_UNAVAILABLE);
			case REALTIME_IDENTITY_MISMATCH -> publicFailure(503, MachineCode.ROUTING_IDENTITY_MISMATCH);
			case RAPTOR_FAILED -> publicFailure(503, MachineCode.ROUTE_SERVICE_UNAVAILABLE);
			case NO_ROUTE -> publicFailure(422, MachineCode.ROUTE_NOT_FOUND);
			case CANCELLED -> new Cancelled();
		};
	}

	private static PublicFailure publicFailure(int httpStatus, MachineCode machineCode) {
		return new PublicFailure(httpStatus, machineCode, false);
	}

	record PublicFailure(int httpStatus, MachineCode machineCode, boolean retryable)
		implements JourneyExecutionDisposition {

		public PublicFailure {
			machineCode = Objects.requireNonNull(machineCode, "machineCode");
			if (retryable) {
				throw new IllegalArgumentException("retryable must be false");
			}
			int expectedHttpStatus = switch (machineCode) {
				case ROUTE_NOT_FOUND -> 422;
				case ROUTING_BUNDLE_UNAVAILABLE, ROUTING_BUNDLE_STALE, REALTIME_REQUIRED_UNAVAILABLE,
					ROUTING_IDENTITY_MISMATCH, ROUTE_SERVICE_UNAVAILABLE -> 503;
			};
			if (httpStatus != expectedHttpStatus) {
				throw new IllegalArgumentException("httpStatus does not match machineCode");
			}
		}
	}

	record Cancelled() implements JourneyExecutionDisposition {
	}

	enum MachineCode {
		ROUTING_BUNDLE_UNAVAILABLE,
		ROUTING_BUNDLE_STALE,
		REALTIME_REQUIRED_UNAVAILABLE,
		ROUTING_IDENTITY_MISMATCH,
		ROUTE_SERVICE_UNAVAILABLE,
		ROUTE_NOT_FOUND
	}
}
