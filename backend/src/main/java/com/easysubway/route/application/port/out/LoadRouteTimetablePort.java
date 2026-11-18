package com.easysubway.route.application.port.out;

import com.easysubway.route.application.model.PlannerIdentity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface LoadRouteTimetablePort {

	int SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE = 108000;

	RouteTimetable loadRouteTimetable();

	/** Mutable implementations override this to read identity and rows in one transaction. */
	default RouteTimetableSnapshot loadRouteTimetableSnapshot() {
		return new RouteTimetableSnapshot(
			timetableCacheKey(),
			activeItxTimetableArtifactId().orElse(null),
			loadRouteTimetable()
		);
	}

	/** V3 station-time requests need the captured freshness receipt to classify stale data explicitly. */
	default RouteTimetableSnapshot loadStationTimetableSnapshot() {
		return loadRouteTimetableSnapshot();
	}

	default String timetableCacheKey() {
		return "STATIC";
	}

	default boolean hasRouteTimetable() {
		RouteTimetable timetable = loadRouteTimetable();
		return !timetable.transitTrips().isEmpty() && !timetable.transitStopTimes().isEmpty();
	}

	/** 활성화와 serving 모두 fresh, integrity-verified snapshot만 허용한다. */
	default boolean hasActivatableRouteTimetable() {
		return hasRouteTimetable();
	}

	default Optional<String> activeItxTimetableArtifactId() {
		return Optional.empty();
	}

	record RouteTimetableSnapshot(
		String cacheKey,
		String timetableArtifactId,
		PlannerIdentity plannerIdentity,
		Instant freshUntil,
		RouteTimetable timetable
	) {
		public RouteTimetableSnapshot(
			String cacheKey,
			String timetableArtifactId,
			PlannerIdentity plannerIdentity,
			RouteTimetable timetable
		) {
			this(cacheKey, timetableArtifactId, plannerIdentity, null, timetable);
		}
		public RouteTimetableSnapshot(String cacheKey, String timetableArtifactId, RouteTimetable timetable) {
			this(cacheKey, timetableArtifactId, null, null, timetable);
		}
	}

	record RouteTimetable(
		List<ServiceCalendar> serviceCalendars,
		List<ServiceCalendarDate> serviceCalendarDates,
		List<TransitRoute> transitRoutes,
		List<TransitTrip> transitTrips,
		List<TransitStopTime> transitStopTimes,
		List<TransitFrequency> transitFrequencies,
		List<OfficialFare> officialFares,
		// GTFS feed_info.feed_end_date (개정 유효 종료일). null이면 개정 유효기간 미선언이므로 STALE 강등하지 않는다.
		LocalDate feedEndDate,
		RouteAccessData routeAccessData
	) {
		public RouteTimetable(
			List<ServiceCalendar> serviceCalendars,
			List<ServiceCalendarDate> serviceCalendarDates,
			List<TransitRoute> transitRoutes,
			List<TransitTrip> transitTrips,
			List<TransitStopTime> transitStopTimes,
			List<TransitFrequency> transitFrequencies,
			List<OfficialFare> officialFares,
			LocalDate feedEndDate
		) {
			this(serviceCalendars, serviceCalendarDates, transitRoutes, transitTrips, transitStopTimes,
				transitFrequencies, officialFares, feedEndDate, RouteAccessData.empty());
		}
		public RouteTimetable(
			List<ServiceCalendar> serviceCalendars,
			List<ServiceCalendarDate> serviceCalendarDates,
			List<TransitRoute> transitRoutes,
			List<TransitTrip> transitTrips,
			List<TransitStopTime> transitStopTimes,
			List<TransitFrequency> transitFrequencies,
			LocalDate feedEndDate
		) {
			this(serviceCalendars, serviceCalendarDates, transitRoutes, transitTrips, transitStopTimes,
				transitFrequencies, List.of(), feedEndDate);
		}

		public RouteTimetable(
			List<ServiceCalendar> serviceCalendars,
			List<ServiceCalendarDate> serviceCalendarDates,
			List<TransitRoute> transitRoutes,
			List<TransitTrip> transitTrips,
			List<TransitStopTime> transitStopTimes,
			List<TransitFrequency> transitFrequencies
		) {
			this(serviceCalendars, serviceCalendarDates, transitRoutes, transitTrips, transitStopTimes,
				transitFrequencies, List.of(), null);
		}

		public RouteTimetable {
			serviceCalendars = List.copyOf(serviceCalendars);
			serviceCalendarDates = List.copyOf(serviceCalendarDates);
			transitRoutes = List.copyOf(transitRoutes);
			transitTrips = List.copyOf(transitTrips);
			transitStopTimes = List.copyOf(transitStopTimes);
			transitFrequencies = List.copyOf(transitFrequencies);
			officialFares = List.copyOf(officialFares);
			routeAccessData = routeAccessData == null ? RouteAccessData.empty() : routeAccessData;
		}

		public static RouteTimetable empty() {
			return new RouteTimetable(
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, RouteAccessData.empty());
		}
	}

	record RouteAccessData(
		List<PathwayNode> pathwayNodes,
		List<PathwayEdge> pathwayEdges,
		List<TransferRule> transferRules,
		List<RouteEdgeEvidence> routeEdgeEvidence,
		List<CarDoorHint> carDoorHints,
		// 공식 연단 간격 등급. 값은 이미 노출 순서(간격 WIDE→NORMAL→NARROW, 높이차 HIGH→NORMAL→LOW, 위치 오름차순)로 정렬돼 있다.
		Map<PlatformGapKey, List<PlatformGap>> platformGaps
	) {
		public RouteAccessData(
			List<PathwayNode> pathwayNodes,
			List<PathwayEdge> pathwayEdges,
			List<TransferRule> transferRules,
			List<RouteEdgeEvidence> routeEdgeEvidence
		) {
			this(pathwayNodes, pathwayEdges, transferRules, routeEdgeEvidence, List.of(), Map.of());
		}

		public RouteAccessData(
			List<PathwayNode> pathwayNodes,
			List<PathwayEdge> pathwayEdges,
			List<TransferRule> transferRules,
			List<RouteEdgeEvidence> routeEdgeEvidence,
			List<CarDoorHint> carDoorHints
		) {
			this(pathwayNodes, pathwayEdges, transferRules, routeEdgeEvidence, carDoorHints, Map.of());
		}

		public RouteAccessData {
			pathwayNodes = List.copyOf(pathwayNodes);
			pathwayEdges = List.copyOf(pathwayEdges);
			transferRules = List.copyOf(transferRules);
			routeEdgeEvidence = List.copyOf(routeEdgeEvidence);
			carDoorHints = carDoorHints == null ? List.of() : List.copyOf(carDoorHints);
			platformGaps = platformGaps == null ? Map.of() : Map.copyOf(platformGaps);
		}
		public static RouteAccessData empty() {
			return new RouteAccessData(List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
		}
	}
	record PlatformGapKey(String stationId, String lineId, String direction) {
		public PlatformGapKey {
			Objects.requireNonNull(stationId, "stationId");
			Objects.requireNonNull(lineId, "lineId");
			Objects.requireNonNull(direction, "direction");
		}
	}
	/** 공식 연단 간격 등급. 선언 순서가 노출 순서(넓을수록 앞)다. */
	enum GapGrade {
		WIDE, NORMAL, NARROW;

		public static GapGrade fromCode(String code) {
			return switch (code) {
				case "WIDE" -> WIDE;
				case "NORMAL" -> NORMAL;
				case "NARROW" -> NARROW;
				default -> throw new IllegalArgumentException("invalid platform gap gap_grade: " + code);
			};
		}
	}
	/** 공식 높이차 등급. 선언 순서가 노출 순서(높을수록 앞)다. */
	enum HeightDiffGrade {
		HIGH, NORMAL, LOW;

		public static HeightDiffGrade fromCode(String code) {
			return switch (code) {
				case "HIGH" -> HIGH;
				case "NORMAL" -> NORMAL;
				case "LOW" -> LOW;
				default -> throw new IllegalArgumentException("invalid platform gap height_diff_grade: " + code);
			};
		}
	}
	record PlatformGap(
		String platformPosition,
		Integer carNumber,
		Integer doorNumber,
		GapGrade gapGrade,
		HeightDiffGrade heightDiffGrade,
		boolean curved
	) {
		public PlatformGap {
			Objects.requireNonNull(platformPosition, "platformPosition");
			Objects.requireNonNull(gapGrade, "gapGrade");
			Objects.requireNonNull(heightDiffGrade, "heightDiffGrade");
		}
	}
	record CarDoorHint(
		String stationId,
		String lineId,
		String direction,
		String targetFacilityType,
		int carNumber,
		int doorNumber
	) {
		public CarDoorHint {
			Objects.requireNonNull(stationId, "stationId");
			Objects.requireNonNull(lineId, "lineId");
			Objects.requireNonNull(direction, "direction");
			Objects.requireNonNull(targetFacilityType, "targetFacilityType");
			if (carNumber < 1 || carNumber > 10) {
				throw new IllegalArgumentException("carNumber must be between 1 and 10");
			}
			if (doorNumber < 1 || doorNumber > 4) {
				throw new IllegalArgumentException("doorNumber must be between 1 and 4");
			}
		}
	}
	record PathwayNode(String id, String stationId, String lineId, String nodeType) {
	}
		record PathwayEdge(
			String id,
			String fromNodeId,
			String toNodeId,
		int durationSeconds,
		int distanceMeters,
		boolean bidirectional,
		boolean includesStairs,
		int reliabilityScore,
			String accessibilityStatus,
			String provenanceKind,
			String verificationStatus,
			String legacyInternalRouteEdgeId,
			/**
			 * #469·#480: 원천 번들의 계단 접근 상태({@code stair_access_state}). data 어휘는 STEP_FREE(계단 없음 확정),
			 * STAIR_ONLY(계단 확정), UNKNOWN(미확정)이다. null은 필드가 없다는 뜻이고 미확정과 같다.
			 */
			String stairAccessState
		) {
			public PathwayEdge(
				String id, String fromNodeId, String toNodeId, int durationSeconds, int distanceMeters,
				boolean bidirectional, boolean includesStairs, int reliabilityScore,
				String accessibilityStatus, String provenanceKind, String verificationStatus
			) {
				this(id, fromNodeId, toNodeId, durationSeconds, distanceMeters, bidirectional, includesStairs,
					reliabilityScore, accessibilityStatus, provenanceKind, verificationStatus, id, null);
			}

			/** 같은 동선에 계단 접근 상태만 바꾼 사본. */
			public PathwayEdge withStairAccessState(String state) {
				return new PathwayEdge(id, fromNodeId, toNodeId, durationSeconds, distanceMeters, bidirectional,
					includesStairs, reliabilityScore, accessibilityStatus, provenanceKind, verificationStatus,
					legacyInternalRouteEdgeId, state);
			}
		}
	record TransferRule(
		String id,
		String fromStationId,
		String fromLineId,
		String toStationId,
		String toLineId,
		String transferType,
		int minTransferSeconds,
		String pathwayEdgeId,
		String strictStepFreePathwayEdgeId,
		String verificationStatus
	) {
	}
	record RouteEdgeEvidence(
		String id,
		String stationId,
		String lineId,
		String edgeId,
		String edgeType,
		String provenanceKind,
		String verificationStatus,
		boolean strictRouteEligible,
		String blockerReason
	) {
	}
	record ServiceCalendar(
		String serviceId,
		boolean monday,
		boolean tuesday,
		boolean wednesday,
		boolean thursday,
		boolean friday,
		boolean saturday,
		boolean sunday,
		LocalDate startDate,
		LocalDate endDate,
		String timezone
	) {
		public ServiceCalendar {
			requireDate(startDate, "service_calendars.start_date");
			requireDate(endDate, "service_calendars.end_date");
			if (startDate.isAfter(endDate)) {
				throw new IllegalArgumentException("service_calendars.start_date must be <= end_date");
			}
		}
	}

	record ServiceCalendarDate(String serviceId, LocalDate date, int exceptionType) {
		public ServiceCalendarDate {
			requireDate(date, "service_calendar_dates.date");
			if (exceptionType != 1 && exceptionType != 2) {
				throw new IllegalArgumentException("service_calendar_dates.exception_type must be 1 or 2");
			}
		}
	}

	record TransitRoute(
		String id,
		String lineId,
		String routeShortName,
		String routeLongName,
		String directionName,
		String timezone
	) {
	}

	record TransitTrip(
		String id,
		String routeId,
		String serviceId,
		String tripHeadsign,
		String directionId,
		String serviceClass,
		String servicePattern,
		String trainNo,
		int serviceDayStartSeconds
	) {
		public TransitTrip(
			String id,
			String routeId,
			String serviceId,
			String tripHeadsign,
			String directionId,
			String servicePattern,
			int serviceDayStartSeconds
		) {
			this(id, routeId, serviceId, tripHeadsign, directionId, "SUBWAY", servicePattern, null,
				serviceDayStartSeconds);
		}

		public TransitTrip {
			if (!"SUBWAY".equals(serviceClass) && !"ITX_CHEONGCHUN".equals(serviceClass)) {
				throw new IllegalArgumentException("transit_trips.service_class is invalid");
			}
			if (!"LOCAL".equals(servicePattern) && !"EXPRESS".equals(servicePattern)) {
				throw new IllegalArgumentException("transit_trips.service_pattern is invalid");
			}
			if ("ITX_CHEONGCHUN".equals(serviceClass) && !"EXPRESS".equals(servicePattern)) {
				throw new IllegalArgumentException("ITX_CHEONGCHUN must use EXPRESS service_pattern");
			}
			trainNo = trainNo == null || trainNo.isBlank() ? null : trainNo;
			requireServiceDaySeconds(serviceDayStartSeconds, "transit_trips.service_day_start_seconds");
		}
	}

	record OfficialFare(
		String tripId,
		String originStationId,
		String destinationStationId,
		int adultFareWon,
		String currency,
		String sourceId,
		String sourceSnapshotId
	) {
		public OfficialFare {
			if (adultFareWon <= 0 || !"KRW".equals(currency)) {
				throw new IllegalArgumentException("official fare must be positive KRW");
			}
		}
	}

	record TransitStopTime(
		String tripId,
		int stopSequence,
		String stationId,
		String lineId,
		int arrivalSeconds,
		int departureSeconds,
		int pickupType,
		int dropOffType
	) {
		public TransitStopTime {
			if (stopSequence <= 0) {
				throw new IllegalArgumentException("transit_stop_times.stop_sequence must be positive");
			}
			requireServiceDaySeconds(arrivalSeconds, "transit_stop_times.arrival_seconds");
			requireServiceDaySeconds(departureSeconds, "transit_stop_times.departure_seconds");
			if (arrivalSeconds > departureSeconds) {
				throw new IllegalArgumentException("transit_stop_times.arrival_seconds must be <= departure_seconds");
			}
		}
	}

	record TransitFrequency(
		String tripId,
		int startTimeSeconds,
		int endTimeSeconds,
		int headwaySeconds,
		boolean exactTimes
	) {
		public TransitFrequency {
			requireServiceDaySeconds(startTimeSeconds, "transit_frequencies.start_time_seconds");
			requireServiceDaySeconds(endTimeSeconds, "transit_frequencies.end_time_seconds");
			if (endTimeSeconds <= startTimeSeconds) {
				throw new IllegalArgumentException("transit_frequencies.end_time_seconds must be > start_time_seconds");
			}
			if (headwaySeconds <= 0) {
				throw new IllegalArgumentException("transit_frequencies.headway_seconds must be positive");
			}
		}
	}

	private static void requireDate(LocalDate date, String fieldName) {
		if (date == null) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
	}

	private static void requireServiceDaySeconds(int seconds, String fieldName) {
		if (seconds < 0 || seconds >= SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE) {
			throw new IllegalArgumentException(fieldName + " must be >= 0 and < 108000");
		}
	}
}
