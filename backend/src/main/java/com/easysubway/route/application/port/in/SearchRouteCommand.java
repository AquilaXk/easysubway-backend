package com.easysubway.route.application.port.in;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.ConstraintMode;
import java.time.OffsetDateTime;

public record SearchRouteCommand(
	String originStationId,
	String destinationStationId,
	MobilityType mobilityType,
	ConstraintMode constraintMode,
	int maxTransfers,
	OffsetDateTime departureTime,
	boolean useRealtime
) {
	public SearchRouteCommand(String originStationId, String destinationStationId, MobilityType mobilityType) {
		this(originStationId, destinationStationId, mobilityType, ConstraintMode.defaultFor(mobilityType), 1);
	}

	public SearchRouteCommand(
		String originStationId,
		String destinationStationId,
		MobilityType mobilityType,
		ConstraintMode constraintMode
	) {
		this(originStationId, destinationStationId, mobilityType, constraintMode, 1);
	}

	public SearchRouteCommand(
		String originStationId,
		String destinationStationId,
		MobilityType mobilityType,
		ConstraintMode constraintMode,
		int maxTransfers
	) {
		this(originStationId, destinationStationId, mobilityType, constraintMode, maxTransfers, null, false);
	}

	public SearchRouteCommand {
		if (constraintMode == null) {
			constraintMode = ConstraintMode.defaultFor(mobilityType);
		}
		maxTransfers = Math.max(0, maxTransfers);
	}
}
