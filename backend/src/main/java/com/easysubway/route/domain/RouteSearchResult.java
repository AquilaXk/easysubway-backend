package com.easysubway.route.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.easysubway.profile.domain.MobilityType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public record RouteSearchResult(
	String routeSearchId,
	String originStationId,
	String originStationName,
	String destinationStationId,
	String destinationStationName,
	MobilityType mobilityType,
	RouteSearchStatus status,
	String lineId,
	String lineName,
	int score,
	List<RouteStep> steps,
	List<RouteWarning> warnings,
	List<String> blockedReasons,
	LocalDateTime createdAt
) {

	@JsonProperty("recommendationReasons")
	public List<String> recommendationReasons() {
		if (status != RouteSearchStatus.FOUND) {
			return List.of();
		}
		List<String> reasons = new ArrayList<>();
		if (hasStepFreeAccessibilityStep()) {
			reasons.add("엘리베이터 동선을 우선했어요");
			reasons.add("계단 없는 출구를 확인했어요");
		}
		if (hasStairWarning()) {
			reasons.add("계단 포함 구간을 미리 표시했어요");
		}
		reasons.add(mobilityReason());
		return List.copyOf(reasons.stream().distinct().limit(3).toList());
	}

	private boolean hasStepFreeAccessibilityStep() {
		return steps.stream()
			.filter(RouteStep::requiresAccessibilityCheck)
			.anyMatch(step -> !step.includesStairs());
	}

	private boolean hasStairWarning() {
		return warnings.stream()
			.anyMatch(warning -> warning.code() == RouteWarningCode.STAIR_ONLY_ACCESS);
	}

	private String mobilityReason() {
		return switch (mobilityType) {
			case WHEELCHAIR -> "휠체어 이동에 맞춰 계단을 피했어요";
			case STROLLER -> "유모차 이동에 맞춰 넓은 동선을 확인했어요";
			case SENIOR -> "천천히 이동하기 쉬운 동선을 확인했어요";
			case PREGNANT -> "짧게 걷는 동선을 우선했어요";
			case TEMPORARY_INJURY -> "계단 부담이 적은 동선을 확인했어요";
			case LUGGAGE -> "큰 짐을 들고 이동하기 쉬운 동선을 확인했어요";
		};
	}
}
