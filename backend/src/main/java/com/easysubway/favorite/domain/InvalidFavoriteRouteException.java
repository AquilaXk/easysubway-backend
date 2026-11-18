package com.easysubway.favorite.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidFavoriteRouteException extends InvalidRequestException {

	public InvalidFavoriteRouteException(String message) {
		super(message);
	}
}
