package com.easysubway.transit.domain;

public enum DataQualityLevel {
	LEVEL_1("Level 1", "기본 정보만 있음", DataQualitySeverity.NEEDS_BASE_DATA, 40, "기본 정보만 있음"),
	LEVEL_2("Level 2", "시설 정보 확인됨", DataQualitySeverity.NEEDS_ROUTE_VERIFICATION, 60, "쉬운 경로 검증 필요"),
	LEVEL_3("Level 3", "쉬운 길 안내 가능", DataQualitySeverity.NEEDS_LIVE_STATUS, 80, "고장·공사 반영 필요"),
	LEVEL_4("Level 4", "고장·공사 반영됨", DataQualitySeverity.VERIFIED, 100, "");

	private final String label;
	private final String description;
	private final DataQualitySeverity severity;
	private final int accessibilityScore;
	private final String scoreReason;

	DataQualityLevel(
		String label,
		String description,
		DataQualitySeverity severity,
		int accessibilityScore,
		String scoreReason
	) {
		this.label = label;
		this.description = description;
		this.severity = severity;
		this.accessibilityScore = accessibilityScore;
		this.scoreReason = scoreReason;
	}

	public String label() {
		return label;
	}

	public String description() {
		return description;
	}

	public DataQualitySeverity severity() {
		return severity;
	}

	public int accessibilityScore() {
		return accessibilityScore;
	}

	public String scoreReason() {
		return scoreReason;
	}

	public int searchSortPriority() {
		return -accessibilityScore;
	}

	public enum DataQualitySeverity {
		NEEDS_BASE_DATA,
		NEEDS_ROUTE_VERIFICATION,
		NEEDS_LIVE_STATUS,
		VERIFIED
	}
}
