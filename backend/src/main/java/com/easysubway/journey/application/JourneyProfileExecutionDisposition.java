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
			case ACTIVE_SNAPSHOT_UNAVAILABLE -> publicFailure(503, MachineCode.ROUTING_BUNDLE_UNAVAILABLE);
			case ACTIVE_SNAPSHOT_STALE -> publicFailure(503, MachineCode.ROUTING_BUNDLE_STALE);
			case REALTIME_UNAVAILABLE -> publicFailure(503, MachineCode.REALTIME_REQUIRED_UNAVAILABLE);
			case TEMPORAL_QUERY_TOO_COMPLEX -> publicFailure(422, MachineCode.TEMPORAL_QUERY_TOO_COMPLEX);
			case RAPTOR_FRONTIER_CAPACITY_EXCEEDED -> publicFailure(503,
				MachineCode.RAPTOR_FRONTIER_CAPACITY_EXCEEDED);
			case NO_SERVICE_IN_DEPARTURE_WINDOW -> publicFailure(422, MachineCode.NO_SERVICE_IN_DEPARTURE_WINDOW);
			case NO_ROUTE_ARRIVING_BY_DEADLINE -> publicFailure(422,
				MachineCode.NO_ROUTE_ARRIVING_BY_DEADLINE);
			case NO_LAST_CONNECTION -> publicFailure(422, MachineCode.NO_LAST_CONNECTION);
			case CANCELLED -> new Cancelled();
			case RAPTOR_FAILED ->
				new InternalFailure(failure.reason());
		};
	}

	private static PublicFailure publicFailure(int status, MachineCode machineCode) {
		return new PublicFailure(status, machineCode, false);
	}

	record PublicFailure(int httpStatus, MachineCode machineCode, boolean retryable)
		implements JourneyProfileExecutionDisposition {
		public PublicFailure {
			machineCode = Objects.requireNonNull(machineCode, "machineCode");
			if (httpStatus != 422 && httpStatus != 503) throw new IllegalArgumentException("profile failure status is closed");
			boolean unprocessable = switch (machineCode) {
				case TEMPORAL_QUERY_TOO_COMPLEX, NO_SERVICE_IN_DEPARTURE_WINDOW,
					NO_ROUTE_ARRIVING_BY_DEADLINE, NO_LAST_CONNECTION -> true;
				default -> false;
			};
			if (unprocessable && httpStatus != 422 || !unprocessable && httpStatus != 503) {
				throw new IllegalArgumentException("profile failure status must match machine code");
			}
			if (retryable) throw new IllegalArgumentException("retryable must be false");
		}
	}

	record Cancelled() implements JourneyProfileExecutionDisposition {
	}

	record InternalFailure(JourneyProfileExecutionResult.Reason reason)
		implements JourneyProfileExecutionDisposition {
		public InternalFailure {
			if (reason != JourneyProfileExecutionResult.Reason.RAPTOR_FAILED) {
				throw new IllegalArgumentException("only unclassified profile failures are allowed");
			}
		}
	}

	enum MachineCode {
		ROUTING_BUNDLE_UNAVAILABLE,
		ROUTING_BUNDLE_STALE,
		REALTIME_REQUIRED_UNAVAILABLE,
		TEMPORAL_QUERY_TOO_COMPLEX,
		RAPTOR_FRONTIER_CAPACITY_EXCEEDED,
		NO_SERVICE_IN_DEPARTURE_WINDOW,
		NO_ROUTE_ARRIVING_BY_DEADLINE,
		NO_LAST_CONNECTION
	}
}
