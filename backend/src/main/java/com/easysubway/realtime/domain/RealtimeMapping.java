package com.easysubway.realtime.domain;

public record RealtimeMapping(
	String providerId,
	String stationId,
	String lineId,
	String providerLineId,
	String providerStationId,
	String queryName,
	String providerLineName,
	boolean supportsArrivals,
	boolean supportsTrainPositions,
	String mappingConfidence,
	long cacheVersion
) {
	public boolean liveEligible() {
		return "OFFICIAL".equals(mappingConfidence) || "MANUAL".equals(mappingConfidence);
	}

	public String ineligibleReason() {
		if (!liveEligible()) {
			return "MAPPING_LOW_CONFIDENCE";
		}
		return null;
	}

	public String effectiveQueryName(String fallback) {
		return queryName == null || queryName.isBlank() ? fallback : queryName;
	}

	public String effectiveProviderLineName(String fallback) {
		return providerLineName == null || providerLineName.isBlank() ? fallback : providerLineName;
	}
}
