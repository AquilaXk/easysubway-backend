package com.easysubway.journey.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record JourneyCandidate(
	String journeyId,
	Instant plannedDepartureTime,
	Instant plannedArrivalTime,
	Instant realtimeDepartureTime,
	Instant realtimeArrivalTime,
	long durationSeconds,
	int transferCount,
	long walkingDistanceMeters,
	TimeSource timeSource,
	Accessibility accessibility,
	List<Leg> legs
) {
	public JourneyCandidate {
		journeyId = requireText(journeyId, "journeyId");
		plannedDepartureTime = Objects.requireNonNull(plannedDepartureTime, "plannedDepartureTime");
		plannedArrivalTime = Objects.requireNonNull(plannedArrivalTime, "plannedArrivalTime");
		requireOrdered(plannedDepartureTime, plannedArrivalTime, "planned times");
		requireOptionalPair(realtimeDepartureTime, realtimeArrivalTime, "realtime times");
		if (realtimeDepartureTime != null) {
			requireOrdered(realtimeDepartureTime, realtimeArrivalTime, "realtime times");
		}
		if (durationSeconds < 0) throw new IllegalArgumentException("durationSeconds must not be negative");
		if (transferCount < 0 || transferCount > 3) {
			throw new IllegalArgumentException("transferCount must be between 0 and 3");
		}
		if (walkingDistanceMeters < 0) {
			throw new IllegalArgumentException("walkingDistanceMeters must not be negative");
		}
		timeSource = Objects.requireNonNull(timeSource, "timeSource");
		if ((timeSource == TimeSource.TIMETABLE) != (realtimeDepartureTime == null)) {
			throw new IllegalArgumentException("timeSource does not match realtime fields");
		}
		accessibility = Objects.requireNonNull(accessibility, "accessibility");
		legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
		if (legs.isEmpty()) throw new IllegalArgumentException("legs must not be empty");
		boolean candidateHasRealtime = realtimeDepartureTime != null;
		for (Leg leg : legs) {
			if (leg instanceof Ride ride && (ride.realtimeDepartureTime() != null) != candidateHasRealtime) {
				throw new IllegalArgumentException("ride realtime fields do not match candidate timeSource");
			}
		}
	}

	public Status status() {
		return Status.FOUND;
	}

	public PlanSource planSource() {
		return PlanSource.SERVER_TIMETABLE_RAPTOR;
	}

	public boolean hasRealtime() {
		return realtimeDepartureTime != null;
	}

	public enum Status {
		FOUND
	}

	public enum PlanSource {
		SERVER_TIMETABLE_RAPTOR
	}

	public enum TimeSource {
		TIMETABLE,
		REALTIME
	}

	public enum AccessibilityResult {
		VERIFIED
	}

	public enum LegType {
		ENTRY,
		RIDE,
		TRANSFER,
		EXIT
	}

	public record Accessibility(boolean stairFree, List<String> reasonCodes) {
		public Accessibility {
			reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
			var uniqueReasons = new HashSet<String>();
			for (String reasonCode : reasonCodes) {
				if (!uniqueReasons.add(requireText(reasonCode, "reasonCode"))) {
					throw new IllegalArgumentException("reasonCodes must be unique");
				}
			}
		}

		public AccessibilityResult result() {
			return AccessibilityResult.VERIFIED;
		}
	}

	public sealed interface Leg permits Entry, Ride, Transfer, Exit {
		LegType type();
	}

	public record Entry(String fromStationId, long durationSeconds) implements Leg {
		public Entry {
			fromStationId = requireText(fromStationId, "fromStationId");
			if (durationSeconds < 0) throw new IllegalArgumentException("durationSeconds must not be negative");
		}

		@Override
		public LegType type() {
			return LegType.ENTRY;
		}
	}

	public record Ride(
		String lineId,
		String tripId,
		String directionStationId,
		String fromStationId,
		String toStationId,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime
	) implements Leg {
		public Ride {
			lineId = requireText(lineId, "lineId");
			tripId = requireText(tripId, "tripId");
			directionStationId = requireText(directionStationId, "directionStationId");
			fromStationId = requireText(fromStationId, "fromStationId");
			toStationId = requireText(toStationId, "toStationId");
			plannedDepartureTime = Objects.requireNonNull(plannedDepartureTime, "plannedDepartureTime");
			plannedArrivalTime = Objects.requireNonNull(plannedArrivalTime, "plannedArrivalTime");
			requireOrdered(plannedDepartureTime, plannedArrivalTime, "planned ride times");
			requireOptionalPair(realtimeDepartureTime, realtimeArrivalTime, "realtime ride times");
			if (realtimeDepartureTime != null) {
				requireOrdered(realtimeDepartureTime, realtimeArrivalTime, "realtime ride times");
			}
		}

		@Override
		public LegType type() {
			return LegType.RIDE;
		}
	}

	public record Transfer(String fromStationId, String toStationId, long durationSeconds) implements Leg {
		public Transfer {
			fromStationId = requireText(fromStationId, "fromStationId");
			toStationId = requireText(toStationId, "toStationId");
			if (durationSeconds < 0) throw new IllegalArgumentException("durationSeconds must not be negative");
		}

		@Override
		public LegType type() {
			return LegType.TRANSFER;
		}
	}

	public record Exit(String fromStationId, long durationSeconds) implements Leg {
		public Exit {
			fromStationId = requireText(fromStationId, "fromStationId");
			if (durationSeconds < 0) throw new IllegalArgumentException("durationSeconds must not be negative");
		}

		@Override
		public LegType type() {
			return LegType.EXIT;
		}
	}

	private static void requireOrdered(Instant departure, Instant arrival, String label) {
		if (departure.isAfter(arrival)) throw new IllegalArgumentException(label + " must be ordered");
	}

	private static void requireOptionalPair(Object first, Object second, String label) {
		if ((first == null) != (second == null)) throw new IllegalArgumentException(label + " must be a pair");
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
