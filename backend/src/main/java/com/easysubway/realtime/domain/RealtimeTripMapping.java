package com.easysubway.realtime.domain;

public record RealtimeTripMapping(
	String providerId,
	String lineId,
	String providerLineId,
	String rawDirection,
	String canonicalDirection,
	String rawDestination,
	String canonicalDestination,
	String rawServicePattern,
	String canonicalServicePattern,
	String mappingConfidence,
	long cacheVersion
) {
	public boolean liveEligible() {
		return "OFFICIAL".equals(mappingConfidence) || "MANUAL".equals(mappingConfidence);
	}

	public String canonicalDirection(String fallback) {
		return canonicalOrFallback(canonicalDirection, fallback);
	}

	public String canonicalDestination(String fallback) {
		return canonicalOrFallback(canonicalDestination, fallback);
	}

	public String canonicalServicePattern(String fallback) {
		return canonicalOrFallback(canonicalServicePattern, fallback);
	}

	public boolean matchesLine(String requestedLineId, String requestedProviderLineId) {
		if (requestedProviderLineId != null && !requestedProviderLineId.isBlank()
			&& !providerLineId.equals(requestedProviderLineId)) {
			return false;
		}
		if (requestedLineId == null || requestedLineId.isBlank()) {
			return true;
		}
		return lineId.equals(requestedLineId) || lineId.endsWith("-" + requestedLineId);
	}

	public boolean matchesRaw(String direction, String destination, String servicePattern) {
		return rawMatches(rawDirection, direction)
			&& rawMatches(rawDestination, destination)
			&& rawMatches(rawServicePattern, servicePattern);
	}

	public int specificity() {
		int specificity = 0;
		if (rawDirection != null && !rawDirection.isBlank()) {
			specificity += 1;
		}
		if (rawDestination != null && !rawDestination.isBlank()) {
			specificity += 1;
		}
		if (rawServicePattern != null && !rawServicePattern.isBlank()) {
			specificity += 1;
		}
		return specificity;
	}

	private boolean rawMatches(String expected, String actual) {
		return expected == null || expected.isBlank() || expected.equals(actual);
	}

	private String canonicalOrFallback(String canonical, String fallback) {
		return canonical == null || canonical.isBlank() ? fallback : canonical;
	}
}
