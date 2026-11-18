package com.easysubway.field.domain;

public enum FieldVerificationItemType {
	EXIT("출구"),
	ELEVATOR("엘리베이터"),
	ESCALATOR("에스컬레이터"),
	RESTROOM("화장실"),
	PLATFORM_TRANSFER("승강장/환승 동선");

	private final String label;

	FieldVerificationItemType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
