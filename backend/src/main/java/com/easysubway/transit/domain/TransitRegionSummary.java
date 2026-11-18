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
		EnumMap<DataQualityLevel, Long> snapshot = new EnumMap<>(DataQualityLevel.class);
		if (dataQualityCounts != null) {
			snapshot.putAll(dataQualityCounts);
		}
		dataQualityCounts = Collections.unmodifiableMap(snapshot);
	}

	@Override
	public Map<DataQualityLevel, Long> dataQualityCounts() {
		EnumMap<DataQualityLevel, Long> snapshot = new EnumMap<>(DataQualityLevel.class);
		snapshot.putAll(dataQualityCounts);
		return Collections.unmodifiableMap(snapshot);
	}
}
