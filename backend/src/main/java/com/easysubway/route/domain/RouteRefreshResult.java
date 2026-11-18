package com.easysubway.route.domain;

import java.time.LocalDateTime;
import java.util.List;

public record RouteRefreshResult(
	String routeSearchId,
	RouteRefreshStatus status,
	RouteSearchResult routeSearch,
	LocalDateTime refreshedAt,
	EtaSource etaSource,
	EtaConfidence etaConfidence,
	String sourceLabel,
	List<String> reasonCodes
) {
	public RouteRefreshResult {
		if (routeSearchId == null || routeSearchId.isBlank()) {
			throw new IllegalArgumentException("routeSearchId is required.");
		}
		if (status == null) {
			throw new IllegalArgumentException("status is required.");
		}
		if (routeSearch == null) {
			throw new IllegalArgumentException("routeSearch is required.");
		}
		if (refreshedAt == null) {
			throw new IllegalArgumentException("refreshedAt is required.");
		}
		if (etaSource == null) {
			throw new IllegalArgumentException("etaSource is required.");
		}
		if (etaConfidence == null) {
			throw new IllegalArgumentException("etaConfidence is required.");
		}
		sourceLabel = sourceLabel == null || sourceLabel.isBlank()
			? "계획 시간 기준"
			: sourceLabel;
		reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
	}
}
