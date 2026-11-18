package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.StationLayoutSourceType;
import java.time.LocalDate;

public record UpdateStationLayoutSourceCommand(
	String stationId,
	String sourceId,
	StationLayoutSourceType sourceType,
	String sourceName,
	String sourceUrl,
	String license,
	boolean commercialUseAllowed,
	boolean attributionRequired,
	LocalDate capturedAt,
	LocalDate reviewedAt,
	String updatedBy
) {
}
