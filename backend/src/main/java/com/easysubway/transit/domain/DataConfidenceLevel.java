package com.easysubway.transit.domain;

public enum DataConfidenceLevel {
	HIGH("최근 확인된 정보"),
	MEDIUM("일부 확인된 정보"),
	LOW("확인이 더 필요한 정보"),
	NEEDS_VERIFICATION("확인이 더 필요해요");

	private final String label;

	DataConfidenceLevel(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
