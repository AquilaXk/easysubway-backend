package com.easysubway.transit.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class RouteEdgeNotFoundException extends ResourceNotFoundException {

	public RouteEdgeNotFoundException() {
		super("내부 이동 간선 정보를 찾을 수 없습니다.");
	}
}
