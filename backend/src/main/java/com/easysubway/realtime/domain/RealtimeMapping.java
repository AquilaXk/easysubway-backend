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

	public boolean matchesProviderLine(String requestedProviderLineId) {
		if (requestedProviderLineId == null || requestedProviderLineId.isBlank()) {
			return true;
		}
		if (providerLineId.equals(requestedProviderLineId)) {
			return true;
		}
		// ponytail: TOPIS station-code alias is the provider station id suffix; add explicit alias data if another provider needs it.
		return providerStationId != null && providerStationId.endsWith(requestedProviderLineId);
	}

	public boolean matchesLine(String requestedLineId) {
		if (requestedLineId == null || requestedLineId.isBlank()) {
			return true;
		}
		return lineId.equals(requestedLineId) || lineId.endsWith("-" + requestedLineId);
	}
}
