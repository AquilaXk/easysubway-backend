package com.easysubway.route.domain;

import com.easysubway.profile.domain.MobilityType;
import java.time.LocalDateTime;
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
}
