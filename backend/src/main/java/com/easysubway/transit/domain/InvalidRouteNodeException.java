package com.easysubway.transit.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidRouteNodeException extends InvalidRequestException {

	public InvalidRouteNodeException(String message) {
		super(message);
	}
}
