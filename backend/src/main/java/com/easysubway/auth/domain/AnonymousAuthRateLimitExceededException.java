package com.easysubway.auth.domain;

public class AnonymousAuthRateLimitExceededException extends RuntimeException {

	public AnonymousAuthRateLimitExceededException(String message) {
		super(message);
	}
}
