package com.easysubway.favorite.domain;

import com.easysubway.common.error.InvalidRequestException;

public class InvalidFavoriteFacilityException extends InvalidRequestException {

	public InvalidFavoriteFacilityException(String message) {
		super(message);
	}
}
