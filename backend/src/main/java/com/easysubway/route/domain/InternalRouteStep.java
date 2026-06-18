package com.easysubway.route.domain;

import com.easysubway.transit.domain.RouteEdgeType;

public record InternalRouteStep(
	int sequence,
	String edgeId,
	String fromNodeId,
	String fromNodeName,
	String toNodeId,
	String toNodeName,
	RouteEdgeType edgeType,
	int distanceMeters,
	int estimatedSeconds,
	boolean includesStairs,
	boolean requiresElevator,
	boolean requiresEscalator,
	int slopeLevel,
	int widthLevel,
	int reliabilityScore,
	String guidance
) {
}
