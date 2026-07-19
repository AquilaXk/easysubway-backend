package com.easysubway.transit.domain;

public enum DataSourceType {
	OFFICIAL_API("공식 API"),
	OFFICIAL_FILE("공식 자료"),
	OPERATOR_PAGE("운영자 안내"),
	USER_REPORT("사용자 제보"),
	ADMIN_VERIFIED("관리자 확인"),
	PARTNER_FEED("제휴기관 안내");

	private final String label;

	DataSourceType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
