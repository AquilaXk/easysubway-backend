package com.easysubway.field.domain;

import java.time.LocalDate;
import java.util.List;

public record FieldVerificationSession(
	String id,
	String stationId,
	String stationName,
	LocalDate verifiedAt,
	String verifiedBy,
	FieldVerificationStatus status,
	String note,
	List<FieldVerificationItem> items
) {

	public FieldVerificationSession {
		id = requireText(id, "id");
		stationId = requireText(stationId, "stationId");
		stationName = requireText(stationName, "stationName");
		verifiedAt = requireNonNull(verifiedAt, "verifiedAt");
		verifiedBy = requireText(verifiedBy, "verifiedBy");
		status = requireNonNull(status, "status");
		note = cleanOptionalText(note);
		items = cleanItems(items);
	}

	private static List<FieldVerificationItem> cleanItems(List<FieldVerificationItem> values) {
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException("items must not be empty.");
		}
		return List.copyOf(values.stream()
			.map(value -> requireNonNull(value, "items"))
			.toList());
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
