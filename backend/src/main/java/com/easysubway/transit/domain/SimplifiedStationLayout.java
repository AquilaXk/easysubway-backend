package com.easysubway.transit.domain;

import java.time.LocalDate;
import java.util.List;

public record SimplifiedStationLayout(
	String id,
	String stationId,
	int version,
	SimplifiedStationLayoutStatus status,
	List<String> sourceIds,
	SimplifiedStationLayoutConfidence confidenceLevel,
	String baseFloor,
	String layoutJson,
	String renderedPreviewUrl,
	String createdBy,
	String reviewedBy,
	LocalDate publishedAt,
	LocalDate lastVerifiedAt
) {

	public SimplifiedStationLayout {
		id = requireText(id, "id");
		stationId = requireText(stationId, "stationId");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than zero.");
		}
		status = requireNonNull(status, "status");
		sourceIds = cleanSourceIds(sourceIds);
		confidenceLevel = requireNonNull(confidenceLevel, "confidenceLevel");
		baseFloor = requireText(baseFloor, "baseFloor");
		layoutJson = requireText(layoutJson, "layoutJson");
		renderedPreviewUrl = cleanOptionalText(renderedPreviewUrl);
		createdBy = requireText(createdBy, "createdBy");
		reviewedBy = cleanOptionalText(reviewedBy);
		lastVerifiedAt = requireNonNull(lastVerifiedAt, "lastVerifiedAt");
	}

	private static List<String> cleanSourceIds(List<String> values) {
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException("sourceIds must not be empty.");
		}
		List<String> cleanedValues = values.stream()
			.map(value -> requireText(value, "sourceIds"))
			.distinct()
			.toList();
		if (cleanedValues.isEmpty()) {
			throw new IllegalArgumentException("sourceIds must not be empty.");
		}
		return List.copyOf(cleanedValues);
	}

	private static String cleanOptionalText(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
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
