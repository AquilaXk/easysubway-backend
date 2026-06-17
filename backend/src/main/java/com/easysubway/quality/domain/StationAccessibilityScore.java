package com.easysubway.quality.domain;

import java.util.List;

public record StationAccessibilityScore(
	String stationId,
	String stationName,
	String region,
	int score,
	List<String> reasons
) {

	public StationAccessibilityScore {
		if (stationId == null || stationId.isBlank()) {
			throw new IllegalArgumentException("역 식별자가 필요합니다.");
		}
		if (stationName == null || stationName.isBlank()) {
			throw new IllegalArgumentException("역 이름이 필요합니다.");
		}
		if (region == null || region.isBlank()) {
			region = "지역 미확인";
		}
		if (score < 0 || score > 100) {
			throw new IllegalArgumentException("역별 접근성 점수는 0부터 100 사이여야 합니다.");
		}
		reasons = List.copyOf(reasons);
	}
}
