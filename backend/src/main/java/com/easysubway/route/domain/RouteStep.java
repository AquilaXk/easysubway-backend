package com.easysubway.route.domain;

import java.util.List;

public record RouteStep(
	int sequence,
	String stepType,
	String title,
	String description,
	String lineId,
	String lineName,
	String fromStationId,
	String toStationId,
	int estimatedMinutes,
	int distanceMeters,
	boolean includesStairs,
	String stairAccessState,
	boolean requiresAccessibilityCheck,
	String timeSource,
	String distanceSource,
	String confidenceLabel,
	List<String> reasonCodes,
	String providerSnapshotId,
	String providerObservedAt,
	String gatewayReceivedAt,
	String servedAt
) {
	public RouteStep {
		stairAccessState = stairAccessState == null || stairAccessState.isBlank()
			? stairAccessStateFor(includesStairs)
			: stairAccessState;
		timeSource = timeSource == null || timeSource.isBlank() ? "UNKNOWN" : timeSource;
		distanceSource = distanceSource == null || distanceSource.isBlank() ? "UNKNOWN" : distanceSource;
		confidenceLabel = confidenceLabel == null || confidenceLabel.isBlank() ? "확인 필요" : confidenceLabel;
		reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
	}

	public RouteStep(
		int sequence,
		String stepType,
		String title,
		String description,
		String lineId,
		String lineName,
		String fromStationId,
		String toStationId,
		int estimatedMinutes,
		int distanceMeters,
		boolean includesStairs,
		String stairAccessState,
		boolean requiresAccessibilityCheck,
		String timeSource,
		String distanceSource,
		String confidenceLabel
	) {
		this(
			sequence,
			stepType,
			title,
			description,
			lineId,
			lineName,
			fromStationId,
			toStationId,
			estimatedMinutes,
			distanceMeters,
			includesStairs,
			stairAccessState,
			requiresAccessibilityCheck,
			timeSource,
			distanceSource,
			confidenceLabel,
			List.of(),
			null,
			null,
			null,
			null
		);
	}

	public RouteStep(
		int sequence,
		String stepType,
		String title,
		String description,
		String lineId,
		String lineName,
		String fromStationId,
		String toStationId,
		int estimatedMinutes,
		int distanceMeters,
		boolean includesStairs,
		boolean requiresAccessibilityCheck
	) {
		this(
			sequence,
			stepType,
			title,
			description,
			lineId,
			lineName,
			fromStationId,
			toStationId,
			estimatedMinutes,
			distanceMeters,
			includesStairs,
			stairAccessStateFor(includesStairs),
			requiresAccessibilityCheck,
			"ESTIMATED_CONSTANT",
			"ESTIMATED_CONSTANT",
			"확인 필요"
		);
	}

	public RouteStep(
		int sequence,
		String title,
		String description,
		String lineId,
		String lineName,
		String fromStationId,
		String toStationId,
		int estimatedMinutes,
		int distanceMeters,
		boolean includesStairs,
		boolean requiresAccessibilityCheck
	) {
		this(
			sequence,
			"",
			title,
			description,
			lineId,
			lineName,
			fromStationId,
			toStationId,
			estimatedMinutes,
			distanceMeters,
			includesStairs,
			requiresAccessibilityCheck
		);
	}

	private static String stairAccessStateFor(boolean includesStairs) {
		return includesStairs ? "STAIR_ONLY" : "UNKNOWN";
	}
}
