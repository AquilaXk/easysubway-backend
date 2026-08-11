package com.easysubway.journey.application;

import java.util.Objects;

public final class JourneySessionException extends RuntimeException {
	private final Kind kind;

	public JourneySessionException(Kind kind) {
		super(Objects.requireNonNull(kind, "kind").machineCode());
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	public int httpStatus() {
		return kind.httpStatus();
	}

	public String machineCode() {
		return kind.machineCode();
	}

	public enum Kind {
		INVALID_REQUEST(400, "INVALID_JOURNEY_SESSION_REQUEST"),
		ATTESTATION_REJECTED(403, "ROUTE_SESSION_ATTESTATION_REJECTED"),
		ATTESTATION_UNAVAILABLE(503, "ROUTE_SESSION_ATTESTATION_UNAVAILABLE"),
		SESSION_REQUIRED(401, "ROUTE_SESSION_REQUIRED");

		private final int httpStatus;
		private final String machineCode;

		Kind(int httpStatus, String machineCode) {
			this.httpStatus = httpStatus;
			this.machineCode = machineCode;
		}

		public int httpStatus() {
			return httpStatus;
		}

		public String machineCode() {
			return machineCode;
		}
	}
}
