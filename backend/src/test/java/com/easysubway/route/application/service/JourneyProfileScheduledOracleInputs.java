package com.easysubway.route.application.service;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 원시 정시 시간표를 oracle 입력으로만 정규화하며 planner나 frontier를 거치지 않는다. */
final class JourneyProfileScheduledOracleInputs {
	private JourneyProfileScheduledOracleInputs() { }

	static List<JourneyProfileExactOracle.Ride> rides(RouteTimetable source, LocalDate serviceDate, int maxRides) {
		Objects.requireNonNull(source, "source is required");
		Objects.requireNonNull(serviceDate, "serviceDate is required");
		if (maxRides < 1) throw new IllegalArgumentException("oracle ride budget must be positive");
		Map<String, ServiceCalendar> calendars = uniqueCalendars(source.serviceCalendars());
		Map<ServiceDate, Integer> exceptions = uniqueExceptions(source.serviceCalendarDates(), calendars.keySet());
		Map<String, TransitRoute> routes = uniqueRoutes(source.transitRoutes());
		List<TransitTrip> trips = uniqueTrips(source.transitTrips(), routes.keySet(), calendars.keySet());
		Map<String, List<TransitStopTime>> stops = orderedStops(source.transitStopTimes(), trips);
		Map<String, List<TransitFrequency>> frequencies = frequencies(source.transitFrequencies(), trips);
		List<Event> events = events(trips, routes, stops, frequencies, calendars, exceptions, serviceDate, maxRides);
		List<JourneyProfileExactOracle.Ride> rides = new ArrayList<>();
		for (int eventIndex = 0; eventIndex < events.size(); eventIndex += 1) {
			Event event = events.get(eventIndex);
			Instant midnight = serviceDate.atStartOfDay(ZoneId.of(event.route().timezone())).toInstant();
			List<TransitStopTime> tripStops = event.stops();
			for (int from = 0; from < tripStops.size(); from += 1) {
				for (int to = from + 1; to < tripStops.size(); to += 1) {
					TransitStopTime board = tripStops.get(from);
					TransitStopTime alight = tripStops.get(to);
					rides.add(new JourneyProfileExactOracle.Ride(
						event.trip().id(), serviceDate, eventIndex, board.stationId(), board.lineId(), alight.stationId(), alight.lineId(),
						midnight.plusSeconds(board.departureSeconds()), midnight.plusSeconds(alight.arrivalSeconds()),
						from, to, board.pickupType() != 1, alight.dropOffType() != 1));
				}
			}
		}
		return List.copyOf(rides);
	}

	private static Map<String, List<TransitFrequency>> frequencies(
		List<TransitFrequency> values, List<TransitTrip> trips
	) {
		Set<String> tripIds = trips.stream().map(TransitTrip::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
		Map<String, List<TransitFrequency>> result = new HashMap<>();
		for (TransitFrequency value : values) {
			requireKnown(value.tripId(), tripIds, "frequency trip");
			if (!value.exactTimes()) throw new IllegalArgumentException("nonexact frequency is not oracle input");
			result.computeIfAbsent(value.tripId(), ignored -> new ArrayList<>()).add(value);
		}
		for (Map.Entry<String, List<TransitFrequency>> entry : result.entrySet()) {
			entry.getValue().sort(java.util.Comparator.comparingInt(TransitFrequency::startTimeSeconds));
			int previousEnd = -1;
			for (TransitFrequency frequency : entry.getValue()) {
				if (frequency.startTimeSeconds() < previousEnd) throw new IllegalArgumentException("overlapping frequency window");
				previousEnd = frequency.endTimeSeconds();
			}
		}
		return result;
	}

	private static List<Event> events(
		List<TransitTrip> trips, Map<String, TransitRoute> routes, Map<String, List<TransitStopTime>> stops,
		Map<String, List<TransitFrequency>> frequencies, Map<String, ServiceCalendar> calendars,
		Map<ServiceDate, Integer> exceptions, LocalDate serviceDate, int maxRides
	) {
		List<Event> result = new ArrayList<>();
		long plannedPairs = 0;
		for (TransitTrip trip : trips) {
			if (!active(calendars.get(trip.serviceId()), exceptions.get(new ServiceDate(trip.serviceId(), serviceDate)), serviceDate)) continue;
			List<TransitStopTime> template = stops.get(trip.id());
			List<TransitFrequency> scheduled = frequencies.get(trip.id());
			if (scheduled == null) {
				plannedPairs = reservePairs(template, plannedPairs, maxRides);
				result.add(new Event(trip, routes.get(trip.routeId()), template, template.getFirst().departureSeconds()));
				continue;
			}
			for (TransitFrequency frequency : scheduled) {
				for (long departure = frequency.startTimeSeconds(); departure < frequency.endTimeSeconds(); departure += frequency.headwaySeconds()) {
					plannedPairs = reservePairs(template, plannedPairs, maxRides);
					result.add(new Event(trip, routes.get(trip.routeId()), shifted(template, (int) departure), (int) departure));
				}
			}
		}
		result.sort(java.util.Comparator.comparing((Event event) -> event.trip().id()).thenComparingInt(Event::departureSeconds));
		return List.copyOf(result);
	}

	private static long reservePairs(List<TransitStopTime> stops, long plannedPairs, int maxRides) {
		long pairs = (long) stops.size() * (stops.size() - 1) / 2;
		if (pairs > maxRides - plannedPairs) throw new IllegalArgumentException("oracle ride budget exceeded");
		return plannedPairs + pairs;
	}

	private static List<TransitStopTime> shifted(List<TransitStopTime> template, int departure) {
		// 정규 입력 모델이 이동된 시각의 범위를 검증한다. 서비스 시간 상수를 복제하지 않는다.
		int offset = departure - template.getFirst().departureSeconds();
		return template.stream().map(stop -> new TransitStopTime(stop.tripId(), stop.stopSequence(), stop.stationId(), stop.lineId(),
			stop.arrivalSeconds() + offset, stop.departureSeconds() + offset, stop.pickupType(), stop.dropOffType())).toList();
	}

	private static Map<String, ServiceCalendar> uniqueCalendars(List<ServiceCalendar> values) {
		Map<String, ServiceCalendar> result = new HashMap<>();
		for (ServiceCalendar value : values) putUnique(result, value.serviceId(), value, "calendar");
		return result;
	}

	private static Map<ServiceDate, Integer> uniqueExceptions(List<ServiceCalendarDate> values, Set<String> calendarIds) {
		Map<ServiceDate, Integer> result = new HashMap<>();
		for (ServiceCalendarDate value : values) {
			requireKnown(value.serviceId(), calendarIds, "calendar");
			ServiceDate key = new ServiceDate(value.serviceId(), value.date());
			if (result.putIfAbsent(key, value.exceptionType()) != null) throw new IllegalArgumentException("duplicate calendar exception");
		}
		return result;
	}

	private static Map<String, TransitRoute> uniqueRoutes(List<TransitRoute> values) {
		Map<String, TransitRoute> result = new HashMap<>();
		for (TransitRoute value : values) putUnique(result, value.id(), value, "route");
		return result;
	}

	private static List<TransitTrip> uniqueTrips(
		List<TransitTrip> values, Set<String> routeIds, Set<String> calendarIds
	) {
		Set<String> ids = new HashSet<>();
		for (TransitTrip value : values) {
			if (!ids.add(requiredId(value.id(), "trip"))) throw new IllegalArgumentException("duplicate trip identity");
			requireKnown(value.routeId(), routeIds, "route");
			requireKnown(value.serviceId(), calendarIds, "calendar");
		}
		return List.copyOf(values);
	}

	private static Map<String, List<TransitStopTime>> orderedStops(List<TransitStopTime> values, List<TransitTrip> trips) {
		Set<String> tripIds = trips.stream().map(TransitTrip::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
		Map<String, List<TransitStopTime>> grouped = new HashMap<>();
		Map<String, Set<Integer>> sequences = new HashMap<>();
		for (TransitStopTime value : values) {
			requireKnown(value.tripId(), tripIds, "trip");
			if (!sequences.computeIfAbsent(value.tripId(), ignored -> new HashSet<>()).add(value.stopSequence())) {
				throw new IllegalArgumentException("duplicate stop identity");
			}
			grouped.computeIfAbsent(value.tripId(), ignored -> new ArrayList<>()).add(value);
		}
		for (TransitTrip trip : trips) {
			List<TransitStopTime> tripStops = grouped.get(trip.id());
			if (tripStops == null) throw new IllegalArgumentException("missing stop identity");
			tripStops.sort(java.util.Comparator.comparingInt(TransitStopTime::stopSequence));
		}
		return grouped;
	}

	private static boolean active(ServiceCalendar calendar, Integer exceptionType, LocalDate date) {
		if (exceptionType != null) return exceptionType == 1;
		if (date.isBefore(calendar.startDate()) || date.isAfter(calendar.endDate())) return false;
		return switch (date.getDayOfWeek()) {
			case MONDAY -> calendar.monday(); case TUESDAY -> calendar.tuesday(); case WEDNESDAY -> calendar.wednesday();
			case THURSDAY -> calendar.thursday(); case FRIDAY -> calendar.friday(); case SATURDAY -> calendar.saturday();
			case SUNDAY -> calendar.sunday();
		};
	}

	private static <T> void putUnique(Map<String, T> values, String id, T value, String kind) {
		if (values.putIfAbsent(requiredId(id, kind), value) != null) throw new IllegalArgumentException("duplicate " + kind + " identity");
	}

	private static void requireKnown(String id, Set<String> known, String kind) {
		if (!known.contains(requiredId(id, kind))) throw new IllegalArgumentException("missing " + kind + " identity");
	}

	private static String requiredId(String id, String kind) {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("missing " + kind + " identity");
		return id;
	}

	private record ServiceDate(String serviceId, LocalDate date) { }
	private record Event(TransitTrip trip, TransitRoute route, List<TransitStopTime> stops, int departureSeconds) { }
}
