package com.easysubway.transit.adapter.out.persistence;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class TransitDataAccessException extends RuntimeException {

	public TransitDataAccessException(String message) {
		super(message);
	}

	public TransitDataAccessException(String message, Throwable cause) {
		super(message, cause);
	}
}
