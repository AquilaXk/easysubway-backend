package com.easysubway.realtime.domain;

import java.time.Instant;
import java.util.Objects;

public record RealtimeArrivalObservation(
	String providerId,
	String stationId,
	String lineId,
	String providerLineId,
	String providerStationId,
	String trainNo,
	Instant providerObservedAt,
	Instant backendReceivedAt,
	Integer rawEtaSeconds,
	Integer adjustedEtaSeconds,
	String rawDirection,
	String rawDestination,
	Instant retainedUntil
) {
	public RealtimeArrivalObservation {
		providerId = requireText(providerId, "providerId");
		stationId = requireText(stationId, "stationId");
		lineId = requireText(lineId, "lineId");
		providerLineId = requireText(providerLineId, "providerLineId");
		providerStationId = requireText(providerStationId, "providerStationId");
		trainNo = requireText(trainNo, "trainNo");
		rawDirection = requireMaxLength(rawDirection, "rawDirection", 120);
		rawDestination = requireMaxLength(rawDestination, "rawDestination", 120);
		providerObservedAt = Objects.requireNonNull(providerObservedAt, "providerObservedAt must not be null");
		backendReceivedAt = Objects.requireNonNull(backendReceivedAt, "backendReceivedAt must not be null");
		retainedUntil = Objects.requireNonNull(retainedUntil, "retainedUntil must not be null");
		requireNonNegative(rawEtaSeconds, "rawEtaSeconds");
		requireNonNegative(adjustedEtaSeconds, "adjustedEtaSeconds");
		if (!retainedUntil.isAfter(backendReceivedAt)) {
			throw new IllegalArgumentException("retainedUntil must be after backendReceivedAt");
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	private static void requireNonNegative(Integer value, String field) {
		if (value != null && value < 0) {
			throw new IllegalArgumentException(field + " must be zero or greater");
		}
	}

	private static String requireMaxLength(String value, String field, int maxLength) {
		if (value != null && value.codePointCount(0, value.length()) > maxLength) {
			throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
		}
		return value;
	}
}
