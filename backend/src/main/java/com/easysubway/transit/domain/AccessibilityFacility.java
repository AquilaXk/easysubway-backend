package com.easysubway.transit.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AccessibilityFacility(
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
	LocalDate lastUpdatedAt
) {
}
