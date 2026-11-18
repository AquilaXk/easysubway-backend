package com.easysubway.journey.application;

public final class FacilityStatusUnavailableException extends RuntimeException {
	public FacilityStatusUnavailableException(String message) {
		super(message);
	}

	public FacilityStatusUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
