package com.easysubway.transit.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidRouteEdgeException extends InvalidRequestException {

	public InvalidRouteEdgeException(String message) {
		super(message);
	}
}
