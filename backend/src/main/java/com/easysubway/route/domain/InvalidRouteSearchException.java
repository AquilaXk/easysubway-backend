package com.easysubway.route.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidRouteSearchException extends InvalidRequestException {

	public InvalidRouteSearchException(String message) {
		super(message);
	}
}
