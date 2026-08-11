package com.easysubway.journey.application;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public record JourneyRequest(
	String requestIdentity,
	String originStationIdentity,
	String destinationStationIdentity,
	Mode mode,
	BooleanSupplier cancellationSignal
) {
	public JourneyRequest {
		requestIdentity = requireText(requestIdentity, "requestIdentity");
		originStationIdentity = requireText(originStationIdentity, "originStationIdentity");
		destinationStationIdentity = requireText(destinationStationIdentity, "destinationStationIdentity");
		mode = Objects.requireNonNull(mode, "mode");
		cancellationSignal = Objects.requireNonNull(cancellationSignal, "cancellationSignal");
	}

	public boolean isCancelled() {
		return cancellationSignal.getAsBoolean();
	}

	public enum Mode {
		TIMETABLE_REQUIRED,
		REALTIME_REQUIRED
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
