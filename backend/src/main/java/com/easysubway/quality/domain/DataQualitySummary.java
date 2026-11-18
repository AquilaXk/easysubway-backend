package com.easysubway.quality.domain;

import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.DataQualityLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DataQualitySummary(
	int totalStations,
	int totalExits,
	int totalFacilities,
	Map<DataQualityLevel, Long> stationQualityCounts,
	List<RegionDataQualitySummary> regionSummaries,
	Map<DataConfidenceLevel, Long> exitConfidenceCounts,
	Map<DataConfidenceLevel, Long> facilityConfidenceCounts,
	long needsVerificationFacilityCount,
	long delayedFacilityStatusCount,
	Map<AccessibilityFacilityStatus, Long> delayedFacilityStatusCounts,
	long missingStationVerificationDateCount,
	List<StationAccessibilityScore> stationAccessibilityScores,
	List<AccessibilityImprovementPriority> accessibilityImprovementPriorities
) {

	public DataQualitySummary {
		stationQualityCounts = stationQualityCounts == null ? null : new LinkedHashMap<>(stationQualityCounts);
		regionSummaries = List.copyOf(regionSummaries);
		exitConfidenceCounts = exitConfidenceCounts == null ? null : new LinkedHashMap<>(exitConfidenceCounts);
		facilityConfidenceCounts = facilityConfidenceCounts == null
			? null
			: new LinkedHashMap<>(facilityConfidenceCounts);
		delayedFacilityStatusCounts = delayedFacilityStatusCounts == null
			? null
			: new LinkedHashMap<>(delayedFacilityStatusCounts);
		stationAccessibilityScores = List.copyOf(stationAccessibilityScores);
		accessibilityImprovementPriorities = List.copyOf(accessibilityImprovementPriorities);
	}

	@Override
	public Map<DataQualityLevel, Long> stationQualityCounts() {
		return stationQualityCounts == null ? null : new LinkedHashMap<>(stationQualityCounts);
	}

	@Override
	public Map<DataConfidenceLevel, Long> exitConfidenceCounts() {
		return exitConfidenceCounts == null ? null : new LinkedHashMap<>(exitConfidenceCounts);
	}

	@Override
	public Map<DataConfidenceLevel, Long> facilityConfidenceCounts() {
		return facilityConfidenceCounts == null ? null : new LinkedHashMap<>(facilityConfidenceCounts);
	}

	@Override
	public Map<AccessibilityFacilityStatus, Long> delayedFacilityStatusCounts() {
		return delayedFacilityStatusCounts == null ? null : new LinkedHashMap<>(delayedFacilityStatusCounts);
	}
}
