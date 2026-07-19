package com.easysubway.route.application.port.out;

import java.time.LocalDate;
import java.util.List;
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

	default String timetableCacheKey() {
		return "STATIC";
	}

	default boolean hasRouteTimetable() {
		RouteTimetable timetable = loadRouteTimetable();
		return !timetable.transitTrips().isEmpty() && !timetable.transitStopTimes().isEmpty();
	}

	default Optional<String> activeItxTimetableArtifactId() {
		return Optional.empty();
	}

	record RouteTimetableSnapshot(
		String cacheKey,
		String timetableArtifactId,
		PlannerIdentity plannerIdentity,
		RouteTimetable timetable
	) {
		public RouteTimetableSnapshot(String cacheKey, String timetableArtifactId, RouteTimetable timetable) {
			this(cacheKey, timetableArtifactId, null, timetable);
		}
	}

	record PlannerIdentity(
		String timetableSnapshotSha256,
		String canonicalPackSha256,
		String canonicalPackSqliteSha256,
		String canonicalStationVersion,
		String canonicalStationSetSha256,
		String sourceLineageSha256,
		String evidenceHash
	) {
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
		List<RouteEdgeEvidence> routeEdgeEvidence
	) {
		public RouteAccessData {
			pathwayNodes = List.copyOf(pathwayNodes);
			pathwayEdges = List.copyOf(pathwayEdges);
			transferRules = List.copyOf(transferRules);
			routeEdgeEvidence = List.copyOf(routeEdgeEvidence);
		}
		public static RouteAccessData empty() {
			return new RouteAccessData(List.of(), List.of(), List.of(), List.of());
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
			String legacyInternalRouteEdgeId
		) {
			public PathwayEdge(
				String id, String fromNodeId, String toNodeId, int durationSeconds, int distanceMeters,
				boolean bidirectional, boolean includesStairs, int reliabilityScore,
				String accessibilityStatus, String provenanceKind, String verificationStatus
			) {
				this(id, fromNodeId, toNodeId, durationSeconds, distanceMeters, bidirectional, includesStairs,
					reliabilityScore, accessibilityStatus, provenanceKind, verificationStatus, id);
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
