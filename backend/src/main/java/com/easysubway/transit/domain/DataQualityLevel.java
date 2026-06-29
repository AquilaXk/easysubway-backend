package com.easysubway.transit.domain;

public enum DataQualityLevel {
	LEVEL_1("정보 확인 중", "일부 정보는 확인 중이에요", DataQualitySeverity.NEEDS_BASE_DATA, 40, "일부 정보는 확인 중이에요"),
	LEVEL_2("시설 정보 있음", "시설 정보를 함께 볼 수 있어요", DataQualitySeverity.NEEDS_ROUTE_VERIFICATION, 60, "쉬운 길 확인이 더 필요해요"),
	LEVEL_3("쉬운 길 안내 가능", "쉬운 길 안내를 볼 수 있어요", DataQualitySeverity.NEEDS_LIVE_STATUS, 80, "고장·공사 소식 확인이 필요해요"),
	LEVEL_4("고장·공사 반영", "고장·공사 소식이 반영됐어요", DataQualitySeverity.VERIFIED, 100, "");

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
