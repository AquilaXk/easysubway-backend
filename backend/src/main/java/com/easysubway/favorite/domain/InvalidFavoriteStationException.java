package com.easysubway.favorite.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidFavoriteStationException extends InvalidRequestException {

	public InvalidFavoriteStationException(String message) {
		super(message);
	}
}
