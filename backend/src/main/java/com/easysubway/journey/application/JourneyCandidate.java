package com.easysubway.journey.application;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap;
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
	Fare fare,
	List<Leg> legs,
	List<JourneyAlternatives.Category> alternativeCategories
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
		fare = Objects.requireNonNull(fare, "fare");
		legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
		if (legs.isEmpty()) throw new IllegalArgumentException("legs must not be empty");
		// #469: 검색 결과 여정이 대표하는 묶음(정렬·중복 없음). 채움 여정과 결과 구성을 거치지 않은 프로필 여정은 빈 목록이다.
		alternativeCategories = List.copyOf(Objects.requireNonNull(alternativeCategories, "alternativeCategories"));
		if (alternativeCategories.stream().distinct().count() != alternativeCategories.size()
			|| !alternativeCategories.stream().sorted().toList().equals(alternativeCategories)) {
			throw new IllegalArgumentException("alternativeCategories must be unique and ordered");
		}
		boolean candidateHasRealtime = realtimeDepartureTime != null;
		for (Leg leg : legs) {
			if (leg instanceof Ride ride && (ride.realtimeDepartureTime() != null) != candidateHasRealtime) {
				throw new IllegalArgumentException("ride realtime fields do not match candidate timeSource");
			}
		}
	}

	/** #469: 결과 구성에서 정한 대표 묶음을 붙인 사본. */
	public JourneyCandidate withAlternativeCategories(List<JourneyAlternatives.Category> categories) {
		return new JourneyCandidate(journeyId, plannedDepartureTime, plannedArrivalTime, realtimeDepartureTime,
			realtimeArrivalTime, durationSeconds, transferCount, walkingDistanceMeters, timeSource, accessibility, fare, legs,
			Objects.requireNonNull(categories, "categories"));
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

		public static final String REASON_VERIFIED = "ACCESSIBILITY_VERIFIED";
		public static final String REASON_STAIRS_INCLUDED = "ACCESSIBILITY_STAIRS_INCLUDED";
		public static final String REASON_UNDETERMINED = "ACCESSIBILITY_UNDETERMINED";

		/**
		 * #503: 여정의 계단 판정 3상태를 reasonCodes로 낸다. 모든 환승이 계단 없음 확정이면 VERIFIED, 계단 미확정 환승이
		 * 하나라도 있으면 UNDETERMINED(확정 계단이 함께 있어도), 미확정 없이 확정 계단이 있으면 STAIRS_INCLUDED다.
		 * 미확정 환승은 계단 쪽으로 다루므로 {@code anyStairsOrUnconfirmed}에 포함된다.
		 */
		public static Accessibility ofStairAccess(boolean anyStairsOrUnconfirmed, boolean anyUnconfirmed) {
			if (anyUnconfirmed && !anyStairsOrUnconfirmed) {
				throw new IllegalArgumentException("unconfirmed stair access must be counted as stairs");
			}
			String reason = anyUnconfirmed ? REASON_UNDETERMINED
				: anyStairsOrUnconfirmed ? REASON_STAIRS_INCLUDED : REASON_VERIFIED;
			return new Accessibility(!anyStairsOrUnconfirmed, List.of(reason));
		}

		public AccessibilityResult result() {
			return AccessibilityResult.VERIFIED;
		}
	}

	public enum FareStatus {
		AVAILABLE,
		UNAVAILABLE
	}

	public record Fare(
		FareStatus status,
		Integer adultCardWon,
		Integer adultCashWon,
		Integer youthCardWon,
		Integer youthCashWon,
		Integer childCardWon,
		Integer childCashWon,
		List<String> sourceSnapshotIds
	) {
		public static Fare unavailable() {
			return new Fare(FareStatus.UNAVAILABLE, null, null, null, null, null, null, List.of());
		}

		public static Fare available(
			int adultCardWon,
			int adultCashWon,
			int youthCardWon,
			int youthCashWon,
			int childCardWon,
			int childCashWon,
			List<String> sourceSnapshotIds
		) {
			return new Fare(
				FareStatus.AVAILABLE,
				adultCardWon,
				adultCashWon,
				youthCardWon,
				youthCashWon,
				childCardWon,
				childCashWon,
				sourceSnapshotIds == null ? List.of() : List.copyOf(sourceSnapshotIds)
			);
		}

		public Fare {
			status = Objects.requireNonNull(status, "status");
			sourceSnapshotIds = sourceSnapshotIds == null ? List.of() : List.copyOf(sourceSnapshotIds);
			if (status == FareStatus.UNAVAILABLE) {
				if (adultCardWon != null || adultCashWon != null || youthCardWon != null || youthCashWon != null
					|| childCardWon != null || childCashWon != null) {
					throw new IllegalArgumentException("fare amounts must be null when status is UNAVAILABLE");
				}
			} else {
				Objects.requireNonNull(adultCardWon, "adultCardWon");
				Objects.requireNonNull(adultCashWon, "adultCashWon");
				Objects.requireNonNull(youthCardWon, "youthCardWon");
				Objects.requireNonNull(youthCashWon, "youthCashWon");
				Objects.requireNonNull(childCardWon, "childCardWon");
				Objects.requireNonNull(childCashWon, "childCashWon");
				if (adultCardWon < 0 || adultCashWon < 0 || youthCardWon < 0 || youthCashWon < 0
					|| childCardWon < 0 || childCashWon < 0) {
					throw new IllegalArgumentException("fare amounts must not be negative");
				}
				if (sourceSnapshotIds.isEmpty()) {
					throw new IllegalArgumentException("sourceSnapshotIds must not be empty when fare is AVAILABLE");
				}
			}
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

	public record AlightingCarDoor(int carNumber, int doorNumber, String targetFacilityType) {
		public AlightingCarDoor {
			if (carNumber < 1 || carNumber > 10) {
				throw new IllegalArgumentException("carNumber must be between 1 and 10");
			}
			if (doorNumber < 1 || doorNumber > 4) {
				throw new IllegalArgumentException("doorNumber must be between 1 and 4");
			}
			targetFacilityType = requireText(targetFacilityType, "targetFacilityType");
		}
	}

	public record Stop(
		String stationId,
		Instant plannedArrivalTime,
		Instant plannedDepartureTime,
		Instant realtimeArrivalTime,
		Instant realtimeDepartureTime
	) {
		public Stop {
			stationId = requireText(stationId, "stationId");
		}
	}

	public record Ride(
		String lineId,
		String tripId,
		String directionStationId,
		String fromStationId,
		String toStationId,
		String servicePattern,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime,
		List<Stop> stops,
		List<AlightingCarDoor> alightingCarDoors,
		List<PlatformGap> boardingPlatformGaps,
		List<PlatformGap> alightingPlatformGaps
	) implements Leg {
		public Ride {
			lineId = requireText(lineId, "lineId");
			tripId = requireText(tripId, "tripId");
			directionStationId = requireText(directionStationId, "directionStationId");
			fromStationId = requireText(fromStationId, "fromStationId");
			toStationId = requireText(toStationId, "toStationId");
			servicePattern = requireText(servicePattern, "servicePattern");
			if (!"LOCAL".equals(servicePattern) && !"EXPRESS".equals(servicePattern)) {
				throw new IllegalStateException("servicePattern must be LOCAL or EXPRESS, got: " + servicePattern);
			}
			plannedDepartureTime = Objects.requireNonNull(plannedDepartureTime, "plannedDepartureTime");
			plannedArrivalTime = Objects.requireNonNull(plannedArrivalTime, "plannedArrivalTime");
			alightingCarDoors = alightingCarDoors == null ? List.of() : List.copyOf(alightingCarDoors);
			boardingPlatformGaps = boardingPlatformGaps == null ? List.of() : List.copyOf(boardingPlatformGaps);
			alightingPlatformGaps = alightingPlatformGaps == null ? List.of() : List.copyOf(alightingPlatformGaps);
			requireOrdered(plannedDepartureTime, plannedArrivalTime, "planned ride times");
			requireOptionalPair(realtimeDepartureTime, realtimeArrivalTime, "realtime ride times");
			if (realtimeDepartureTime != null) {
				requireOrdered(realtimeDepartureTime, realtimeArrivalTime, "realtime ride times");
			}
			stops = stops == null ? List.of() : List.copyOf(stops);
			if (stops.size() < 2) {
				throw new IllegalStateException("stops must contain at least 2 stops, got: " + stops.size());
			}
		}

		@Override
		public LegType type() {
			return LegType.RIDE;
		}
	}

	public record Transfer(
		String fromStationId,
		String toStationId,
		long durationSeconds,
		String transferType,
		Boolean farePenaltyApplies,
		Integer transferLimitMinutes
	) implements Leg {
		public Transfer {
			fromStationId = requireText(fromStationId, "fromStationId");
			toStationId = requireText(toStationId, "toStationId");
			if (durationSeconds < 0) throw new IllegalArgumentException("durationSeconds must not be negative");
		}

		public Transfer(String fromStationId, String toStationId, long durationSeconds) {
			this(fromStationId, toStationId, durationSeconds, null, null, null);
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
