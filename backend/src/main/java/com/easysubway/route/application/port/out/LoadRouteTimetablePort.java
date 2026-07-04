package com.easysubway.route.application.port.out;

import java.time.LocalDate;
import java.util.List;

public interface LoadRouteTimetablePort {

	int SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE = 108000;

	RouteTimetable loadRouteTimetable();

	record RouteTimetable(
		List<ServiceCalendar> serviceCalendars,
		List<ServiceCalendarDate> serviceCalendarDates,
		List<TransitRoute> transitRoutes,
		List<TransitTrip> transitTrips,
		List<TransitStopTime> transitStopTimes,
		List<TransitFrequency> transitFrequencies
	) {
		public RouteTimetable {
			serviceCalendars = List.copyOf(serviceCalendars);
			serviceCalendarDates = List.copyOf(serviceCalendarDates);
			transitRoutes = List.copyOf(transitRoutes);
			transitTrips = List.copyOf(transitTrips);
			transitStopTimes = List.copyOf(transitStopTimes);
			transitFrequencies = List.copyOf(transitFrequencies);
		}

		public static RouteTimetable empty() {
			return new RouteTimetable(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		}
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
		String servicePattern,
		int serviceDayStartSeconds
	) {
		public TransitTrip {
			requireServiceDaySeconds(serviceDayStartSeconds, "transit_trips.service_day_start_seconds");
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
