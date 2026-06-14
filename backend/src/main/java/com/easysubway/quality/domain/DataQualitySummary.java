package com.easysubway.quality.domain;

import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import java.util.Map;

public record DataQualitySummary(
	int totalStations,
	int totalExits,
	int totalFacilities,
	Map<DataQualityLevel, Long> stationQualityCounts,
	Map<DataConfidenceLevel, Long> exitConfidenceCounts,
	Map<DataConfidenceLevel, Long> facilityConfidenceCounts,
	long needsVerificationFacilityCount,
	long missingStationVerificationDateCount
) {
}
