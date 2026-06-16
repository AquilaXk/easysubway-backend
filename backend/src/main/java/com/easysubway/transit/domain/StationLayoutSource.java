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

	public StationLayoutSource {
		id = requireText(id, "id");
		stationId = requireText(stationId, "stationId");
		sourceType = requireNonNull(sourceType, "sourceType");
		sourceName = requireText(sourceName, "sourceName");
		sourceUrl = requireText(sourceUrl, "sourceUrl");
		license = requireText(license, "license");
		capturedAt = requireNonNull(capturedAt, "capturedAt");
		if (reviewedAt != null && reviewedAt.isBefore(capturedAt)) {
			throw new IllegalArgumentException("reviewedAt must not be before capturedAt.");
		}
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank.");
		}
		return value.trim();
	}

	private static <T> T requireNonNull(T value, String fieldName) {
		if (value == null) {
			throw new IllegalArgumentException(fieldName + " must not be null.");
		}
		return value;
	}
}
