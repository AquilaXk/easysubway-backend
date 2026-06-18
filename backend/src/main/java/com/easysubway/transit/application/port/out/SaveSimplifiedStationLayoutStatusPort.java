package com.easysubway.transit.application.port.out;

import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import java.time.LocalDate;

public interface SaveSimplifiedStationLayoutStatusPort {

	void saveSimplifiedStationLayoutStatus(
		String layoutId,
		SimplifiedStationLayoutStatus status,
		String reviewedBy,
		LocalDate updatedAt
	);
}
