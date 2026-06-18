package com.easysubway.transit.application.port.in;

public record UpdateRouteEdgeCommand(
	String stationId,
	String edgeId,
	int distanceMeters,
	int estimatedSeconds,
	boolean hasStairs,
	boolean requiresElevator,
	boolean requiresEscalator,
	int slopeLevel,
	int widthLevel,
	int reliabilityScore,
	boolean active,
	String updatedBy
) {
}
