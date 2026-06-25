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
	boolean requiresAccessibilityCheck
) {
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
}
