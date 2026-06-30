package com.easysubway.route.application.port.in;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.ConstraintMode;

public record SearchRouteCommand(
	String originStationId,
	String destinationStationId,
	MobilityType mobilityType,
	ConstraintMode constraintMode
) {
	public SearchRouteCommand(String originStationId, String destinationStationId, MobilityType mobilityType) {
		this(originStationId, destinationStationId, mobilityType, ConstraintMode.defaultFor(mobilityType));
	}

	public SearchRouteCommand {
		if (constraintMode == null) {
			constraintMode = ConstraintMode.defaultFor(mobilityType);
		}
	}
}
