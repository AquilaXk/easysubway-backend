package com.easysubway.transit.application.port.in;

import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataSourceType;
import java.math.BigDecimal;

public record CreateAccessibilityFacilityCommand(
	String id,
	String stationId,
	String exitId,
	AccessibilityFacilityType type,
	String name,
	String floorFrom,
	String floorTo,
	BigDecimal latitude,
	BigDecimal longitude,
	String description,
	AccessibilityFacilityStatus status,
	DataConfidenceLevel dataConfidence,
	DataSourceType dataSourceType,
	String updatedBy
) {
}
