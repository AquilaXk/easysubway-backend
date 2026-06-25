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

	@JsonProperty("burdenCost")
	public int burdenCost() {
		return score;
	}

	@JsonProperty("estimatedDurationSeconds")
	public int estimatedDurationSeconds() {
		return steps.stream()
			.mapToInt(step -> Math.max(0, step.estimatedMinutes()) * 60)
			.sum();
	}

	@JsonProperty("walkingDistanceMeters")
	public int walkingDistanceMeters() {
		return steps.stream()
			.filter(this::isWalkingStep)
			.mapToInt(step -> Math.max(0, step.distanceMeters()))
			.sum();
	}

	@JsonProperty("transferCount")
	public int transferCount() {
		long typedTransfers = steps.stream()
			.filter(step -> "transfer".equals(step.stepType()))
			.count();
		if (typedTransfers > 0) {
			return Math.toIntExact(typedTransfers);
		}
		String previousLine = "";
		int changes = 0;
		for (RouteStep step : steps) {
			String line = !step.lineId().isBlank() ? step.lineId() : step.lineName();
			if (line.isBlank()) {
				continue;
			}
			if (!previousLine.isBlank() && !previousLine.equals(line)) {
				changes++;
			}
			previousLine = line;
		}
		return changes;
	}

	@JsonProperty("evidenceSummary")
	public List<String> evidenceSummary() {
		if (steps.isEmpty()) {
			return List.of();
		}
		boolean requiresAccessibilityCheck = steps.stream()
			.anyMatch(step -> step.requiresAccessibilityCheck() || "UNKNOWN".equals(step.stairAccessState()));
		boolean hasDurationEstimate = steps.stream()
			.allMatch(step -> step.estimatedMinutes() > 0);
		boolean hasDistanceMeasure = steps.stream()
			.allMatch(step -> step.distanceMeters() > 0);
		return List.of(
			requiresAccessibilityCheck ? "ACCESSIBILITY_CHECK_REQUIRED" : "ACCESSIBILITY_VERIFIED",
			hasDurationEstimate ? "DURATION_ESTIMATED" : "DURATION_UNKNOWN",
			hasDistanceMeasure ? "DISTANCE_MEASURED" : "DISTANCE_UNKNOWN"
		);
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

	private boolean isWalkingStep(RouteStep step) {
		return switch (step.stepType()) {
			case "entry", "exit", "transfer", "internal" -> true;
			case "ride" -> false;
			default -> step.requiresAccessibilityCheck();
		};
	}
}
