package com.easysubway.transit.domain;

import java.math.BigDecimal;

public record RouteNode(
	String id,
	String stationId,
	RouteNodeType type,
	String name,
	String floor,
	BigDecimal latitude,
	BigDecimal longitude,
	String facilityId,
	String layoutId,
	int displayX,
	int displayY,
	String displayLabel,
	String accessibilityNote
) {

	public RouteNode {
		id = requireText(id, "id");
		stationId = requireText(stationId, "stationId");
		type = requireNonNull(type, "type");
		name = requireText(name, "name");
		floor = requireText(floor, "floor");
		requireCoordinates(latitude, longitude);
		facilityId = cleanOptionalText(facilityId);
		layoutId = requireText(layoutId, "layoutId");
		requireDisplayCoordinate(displayX, "displayX");
		requireDisplayCoordinate(displayY, "displayY");
		displayLabel = requireText(displayLabel, "displayLabel");
		accessibilityNote = cleanOptionalText(accessibilityNote);
	}

	private static void requireCoordinates(BigDecimal latitude, BigDecimal longitude) {
		if ((latitude == null) != (longitude == null)) {
			throw new IllegalArgumentException("latitude and longitude must be provided together.");
		}
		if (latitude == null) {
			return;
		}
		requireCoordinateRange(latitude, "-90", "90", "latitude");
		requireCoordinateRange(longitude, "-180", "180", "longitude");
	}

	private static void requireCoordinateRange(BigDecimal value, String minimum, String maximum, String fieldName) {
		if (value.compareTo(new BigDecimal(minimum)) < 0 || value.compareTo(new BigDecimal(maximum)) > 0) {
			throw new IllegalArgumentException(fieldName + " must be between " + minimum + " and " + maximum + ".");
		}
	}

	private static void requireDisplayCoordinate(int value, String fieldName) {
		if (value < 0) {
			throw new IllegalArgumentException(fieldName + " must not be negative.");
		}
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
