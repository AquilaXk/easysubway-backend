package com.easysubway.auth.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidAnonymousAuthException extends InvalidRequestException {

	public InvalidAnonymousAuthException(String message) {
		super(message);
	}
}
