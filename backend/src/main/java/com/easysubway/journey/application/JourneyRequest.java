package com.easysubway.journey.application;

import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

public record JourneyRequest(
	String requestId,
	String originStationId,
	String destinationStationId,
	Departure departure,
	TimePolicy timePolicy,
	MobilityProfile mobilityProfile,
	ConstraintMode constraintMode,
	int maxTransfers,
	int alternativeCount,
	BooleanSupplier cancellationSignal
) {
	private static final Pattern ULID = Pattern.compile("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");

	public JourneyRequest {
		requestId = requireUlid(requestId);
		originStationId = requireText(originStationId, "originStationId");
		destinationStationId = requireText(destinationStationId, "destinationStationId");
		departure = Objects.requireNonNull(departure, "departure");
		timePolicy = Objects.requireNonNull(timePolicy, "timePolicy");
		mobilityProfile = Objects.requireNonNull(mobilityProfile, "mobilityProfile");
		constraintMode = Objects.requireNonNull(constraintMode, "constraintMode");
		if (maxTransfers < 0 || maxTransfers > 3) throw new IllegalArgumentException("maxTransfers must be between 0 and 3");
		if (alternativeCount < 1 || alternativeCount > 3) throw new IllegalArgumentException("alternativeCount must be between 1 and 3");
		if (mobilityProfile == MobilityProfile.NO_STAIRS && constraintMode == ConstraintMode.NONE) {
			throw new IllegalArgumentException("NO_STAIRS requires REQUIRE_STEP_FREE");
		}
		cancellationSignal = Objects.requireNonNull(cancellationSignal, "cancellationSignal");
	}

	public boolean isCancelled() {
		return cancellationSignal.getAsBoolean();
	}

	public sealed interface Departure permits Departure.Now, Departure.Scheduled {
		record Now() implements Departure {
		}

		record Scheduled(Instant requestedAt) implements Departure {
			public Scheduled {
				requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
			}
		}
	}

	public enum TimePolicy {
		TIMETABLE_REQUIRED,
		REALTIME_REQUIRED
	}

	public enum MobilityProfile {
		STANDARD,
		SLOW,
		NO_STAIRS,
		STEP_FREE
	}

	public enum ConstraintMode {
		NONE,
		REQUIRE_STEP_FREE
	}

	private static String requireUlid(String value) {
		value = requireText(value, "requestId");
		if (!ULID.matcher(value).matches()) throw new IllegalArgumentException("requestId must be a ULID");
		return value;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
