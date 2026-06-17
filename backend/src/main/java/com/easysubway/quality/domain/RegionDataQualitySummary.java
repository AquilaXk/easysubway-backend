package com.easysubway.quality.domain;

import com.easysubway.transit.domain.DataQualityLevel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record RegionDataQualitySummary(
	String name,
	int stationCount,
	Map<DataQualityLevel, Long> stationQualityCounts
) {

	public RegionDataQualitySummary {
		if (stationQualityCounts == null || stationQualityCounts.isEmpty()) {
			stationQualityCounts = Map.of();
		} else {
			stationQualityCounts = Collections.unmodifiableMap(new EnumMap<>(stationQualityCounts));
		}
	}
}
