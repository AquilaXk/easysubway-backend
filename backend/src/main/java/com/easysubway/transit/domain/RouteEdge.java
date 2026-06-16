package com.easysubway.transit.domain;

public record RouteEdge(
	String id,
	String stationId,
	String fromNodeId,
	String toNodeId,
	RouteEdgeType type,
	int distanceMeters,
	int estimatedSeconds,
	boolean hasStairs,
	boolean requiresElevator,
	boolean requiresEscalator,
	int slopeLevel,
	int widthLevel,
	int reliabilityScore,
	boolean active
) {

	public RouteEdge {
		id = requireText(id, "id");
		stationId = requireText(stationId, "stationId");
		fromNodeId = requireText(fromNodeId, "fromNodeId");
		toNodeId = requireText(toNodeId, "toNodeId");
		type = requireNonNull(type, "type");
		requireNotNegative(distanceMeters, "distanceMeters");
		requireNotNegative(estimatedSeconds, "estimatedSeconds");
		requireLevel(slopeLevel, "slopeLevel");
		requireLevel(widthLevel, "widthLevel");
		requireReliabilityScore(reliabilityScore);
	}

	private static void requireNotNegative(int value, String fieldName) {
		if (value < 0) {
			throw new IllegalArgumentException(fieldName + " must not be negative.");
		}
	}

	private static void requireLevel(int value, String fieldName) {
		if (value < 1 || value > 5) {
			throw new IllegalArgumentException(fieldName + " must be between 1 and 5.");
		}
	}

	private static void requireReliabilityScore(int value) {
		if (value < 0 || value > 100) {
			throw new IllegalArgumentException("reliabilityScore must be between 0 and 100.");
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
