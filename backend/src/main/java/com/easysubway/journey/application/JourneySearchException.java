package com.easysubway.journey.application;

import java.util.Objects;

public final class JourneySearchException extends RuntimeException {

	private final Code code;

	public JourneySearchException(Code code) {
		super(Objects.requireNonNull(code, "code").name());
		this.code = code;
	}

	public Code code() {
		return code;
	}

	public enum Code {
		INVALID_JOURNEY_REQUEST,
		STATION_NOT_FOUND,
		ROUTE_NOT_FOUND,
		ACCESSIBILITY_CONSTRAINT_UNSATISFIED,
		ROUTING_BUNDLE_UNAVAILABLE,
		ROUTING_BUNDLE_STALE,
		TIMETABLE_UNAVAILABLE,
		TIMETABLE_STALE,
		REALTIME_REQUIRED_UNAVAILABLE,
		ROUTING_IDENTITY_MISMATCH,
		ROUTE_SERVICE_UNAVAILABLE,
		JOURNEY_SEARCH_TIMEOUT
	}
}
