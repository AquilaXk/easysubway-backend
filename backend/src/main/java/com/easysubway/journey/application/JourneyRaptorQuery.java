package com.easysubway.journey.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Journey-native input to the RAPTOR engine.
 *
 * <p>The captured effective instant, rather than the client departure form, is the only point-query
 * time supplied to the planner. Range forms are closed here so callers cannot encode an untyped
 * temporal request before their dedicated planner implementations exist.</p>
 */
public record JourneyRaptorQuery(
	String requestId,
	String originStationId,
	String destinationStationId,
	TemporalQuery temporalQuery,
	JourneyRequest.TimePolicy timePolicy,
	JourneyRequest.WalkingPace walkingPace,
	JourneyRequest.MobilityProfile mobilityProfile,
	JourneyRequest.ConstraintMode constraintMode,
	int maxTransfers,
	int alternativeCount,
	BooleanSupplier cancellationSignal
) {
	private static final Pattern ULID = Pattern.compile("^[0-7][0-9A-HJKMNP-TV-Z]{25}$");

	public JourneyRaptorQuery {
		requestId = requireUlid(requestId);
		originStationId = requireText(originStationId, "originStationId");
		destinationStationId = requireText(destinationStationId, "destinationStationId");
		temporalQuery = Objects.requireNonNull(temporalQuery, "temporalQuery");
		timePolicy = Objects.requireNonNull(timePolicy, "timePolicy");
		walkingPace = Objects.requireNonNull(walkingPace, "walkingPace");
		mobilityProfile = Objects.requireNonNull(mobilityProfile, "mobilityProfile");
		constraintMode = Objects.requireNonNull(constraintMode, "constraintMode");
		if (maxTransfers < 0 || maxTransfers > 3) {
			throw new IllegalArgumentException("maxTransfers must be between 0 and 3");
		}
		if (alternativeCount < 1 || alternativeCount > 3) {
			throw new IllegalArgumentException("alternativeCount must be between 1 and 3");
		}
		if (mobilityProfile == JourneyRequest.MobilityProfile.NO_STAIRS
			&& constraintMode == JourneyRequest.ConstraintMode.NONE) {
			throw new IllegalArgumentException("NO_STAIRS requires REQUIRE_STEP_FREE");
		}
		cancellationSignal = Objects.requireNonNull(cancellationSignal, "cancellationSignal");
	}

	public static JourneyRaptorQuery from(JourneyRequest request, Instant effectiveInstant) {
		Objects.requireNonNull(request, "request");
		return new JourneyRaptorQuery(
			request.requestId(),
			request.originStationId(),
			request.destinationStationId(),
			new DepartAt(Objects.requireNonNull(effectiveInstant, "effectiveInstant")),
			request.timePolicy(),
			request.walkingPace(),
			request.mobilityProfile(),
			request.constraintMode(),
			request.maxTransfers(),
			request.alternativeCount(),
			request.cancellationSignal()
		);
	}

	public boolean isCancelled() {
		return cancellationSignal.getAsBoolean();
	}

	public boolean isPointQuery() {
		return temporalQuery instanceof DepartAt;
	}

	public sealed interface TemporalQuery permits DepartAt, DepartBetween, ArriveBy, LastConnection {
	}

	public record DepartAt(Instant readyAt) implements TemporalQuery {
		public DepartAt {
			readyAt = Objects.requireNonNull(readyAt, "readyAt");
		}
	}

	public record DepartBetween(Instant earliestReadyAt, Instant latestReadyAt) implements TemporalQuery {
		public DepartBetween {
			earliestReadyAt = Objects.requireNonNull(earliestReadyAt, "earliestReadyAt");
			latestReadyAt = Objects.requireNonNull(latestReadyAt, "latestReadyAt");
			if (!latestReadyAt.isAfter(earliestReadyAt)) {
				throw new IllegalArgumentException("latestReadyAt must be later than earliestReadyAt");
			}
		}
	}

	public record ArriveBy(Instant earliestReadyAt, Instant arrivalDeadline) implements TemporalQuery {
		public ArriveBy {
			earliestReadyAt = Objects.requireNonNull(earliestReadyAt, "earliestReadyAt");
			arrivalDeadline = Objects.requireNonNull(arrivalDeadline, "arrivalDeadline");
			if (!arrivalDeadline.isAfter(earliestReadyAt)) {
				throw new IllegalArgumentException("arrivalDeadline must be later than earliestReadyAt");
			}
		}
	}

	public record LastConnection(LocalDate serviceDate) implements TemporalQuery {
		public LastConnection {
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
		}
	}

	private static String requireUlid(String value) {
		value = requireText(value, "requestId");
		if (!ULID.matcher(value).matches()) {
			throw new IllegalArgumentException("requestId must be a ULID");
		}
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
