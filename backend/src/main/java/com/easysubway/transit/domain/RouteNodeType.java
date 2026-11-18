package com.easysubway.transit.domain;

public enum RouteNodeType {
	ENTRANCE("출입구"),
	EXIT("출구"),
	CONCOURSE("대합실"),
	FAREGATE("개찰구"),
	PLATFORM("승강장"),
	TRANSFER_PASSAGE("환승통로"),
	ELEVATOR("엘리베이터"),
	ESCALATOR("에스컬레이터"),
	STAIR("계단"),
	RAMP("경사로"),
	TOILET("화장실"),
	ACCESSIBLE_TOILET("장애인 화장실"),
	NURSING_ROOM("수유실"),
	CUSTOMER_CENTER("고객센터");

	private final String label;

	RouteNodeType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
