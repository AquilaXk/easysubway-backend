package com.easysubway.transit.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class SimplifiedStationLayoutNotFoundException extends ResourceNotFoundException {

	public SimplifiedStationLayoutNotFoundException() {
		super("역 구조도 정보를 찾을 수 없습니다.");
	}
}
