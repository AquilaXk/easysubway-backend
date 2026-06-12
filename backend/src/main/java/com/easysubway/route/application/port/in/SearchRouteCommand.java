package com.easysubway.route.application.port.in;

import com.easysubway.profile.domain.MobilityType;

public record SearchRouteCommand(
	String originStationId,
	String destinationStationId,
	MobilityType mobilityType
) {
}
