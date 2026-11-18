package com.easysubway.transit.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidStationLayoutSourceException extends InvalidRequestException {

	public InvalidStationLayoutSourceException(String message) {
		super(message);
	}
}
