package com.easysubway.transit.domain;

import com.easysubway.common.error.ResourceNotFoundException;

public class RouteNodeNotFoundException extends ResourceNotFoundException {

	public RouteNodeNotFoundException() {
		super("내부 이동 노드 정보를 찾을 수 없습니다.");
	}
}
