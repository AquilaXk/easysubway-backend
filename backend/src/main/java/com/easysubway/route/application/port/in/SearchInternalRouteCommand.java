package com.easysubway.route.application.port.in;

import com.easysubway.profile.domain.MobilityType;

public record SearchInternalRouteCommand(
	String stationId,
	String fromNodeId,
	String toNodeId,
	MobilityType mobilityType
) {
}
