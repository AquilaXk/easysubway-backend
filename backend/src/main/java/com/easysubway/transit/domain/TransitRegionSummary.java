package com.easysubway.transit.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record TransitRegionSummary(
	String name,
	int operatorCount,
	int lineCount,
	int stationCount,
	Map<DataQualityLevel, Long> dataQualityCounts
) {

	public TransitRegionSummary {
		if (dataQualityCounts == null || dataQualityCounts.isEmpty()) {
			dataQualityCounts = Map.of();
		} else {
			dataQualityCounts = Collections.unmodifiableMap(new EnumMap<>(dataQualityCounts));
		}
	}
}
