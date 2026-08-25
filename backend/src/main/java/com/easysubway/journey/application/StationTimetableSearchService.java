package com.easysubway.journey.application;

import com.easysubway.route.application.model.PlannerIdentity;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads only one active timetable snapshot and never synthesizes timetable success. */
public final class StationTimetableSearchService {

	public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
	private final LoadRouteTimetablePort timetablePort;
	private final Clock clock;

	public StationTimetableSearchService(LoadRouteTimetablePort timetablePort, Clock clock) {
		this.timetablePort = Objects.requireNonNull(timetablePort, "timetablePort");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public SearchResult search(SearchRequest request) {
		Objects.requireNonNull(request, "request");
		LoadRouteTimetablePort.RouteTimetableSnapshot snapshot;
		try {
			snapshot = timetablePort.loadStationTimetableSnapshot();
		} catch (RuntimeException exception) {
			throw failure(Failure.TIMETABLE_UNAVAILABLE);
		}
		if (snapshot == null || snapshot.timetable() == null) {
			throw failure(Failure.TIMETABLE_UNAVAILABLE);
		}
		if (snapshot.timetableArtifactId() == null && snapshot.plannerIdentity() == null && snapshot.freshUntil() == null) {
			throw failure(Failure.TIMETABLE_UNAVAILABLE);
		}
		if (snapshot.freshUntil() == null) {
			throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
		if (!snapshot.freshUntil().isAfter(clock.instant())) {
			throw failure(Failure.TIMETABLE_STALE);
		}
		SourceIdentity source = sourceIdentity(snapshot);
		RouteTimetable timetable = snapshot.timetable();
		if (!hasCanonicalStationLine(timetable, request.stationId(), request.lineId())) {
			throw failure(Failure.STATION_LINE_NOT_FOUND);
		}

		Map<String, TransitTrip> trips = uniqueById(timetable.transitTrips(), TransitTrip::id);
		Map<String, TransitRoute> routes = uniqueById(timetable.transitRoutes(), TransitRoute::id);
		if (!hasTimetableCoverage(timetable, request.stationId(), request.lineId())) {
			throw failure(Failure.TIMETABLE_NOT_COVERED);
		}
		List<DepartureCandidate> candidates = departures(timetable, trips, routes, request.stationId(), request.lineId());

		DayType resolvedDayType = resolveDayType(timetable, request.selector());
		if (request.selector() instanceof Selector.DayTypeSelector dayType
			&& dayType.dayType() != resolvedDayType) {
			throw failure(Failure.INVALID_JOURNEY_REQUEST);
		}
		List<Departure> selected = select(candidates, timetable, request.selector());
		return new SearchResult(
			request.stationId(), request.lineId(), request.selector(), resolvedDayType,
			group(selected), source
		);
	}

	private static boolean hasCanonicalStationLine(RouteTimetable timetable, String stationId, String lineId) {
		return timetable.routeAccessData().pathwayNodes().stream()
			.anyMatch(stop -> stationId.equals(stop.stationId()) && lineId.equals(stop.lineId()));
	}

	private static boolean hasTimetableCoverage(RouteTimetable timetable, String stationId, String lineId) {
		return timetable.transitStopTimes().stream()
			.anyMatch(stop -> stationId.equals(stop.stationId()) && lineId.equals(stop.lineId()));
	}

	private static List<DepartureCandidate> departures(
		RouteTimetable timetable,
		Map<String, TransitTrip> trips,
		Map<String, TransitRoute> routes,
		String stationId,
		String lineId
	) {
		Map<String, List<TransitStopTime>> stopsByTrip = new HashMap<>();
		for (TransitStopTime stop : timetable.transitStopTimes()) {
			stopsByTrip.computeIfAbsent(stop.tripId(), ignored -> new ArrayList<>()).add(stop);
		}
		Map<String, List<TransitFrequency>> frequenciesByTrip = new HashMap<>();
		for (TransitFrequency frequency : timetable.transitFrequencies()) {
			frequenciesByTrip.computeIfAbsent(frequency.tripId(), ignored -> new ArrayList<>()).add(frequency);
		}
		List<DepartureCandidate> result = new ArrayList<>();
		for (TransitStopTime stop : timetable.transitStopTimes()) {
			if (!stationId.equals(stop.stationId()) || !lineId.equals(stop.lineId())) continue;
			TransitTrip trip = trips.get(stop.tripId());
			if (trip == null) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			TransitRoute route = routes.get(trip.routeId());
			if (route == null || !lineId.equals(route.lineId()) || !SERVICE_ZONE.getId().equals(route.timezone())
				|| route.directionName() == null || route.directionName().isBlank()) {
				throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			}
			List<TransitFrequency> frequencies = frequenciesByTrip.getOrDefault(stop.tripId(), List.of());
			if (frequencies.isEmpty()) {
				result.add(new DepartureCandidate(route.directionName(), trip, stop.departureSeconds()));
				continue;
			}
			int firstDeparture = stopsByTrip.getOrDefault(stop.tripId(), List.of()).stream()
				.min(Comparator.comparingInt(TransitStopTime::stopSequence)).map(TransitStopTime::departureSeconds)
				.orElseThrow(() -> failure(Failure.TIMETABLE_IDENTITY_MISMATCH));
			for (TransitFrequency frequency : frequencies) {
				for (int base = frequency.startTimeSeconds(); base < frequency.endTimeSeconds();) {
					int shift;
					try {
						shift = Math.subtractExact(base, firstDeparture);
					} catch (ArithmeticException exception) {
						throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
					}
					if (!validFrequencyInstance(stopsByTrip.get(stop.tripId()), shift)) {
						base = nextFrequencyBase(base, frequency.headwaySeconds());
						continue;
					}
					int departure;
					try {
						departure = Math.addExact(stop.departureSeconds(), shift);
					} catch (ArithmeticException exception) {
						throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
					}
					if (departure < 0 || departure >= LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE) {
						throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
					}
					result.add(new DepartureCandidate(route.directionName(), trip, departure));
					base = nextFrequencyBase(base, frequency.headwaySeconds());
				}
			}
		}
		return result;
	}

	private static int nextFrequencyBase(int base, int headwaySeconds) {
		try {
			return Math.addExact(base, headwaySeconds);
		} catch (ArithmeticException exception) {
			throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
	}

	private static boolean validFrequencyInstance(List<TransitStopTime> stops, int shift) {
		if (stops == null || stops.isEmpty()) return false;
		try {
			for (TransitStopTime stop : stops) {
				int arrival = Math.addExact(stop.arrivalSeconds(), shift);
				int departure = Math.addExact(stop.departureSeconds(), shift);
				if (arrival < 0 || departure < arrival
					|| departure >= LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE) return false;
			}
			return true;
		} catch (ArithmeticException exception) {
			return false;
		}
	}

	private static List<Departure> select(
		List<DepartureCandidate> candidates,
		RouteTimetable timetable,
		Selector selector
	) {
		List<Departure> result = new ArrayList<>();
		if (selector instanceof Selector.ServiceDateSelector serviceDate) {
			appendForDate(result, candidates, timetable, serviceDate.serviceDate());
		} else if (selector instanceof Selector.DayTypeSelector dayType) {
			appendForDate(result, candidates, timetable, dayType.referenceDate());
		} else if (selector instanceof Selector.NextDeparturesSelector next) {
			LocalDate first = next.asOf().atZone(SERVICE_ZONE).toLocalDate().minusDays(1);
			LocalDate last = next.asOf().plusSeconds((long) next.horizonDays() * 86_400).atZone(SERVICE_ZONE).toLocalDate();
			for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
				appendForDate(result, candidates, timetable, date);
			}
			Instant until = next.asOf().plusSeconds((long) next.horizonDays() * 86_400);
			result.removeIf(value -> value.departureAt().isBefore(next.asOf()) || !value.departureAt().isBefore(until));
			validateDepartureOrderAndIdentity(result);
			Map<String, Departure> firstByDirection = new HashMap<>();
			for (Departure departure : result) {
				firstByDirection.merge(departure.directionName(), departure,
					(left, right) -> left.departureAt().isBefore(right.departureAt()) ? left : right);
			}
			result = new ArrayList<>(firstByDirection.values());
		}
		if (!(selector instanceof Selector.NextDeparturesSelector)) {
			validateDepartureOrderAndIdentity(result);
		}
		return result;
	}

	private static void appendForDate(
		List<Departure> target,
		List<DepartureCandidate> candidates,
		RouteTimetable timetable,
		LocalDate serviceDate
	) {
		Set<String> activeServiceIds = activeServices(timetable, serviceDate);
		for (DepartureCandidate candidate : candidates) {
			if (!activeServiceIds.contains(candidate.trip().serviceId())) continue;
			Instant departureAt = serviceDate.atStartOfDay(SERVICE_ZONE)
				.plusSeconds(candidate.secondsFromServiceDayStart()).toInstant();
			target.add(new Departure(
				candidate.directionName(), serviceDate, candidate.secondsFromServiceDayStart(), departureAt,
				candidate.trip().servicePattern(), candidate.trip().serviceClass()
			));
		}
	}

	private static Set<String> activeServices(RouteTimetable timetable, LocalDate serviceDate) {
		Map<String, Integer> exception = new HashMap<>();
		for (ServiceCalendarDate date : timetable.serviceCalendarDates()) {
			if (!serviceDate.equals(date.date()) || exception.put(date.serviceId(), date.exceptionType()) != null) {
				if (serviceDate.equals(date.date())) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			}
		}
		Set<String> active = new HashSet<>();
		for (ServiceCalendar calendar : timetable.serviceCalendars()) {
			if (!SERVICE_ZONE.getId().equals(calendar.timezone())) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			if (!serviceDate.isBefore(calendar.startDate()) && !serviceDate.isAfter(calendar.endDate())
				&& runsOn(calendar, serviceDate.getDayOfWeek())) {
				active.add(calendar.serviceId());
			}
		}
		for (Map.Entry<String, Integer> entry : exception.entrySet()) {
			if (entry.getValue() == 1) active.add(entry.getKey()); else active.remove(entry.getKey());
		}
		return active;
	}

	private static DayType resolveDayType(RouteTimetable timetable, Selector selector) {
		LocalDate serviceDate = switch (selector) {
			case Selector.ServiceDateSelector value -> value.serviceDate();
			case Selector.DayTypeSelector value -> value.referenceDate();
			case Selector.NextDeparturesSelector value -> value.asOf().atZone(SERVICE_ZONE).toLocalDate();
		};
		DayType civil = DayType.from(serviceDate);
		if (selector instanceof Selector.NextDeparturesSelector) return civil;
		Map<String, List<ServiceCalendar>> calendars = new HashMap<>();
		for (ServiceCalendar calendar : timetable.serviceCalendars()) {
			if (!SERVICE_ZONE.getId().equals(calendar.timezone())) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			calendars.computeIfAbsent(calendar.serviceId(), ignored -> new ArrayList<>()).add(calendar);
		}
		boolean civilClassRemoved = false;
		for (ServiceCalendarDate exception : timetable.serviceCalendarDates()) {
			if (!serviceDate.equals(exception.date()) || exception.exceptionType() != 2) continue;
			List<ServiceCalendar> matches = calendars.get(exception.serviceId());
			if (matches != null && matches.size() == 1 && calendarSignature(matches.getFirst()) == civil) {
				civilClassRemoved = true;
			}
		}
		if (!civilClassRemoved) return civil;

		Set<DayType> overrides = new HashSet<>();
		for (ServiceCalendarDate exception : timetable.serviceCalendarDates()) {
			if (!serviceDate.equals(exception.date()) || exception.exceptionType() != 1) continue;
			List<ServiceCalendar> matches = calendars.get(exception.serviceId());
			if (matches == null || matches.size() != 1) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			DayType signature = calendarSignature(matches.getFirst());
			if (signature == null) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			overrides.add(signature);
		}
		if (overrides.size() != 1) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		return overrides.iterator().next();
	}

	private static DayType calendarSignature(ServiceCalendar calendar) {
		if (calendar.monday() && calendar.tuesday() && calendar.wednesday() && calendar.thursday() && calendar.friday()
			&& !calendar.saturday() && !calendar.sunday()) return DayType.WEEKDAY;
		if (!calendar.monday() && !calendar.tuesday() && !calendar.wednesday() && !calendar.thursday() && !calendar.friday()
			&& calendar.saturday() && !calendar.sunday()) return DayType.SATURDAY;
		if (!calendar.monday() && !calendar.tuesday() && !calendar.wednesday() && !calendar.thursday() && !calendar.friday()
			&& !calendar.saturday() && calendar.sunday()) return DayType.SUNDAY_HOLIDAY;
		return null;
	}

	private static boolean runsOn(ServiceCalendar calendar, DayOfWeek day) {
		return switch (day) {
			case MONDAY -> calendar.monday(); case TUESDAY -> calendar.tuesday(); case WEDNESDAY -> calendar.wednesday();
			case THURSDAY -> calendar.thursday(); case FRIDAY -> calendar.friday(); case SATURDAY -> calendar.saturday();
			case SUNDAY -> calendar.sunday();
		};
	}

	private static List<DirectionGroup> group(List<Departure> departures) {
		Map<String, List<Departure>> grouped = new HashMap<>();
		for (Departure departure : departures) {
			grouped.computeIfAbsent(departure.directionName(), ignored -> new ArrayList<>()).add(departure);
		}
		return grouped.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.map(entry -> new DirectionGroup(entry.getKey(), entry.getValue().stream()
				.sorted(Comparator.comparing(Departure::serviceDate).thenComparingInt(Departure::secondsFromServiceDayStart)).toList()))
			.toList();
	}

	private static void validateDepartureOrderAndIdentity(List<Departure> departures) {
		Set<String> unique = new HashSet<>();
		for (Departure departure : departures) {
			String identity = departure.directionName() + "\u0000" + departure.serviceDate() + "\u0000"
				+ departure.secondsFromServiceDayStart() + "\u0000" + departure.servicePattern() + "\u0000" + departure.serviceClass();
			if (!unique.add(identity)) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
	}

	private static SourceIdentity sourceIdentity(LoadRouteTimetablePort.RouteTimetableSnapshot snapshot) {
		PlannerIdentity identity = snapshot.plannerIdentity();
		if (identity == null || !text(snapshot.timetableArtifactId()) || !sha(identity.timetableSnapshotSha256())
			|| !text(identity.canonicalStationVersion()) || !sha(identity.canonicalStationSetSha256())
			|| !sha(identity.sourceLineageSha256()) || !sha(identity.evidenceHash())) {
			throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
		return new SourceIdentity(snapshot.timetableArtifactId(), identity.timetableSnapshotSha256(),
			identity.canonicalStationVersion(), identity.canonicalStationSetSha256(), identity.sourceLineageSha256(),
			identity.evidenceHash(), snapshot.freshUntil());
	}

	private static boolean text(String value) { return value != null && !value.isBlank(); }
	private static boolean sha(String value) { return value != null && SHA256.matcher(value).matches(); }
	private static FailureException failure(Failure failure) { return new FailureException(failure); }

	public record SearchRequest(String stationId, String lineId, Selector selector) {
		public SearchRequest {
			if (!text(stationId) || !text(lineId)) throw failure(Failure.INVALID_JOURNEY_REQUEST);
			selector = Objects.requireNonNull(selector, "selector");
		}
	}

	public sealed interface Selector permits Selector.ServiceDateSelector, Selector.DayTypeSelector, Selector.NextDeparturesSelector {
		DayType resolvedDayType();
		record ServiceDateSelector(LocalDate serviceDate) implements Selector {
			public ServiceDateSelector { serviceDate = Objects.requireNonNull(serviceDate, "serviceDate"); }
			@Override public DayType resolvedDayType() { return DayType.from(serviceDate); }
		}
		record DayTypeSelector(DayType dayType, LocalDate referenceDate) implements Selector {
			public DayTypeSelector { dayType = Objects.requireNonNull(dayType, "dayType"); referenceDate = Objects.requireNonNull(referenceDate, "referenceDate"); }
			@Override public DayType resolvedDayType() { return DayType.from(referenceDate); }
		}
		record NextDeparturesSelector(Instant asOf, int horizonDays) implements Selector {
			public NextDeparturesSelector { asOf = Objects.requireNonNull(asOf, "asOf"); if (horizonDays < 1 || horizonDays > 8) throw failure(Failure.INVALID_JOURNEY_REQUEST); }
			@Override public DayType resolvedDayType() { return DayType.from(asOf.atZone(SERVICE_ZONE).toLocalDate()); }
		}
	}

	public enum DayType {
		WEEKDAY, SATURDAY, SUNDAY_HOLIDAY;
		static DayType from(LocalDate date) {
			return switch (date.getDayOfWeek()) {
				case SATURDAY -> SATURDAY; case SUNDAY -> SUNDAY_HOLIDAY; default -> WEEKDAY;
			};
		}
	}
	public record SearchResult(String stationId, String lineId, Selector selector, DayType resolvedDayType,
		List<DirectionGroup> directionGroups, SourceIdentity sourceIdentity) { }
	public record DirectionGroup(String directionName, List<Departure> departures) { }
	public record Departure(String directionName, LocalDate serviceDate, int secondsFromServiceDayStart,
		Instant departureAt, String servicePattern, String serviceClass) { }
	public record SourceIdentity(String timetableArtifactId, String timetableSnapshotSha256, String canonicalStationVersion,
		String canonicalStationSetSha256, String sourceLineageSha256, String evidenceHash, Instant freshUntil) { }
	private record DepartureCandidate(String directionName, TransitTrip trip, int secondsFromServiceDayStart) { }
	public enum Failure { INVALID_JOURNEY_REQUEST, STATION_LINE_NOT_FOUND, TIMETABLE_NOT_COVERED, TIMETABLE_UNAVAILABLE, TIMETABLE_STALE, TIMETABLE_IDENTITY_MISMATCH }
	public static final class FailureException extends RuntimeException {
		private final Failure failure;
		public FailureException(Failure failure) { super(failure.name()); this.failure = failure; }
		public Failure failure() { return failure; }
	}

	private static <T> Map<String, T> uniqueById(List<T> values, java.util.function.Function<T, String> id) {
		Map<String, T> result = new HashMap<>();
		for (T value : values) if (result.put(id.apply(value), value) != null) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		return result;
	}
}
