package com.easysubway.transit.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidAccessibilityFacilityException extends InvalidRequestException {

	public InvalidAccessibilityFacilityException(String message) {
		super(message);
	}
}
