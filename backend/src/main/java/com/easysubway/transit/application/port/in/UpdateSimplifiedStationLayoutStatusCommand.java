package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;

public record UpdateSimplifiedStationLayoutStatusCommand(
	String layoutId,
	SimplifiedStationLayoutStatus status,
	String reviewedBy,
	Integer expectedVersion
) {

	public UpdateSimplifiedStationLayoutStatusCommand(
		String layoutId,
		SimplifiedStationLayoutStatus status,
		String reviewedBy
	) {
		this(layoutId, status, reviewedBy, null);
	}
}
