package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;
import java.util.List;

public record InternalRouteResult(
	String stationId,
	String stationName,
	String fromNodeId,
	String fromNodeName,
	String toNodeId,
	String toNodeName,
	MobilityType mobilityType,
	RouteSearchStatus status,
	int totalDistanceMeters,
	int totalEstimatedSeconds,
	List<InternalRouteStep> steps,
	List<RouteWarning> warnings,
	List<String> blockedReasons
) {
}
