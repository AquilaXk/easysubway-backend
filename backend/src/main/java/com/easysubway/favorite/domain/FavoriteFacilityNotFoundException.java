package com.easysubway.favorite.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class FavoriteFacilityNotFoundException extends ResourceNotFoundException {

	private static final String MESSAGE = "시설 정보를 찾을 수 없습니다.";

	public FavoriteFacilityNotFoundException() {
		super(MESSAGE);
	}
}
