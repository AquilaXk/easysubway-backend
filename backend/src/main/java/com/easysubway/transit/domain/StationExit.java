package com.easysubway.transit.domain;

import java.math.BigDecimal;

public record StationExit(
	String id,
	String stationId,
	String exitNumber,
	String name,
	BigDecimal latitude,
	BigDecimal longitude,
	boolean hasElevatorConnection,
	boolean hasStairOnlyPath,
	DataConfidenceLevel dataConfidence,
	DataSourceType dataSourceType
) {
}
