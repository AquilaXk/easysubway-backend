package com.easysubway.route.domain;

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
	boolean requiresAccessibilityCheck
) {
	public RouteStep {
		stairAccessState = stairAccessState == null || stairAccessState.isBlank()
			? stairAccessStateFor(includesStairs)
			: stairAccessState;
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
			requiresAccessibilityCheck
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
