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
			reasons.add("선택된 경로에서 접근성 확인이 필요한 구간을 표시합니다.");
			reasons.add("출구와 시설 상태는 현장 안내를 함께 확인해 주세요.");
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
			case WHEELCHAIR -> "휠체어 이동 조건을 반영해 계단 부담이 낮은 경로를 우선했습니다.";
			case STROLLER -> "유모차 이동 조건을 반영해 이동 부담이 낮은 경로를 우선했습니다.";
			case SENIOR -> "천천히 이동하기 쉬운 조건을 반영해 이동 부담이 낮은 경로를 우선했습니다.";
			case PREGNANT -> "짧게 걷는 동선을 우선했어요";
			case TEMPORARY_INJURY -> "계단 부담이 적은 조건을 반영해 이동 부담이 낮은 경로를 우선했습니다.";
			case LUGGAGE -> "큰 짐을 들고 이동하기 쉬운 조건을 반영해 이동 부담이 낮은 경로를 우선했습니다.";
		};
	}
}
