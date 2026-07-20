package com.easysubway.transit.domain;

public enum RouteEdgeType {
	WALK("도보"),
	WALKWAY("보행통로"),
	STAIR("계단"),
	ELEVATOR("엘리베이터"),
	ESCALATOR("에스컬레이터"),
	RAMP("경사로"),
	TRAIN("열차"),
	RIDE("승차"),
	TRANSFER("환승"),
	IN_STATION_TRANSFER("역내 환승"),
	OUT_OF_STATION_TRANSFER("역외 환승"),
	ENTRY("진입"),
	EXIT("진출"),
	FACILITY_CONNECTOR("시설 연결"),
	LEGACY_TRANSFER("환승(구버전)");

	private final String label;

	RouteEdgeType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
