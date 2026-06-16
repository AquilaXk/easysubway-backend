package com.easysubway.transit.domain;

import java.time.LocalDate;

public record StationLayoutSource(
	String id,
	String stationId,
	StationLayoutSourceType sourceType,
	String sourceName,
	String sourceUrl,
	String license,
	boolean commercialUseAllowed,
	boolean attributionRequired,
	LocalDate capturedAt,
	LocalDate reviewedAt
) {
}
