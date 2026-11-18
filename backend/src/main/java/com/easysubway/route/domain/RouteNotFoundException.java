package com.easysubway.route.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class RouteNotFoundException extends ResourceNotFoundException {

	public RouteNotFoundException() {
		super("연결 가능한 경로를 찾을 수 없습니다.");
	}
}
