package com.easysubway.transit.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidStationSearchException extends InvalidRequestException {

	public InvalidStationSearchException(String message) {
		super(message);
	}
}
