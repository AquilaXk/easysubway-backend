package com.easysubway.route.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class RouteSearchNotFoundException extends ResourceNotFoundException {

	public RouteSearchNotFoundException() {
		super("경로 검색 결과를 찾을 수 없습니다.");
	}
}
