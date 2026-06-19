package com.easysubway.field.domain;

import java.time.LocalDateTime;

public record FieldVerificationChangeHistory(
	String id,
	String sessionId,
	String stationId,
	String itemId,
	FieldVerificationStatus previousStatus,
	FieldVerificationStatus newStatus,
	String previousNote,
	String newNote,
	String changedBy,
	LocalDateTime changedAt
) {

	public FieldVerificationChangeHistory {
		id = requireText(id, "id");
		sessionId = requireText(sessionId, "sessionId");
		stationId = requireText(stationId, "stationId");
		itemId = requireText(itemId, "itemId");
		previousStatus = requireNonNull(previousStatus, "previousStatus");
		newStatus = requireNonNull(newStatus, "newStatus");
		previousNote = cleanOptionalText(previousNote);
		newNote = cleanOptionalText(newNote);
		changedBy = requireText(changedBy, "changedBy");
		changedAt = requireNonNull(changedAt, "changedAt");
	}

	private static String requireText(String value, String fieldName) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(fieldName + " must not be blank.");
		}
		return value.trim();
	}

	private static String cleanOptionalText(String value) {
		if (value == null || value.isBlank()) {
			return null;
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
