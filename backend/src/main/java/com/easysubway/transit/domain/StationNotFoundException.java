package com.easysubway.transit.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class StationNotFoundException extends ResourceNotFoundException {

	private static final String MESSAGE = "역 정보를 찾을 수 없습니다.";

	public StationNotFoundException() {
		super(MESSAGE);
	}
}
