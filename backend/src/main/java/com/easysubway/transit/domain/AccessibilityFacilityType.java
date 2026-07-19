package com.easysubway.transit.domain;

public enum AccessibilityFacilityType {
	ELEVATOR("엘리베이터"),
	ESCALATOR("에스컬레이터"),
	WHEELCHAIR_LIFT("휠체어 리프트"),
	RAMP("경사로"),
	ACCESSIBLE_TOILET("장애인 화장실"),
	TOILET("화장실"),
	NURSING_ROOM("수유실"),
	CUSTOMER_CENTER("고객센터");

	private final String label;

	AccessibilityFacilityType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
