package com.easysubway.profile.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidMobilityProfileException extends InvalidRequestException {

	public InvalidMobilityProfileException(String message) {
		super(message);
	}
}
