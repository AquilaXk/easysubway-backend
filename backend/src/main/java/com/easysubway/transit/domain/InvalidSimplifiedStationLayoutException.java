package com.easysubway.transit.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidSimplifiedStationLayoutException extends InvalidRequestException {

	public InvalidSimplifiedStationLayoutException(String message) {
		super(message);
	}
}
