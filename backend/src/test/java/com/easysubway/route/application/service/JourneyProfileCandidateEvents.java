package com.easysubway.route.application.service;

import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 측정 후보를 위한 compiled event 읽기이며, 운행 승인이나 profile 성공을 의미하지 않는다. */
final class JourneyProfileCandidateEvents {
	private JourneyProfileCandidateEvents() { }

	static List<Event> events(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		Instant activeFrom,
		Instant freshUntil,
		Set<String> canonicalLineIds
	) {
		if (activeFrom == null || freshUntil == null || !activeFrom.isBefore(freshUntil)) {
			throw new IllegalArgumentException("candidate validity window is required");
		}
		// 27:xx 운행은 03:00 경계를 지나도 원래 service date에 속한다.
		// 기존 시간표의 허용 시각 범위로 날짜를 좁힌 뒤 실제 compiled event로 판정한다.
		LocalDate first = activeFrom.minusSeconds(LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE - 1L)
			.atZone(ServiceDayResolver.ZONE).toLocalDate();
		LocalDate last = freshUntil.minusNanos(1).atZone(ServiceDayResolver.ZONE).toLocalDate();
		List<Event> selected = new ArrayList<>();
		for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
			Instant midnight = date.atStartOfDay(ServiceDayResolver.ZONE).toInstant();
			for (Event event : events(timetable, date, canonicalLineIds)) {
				if (event.stops().stream().anyMatch(stop ->
					inside(midnight.plusSeconds(stop.arrivalSeconds()), activeFrom, freshUntil)
						|| inside(midnight.plusSeconds(stop.departureSeconds()), activeFrom, freshUntil))) {
					selected.add(event);
				}
			}
		}
		return List.copyOf(selected);
	}

	private static boolean inside(Instant event, Instant activeFrom, Instant freshUntil) {
		return !event.isBefore(activeFrom) && event.isBefore(freshUntil);
	}

	static List<Event> events(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		LocalDate serviceDate,
		Set<String> canonicalLineIds
	) {
		if (canonicalLineIds == null || canonicalLineIds.isEmpty()) throw new IllegalArgumentException("canonical lines are required");
		Set<String> known = timetable.source().transitRoutes().stream().map(route -> route.lineId()).collect(java.util.stream.Collectors.toSet());
		if (!known.containsAll(canonicalLineIds)) throw new IllegalArgumentException("canonical line is not compiled");
		Map<Integer, RouteTimetableRaptorPlanner.ScheduledTrip> unique = new LinkedHashMap<>();
		var active = timetable.activeServiceDay(serviceDate);
		for (int pattern = 0; pattern < timetable.routePatternCount(); pattern += 1) {
			for (var trip : active.tripsByPattern(pattern)) {
				if (canonicalLineIds.contains(trip.route().lineId())) unique.putIfAbsent(trip.index(), trip);
			}
		}
		List<Event> events = new ArrayList<>();
		for (var trip : unique.values()) {
			List<Stop> stops = new ArrayList<>();
			for (int index = 0; index < trip.stopTimes().size(); index += 1) {
				var stop = trip.stopTimes().get(index);
				stops.add(new Stop(stop.stationId(), stop.lineId(), trip.arrivalSeconds(index), trip.departureSeconds(index),
					trip.allowsPickup(index), trip.allowsDropOff(index)));
			}
			events.add(new Event(trip.route().id(), trip.route().lineId(), trip.trip().id(), trip.index(), serviceDate, stops));
		}
		events.sort(Comparator.comparing(Event::routeLineId).thenComparing(Event::tripId).thenComparingInt(Event::scheduledTripIndex));
		return List.copyOf(events);
	}

	record Event(String routeId, String routeLineId, String tripId, int scheduledTripIndex, LocalDate serviceDate, List<Stop> stops) {
		public Event { stops = List.copyOf(stops); }
	}
	record Stop(String stationId, String lineId, int arrivalSeconds, int departureSeconds, boolean allowsPickup, boolean allowsDropOff) { }
}
