package com.easysubway.field.domain;

public record FieldVerificationItem(
	String id,
	FieldVerificationItemType type,
	String targetName,
	FieldVerificationStatus status,
	String note
) {

	public FieldVerificationItem {
		id = requireText(id, "id");
		type = requireNonNull(type, "type");
		targetName = requireText(targetName, "targetName");
		status = requireNonNull(status, "status");
		note = cleanOptionalText(note);
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
