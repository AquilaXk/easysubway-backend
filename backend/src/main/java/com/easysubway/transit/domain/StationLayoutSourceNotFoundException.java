package com.easysubway.transit.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class StationLayoutSourceNotFoundException extends ResourceNotFoundException {

	public StationLayoutSourceNotFoundException() {
		super("구조도 기준 자료 정보를 찾을 수 없습니다.");
	}
}
