package com.easysubway.route.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidRouteFeedbackException extends InvalidRequestException {

	public InvalidRouteFeedbackException(String message) {
		super(message);
	}
}
