package com.easysubway.journey.application;

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

/** Reads only the active server route bundle timetable and never synthesizes timetable success. */
public final class StationTimetableSearchService {

	public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
	private final StationTimetableSnapshotPort snapshotPort;
	private final Clock clock;

	public StationTimetableSearchService(StationTimetableSnapshotPort snapshotPort, Clock clock) {
		this.snapshotPort = Objects.requireNonNull(snapshotPort, "snapshotPort");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public SearchResult search(SearchRequest request) {
		Objects.requireNonNull(request, "request");
		StationTimetableSnapshotPort.StationTimetableSnapshot snapshot;
		try {
			snapshot = snapshotPort.loadStationTimetableSnapshot();
		} catch (FailureException exception) {
			// 활성 번들 없음·만료는 어댑터가 판정한 그대로 드러낸다. 그 밖의 실패는 사용할 수 없음이다.
			if (exception.failure() == Failure.TIMETABLE_STALE) throw failure(Failure.TIMETABLE_STALE);
			throw failure(Failure.TIMETABLE_UNAVAILABLE);
		} catch (RuntimeException exception) {
			throw failure(Failure.TIMETABLE_UNAVAILABLE);
		}
		if (snapshot == null || snapshot.index() == null) {
			throw failure(Failure.TIMETABLE_UNAVAILABLE);
		}
		if (snapshot.sourceIdentity() == null || snapshot.sourceIdentity().freshUntil() == null) {
			throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
		if (!snapshot.sourceIdentity().freshUntil().isAfter(clock.instant())) {
			throw failure(Failure.TIMETABLE_STALE);
		}
		SourceIdentity source = sourceIdentity(snapshot.sourceIdentity());
		StationTimetableIndex index = snapshot.index();
		RouteTimetable timetable = index.timetable();
		if (snapshot.canonicalStationLines() == null) {
			throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
		StationTimetableSnapshotPort.StationLine stationLine =
			new StationTimetableSnapshotPort.StationLine(request.stationId(), request.lineId());
		if (!snapshot.canonicalStationLines().contains(stationLine)) {
			throw failure(Failure.STATION_LINE_NOT_FOUND);
		}

		index.requireStructurallyValid();
		if (!index.covers(stationLine)) {
			throw failure(Failure.TIMETABLE_NOT_COVERED);
		}
		// #476 F3: 역·노선별 출발 후보는 세대 색인에 한 번 계산해 두고 재사용한다(요청마다 전국 정차 행을 훑지 않는다).
		List<DepartureCandidate> candidates = index.departures(stationLine, key -> departures(index, key));
		// 다음 정차역이 있는 출발만 시간표에 싣는다. 노선 끝 역처럼 출발이 하나도 없으면 시간표 범위 밖이다.
		if (candidates.isEmpty()) {
			throw failure(Failure.TIMETABLE_NOT_COVERED);
		}

		Set<String> servingServiceIds = new HashSet<>();
		for (DepartureCandidate candidate : candidates) servingServiceIds.add(candidate.trip().serviceId());
		LocalDate referenceDate = selectorServiceDate(request.selector());
		DayType resolvedDayType = resolveDayType(timetable, referenceDate, servingServiceIds);
		if (!(request.selector() instanceof Selector.NextDeparturesSelector)) {
			requireHolidayCalendarCoverage(timetable, referenceDate, servingServiceIds, resolvedDayType);
		}
		if (request.selector() instanceof Selector.DayTypeSelector dayType
			&& dayType.dayType() != resolvedDayType) {
			throw failure(Failure.INVALID_JOURNEY_REQUEST);
		}
		List<Departure> selected = select(candidates, timetable, request.selector());
		if (request.selector() instanceof Selector.NextDeparturesSelector) {
			// 다음 출발은 여러 서비스일에 걸치므로, 실제로 출발을 내는 서비스일마다 같은 기준을 적용한다.
			for (LocalDate serviceDate : selected.stream().map(Departure::serviceDate).distinct().toList()) {
				requireHolidayCalendarCoverage(timetable, serviceDate, servingServiceIds,
					resolveDayType(timetable, serviceDate, servingServiceIds));
			}
		}
		return new SearchResult(
			request.stationId(), request.lineId(), request.selector(), resolvedDayType,
			group(selected), source
		);
	}

	private static List<DepartureCandidate> departures(
		StationTimetableIndex index,
		StationTimetableSnapshotPort.StationLine stationLine
	) {
		String lineId = stationLine.lineId();
		List<DepartureCandidate> result = new ArrayList<>();
		for (TransitStopTime stop : index.stopsAt(stationLine)) {
			if (stop.pickupType() == 1) continue;
			TransitTrip trip = index.trip(stop.tripId());
			if (trip == null) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			TransitRoute route = index.route(trip.routeId());
			if (route == null || !lineId.equals(route.lineId()) || !SERVICE_ZONE.getId().equals(route.timezone())) {
				throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			}
			// 방향은 이 역 다음에 서는 역(다음 정차역)으로 묶는다. 종착역(headsign)으로 묶으면 2호선처럼 양방향이 같은
			// 종착역을 쓰는 노선에서 방향이 섞인다. 원천의 방면 이름은 값이 있을 때만 그대로 싣는다.
			List<TransitStopTime> tripStops = index.stopsOfTrip(stop.tripId());
			if (!tripStops.contains(stop)) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			TransitStopTime nextStop = null;
			TransitStopTime terminalStop = null;
			for (TransitStopTime candidate : tripStops) {
				if (candidate.stopSequence() > stop.stopSequence()
					&& (nextStop == null || candidate.stopSequence() < nextStop.stopSequence())) nextStop = candidate;
				if (terminalStop == null || candidate.stopSequence() > terminalStop.stopSequence()) terminalStop = candidate;
			}
			if (nextStop == null) continue;
			Direction direction = new Direction(nextStop.stationId(), terminalStop.stationId(),
				route.directionName() == null || route.directionName().isBlank() ? null : route.directionName());
			List<TransitFrequency> frequencies = index.frequenciesOfTrip(stop.tripId());
			if (frequencies.isEmpty()) {
				result.add(new DepartureCandidate(direction, trip, stop.departureSeconds()));
				continue;
			}
			// tripStops는 이 정차를 포함하므로 비어 있지 않다.
			int firstDeparture = java.util.Collections.min(tripStops, Comparator.comparingInt(TransitStopTime::stopSequence))
				.departureSeconds();
			for (TransitFrequency frequency : frequencies) {
				if (!frequency.exactTimes()) continue;
				for (int base = frequency.startTimeSeconds(); base < frequency.endTimeSeconds();) {
					int shift;
					try {
						shift = Math.subtractExact(base, firstDeparture);
					} catch (ArithmeticException exception) {
						throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
					}
					if (!validFrequencyInstance(index.stopsOfTrip(stop.tripId()), shift)) {
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
					result.add(new DepartureCandidate(direction, trip, departure));
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
		} else {
			Selector.NextDeparturesSelector next = (Selector.NextDeparturesSelector) selector;
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
				firstByDirection.merge(departure.nextStationId(), departure,
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
		if (timetable.feedEndDate() != null && serviceDate.isAfter(timetable.feedEndDate())) {
			throw failure(Failure.TIMETABLE_STALE);
		}
		Set<String> activeServiceIds = activeServices(timetable, serviceDate);
		for (DepartureCandidate candidate : candidates) {
			if (!activeServiceIds.contains(candidate.trip().serviceId())) continue;
			Instant departureAt = serviceDate.atStartOfDay(SERVICE_ZONE)
				.plusSeconds(candidate.secondsFromServiceDayStart()).toInstant();
			target.add(new Departure(
				candidate.direction().nextStationId(), candidate.direction().directionName(), serviceDate,
				candidate.secondsFromServiceDayStart(), departureAt, candidate.trip().servicePattern(),
				candidate.trip().serviceClass(), candidate.direction().terminalStationId()
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

	private static LocalDate selectorServiceDate(Selector selector) {
		return switch (selector) {
			case Selector.ServiceDateSelector value -> value.serviceDate();
			case Selector.DayTypeSelector value -> value.referenceDate();
			case Selector.NextDeparturesSelector value -> value.asOf().atZone(SERVICE_ZONE).toLocalDate();
		};
	}

	// #476 F1: 서비스일 라벨은 그 날 요청 역·노선에서 실제로 쓰는 달력의 종류다. 전국 번들에는 여러 기관의 달력이 함께
	// 있으므로 이 역·노선을 지나는 열차의 달력만 본다. 토요일 시간표가 없는 기관(토·일 운행 휴일 달력)은 토요일에도
	// SUNDAY_HOLIDAY다(시간표 요일 정책). 평일·주말을 함께 도는 달력은 날짜 자체의 요일을 쓴다. 서로 다른 종류의 달력이
	// 같은 날 함께 돌거나, 운행 중인 서비스의 달력이 없거나 종류를 정할 수 없으면 원천 불일치로 실패한다.
	private static DayType resolveDayType(RouteTimetable timetable, LocalDate serviceDate, Set<String> servingServiceIds) {
		DayType civil = DayType.from(serviceDate);
		// activeServices가 모든 달력의 시간대(Asia/Seoul)를 먼저 검사한다.
		Set<String> active = new HashSet<>(activeServices(timetable, serviceDate));
		Map<String, List<ServiceCalendar>> calendars = new HashMap<>();
		for (ServiceCalendar calendar : timetable.serviceCalendars()) {
			if (!servingServiceIds.contains(calendar.serviceId())) continue;
			calendars.computeIfAbsent(calendar.serviceId(), ignored -> new ArrayList<>()).add(calendar);
		}
		active.retainAll(servingServiceIds);
		if (active.isEmpty()) {
			// 이 역·노선의 서비스가 그 날 빠지기만 하고 대신 도는 서비스가 없으면 날짜 종류를 정할 수 없다.
			for (ServiceCalendarDate exception : timetable.serviceCalendarDates()) {
				if (serviceDate.equals(exception.date()) && exception.exceptionType() == 2
					&& servingServiceIds.contains(exception.serviceId())) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			}
			return civil;
		}
		Set<DayType> classes = new HashSet<>();
		for (String serviceId : active) {
			List<ServiceCalendar> matches = calendars.get(serviceId);
			if (matches == null || matches.size() != 1) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			ServiceCalendar calendar = matches.getFirst();
			DayType signature = calendarSignature(calendar);
			if (signature == null && runsOn(calendar, serviceDate.getDayOfWeek())) signature = civil;
			if (signature == null) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
			classes.add(signature);
		}
		if (classes.size() != 1) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		return classes.iterator().next();
	}

	// #476 F2: 번들의 다른 기관 달력이 그 날을 휴일로 표시(휴일 종류 달력 추가)했는데 이 역·노선 달력에는 그 날 예외가 하나도
	// 없고 휴일 시간표도 돌지 않으면, 평일·토요일 시간표를 성공으로 내지 않는다(fallback 금지). 근거는 번들에 이미 있는 예외뿐이며
	// 서버가 공휴일을 따로 조회하거나 추정하지 않는다. 기관 달력에 예외가 실리면(data#919) 자동으로 정상 응답한다.
	private static void requireHolidayCalendarCoverage(
		RouteTimetable timetable, LocalDate serviceDate, Set<String> servingServiceIds, DayType resolvedDayType
	) {
		if (resolvedDayType == DayType.SUNDAY_HOLIDAY) return;
		Map<String, Integer> calendarCounts = new HashMap<>();
		Map<String, ServiceCalendar> calendarsById = new HashMap<>();
		for (ServiceCalendar calendar : timetable.serviceCalendars()) {
			calendarCounts.merge(calendar.serviceId(), 1, Integer::sum);
			calendarsById.put(calendar.serviceId(), calendar);
		}
		boolean holidayElsewhere = false;
		for (ServiceCalendarDate exception : timetable.serviceCalendarDates()) {
			if (!serviceDate.equals(exception.date())) continue;
			if (servingServiceIds.contains(exception.serviceId())) return;
			if (exception.exceptionType() == 1 && calendarCounts.getOrDefault(exception.serviceId(), 0) == 1
				&& calendarSignature(calendarsById.get(exception.serviceId())) == DayType.SUNDAY_HOLIDAY) {
				holidayElsewhere = true;
			}
		}
		if (holidayElsewhere) {
			throw new FailureException(Failure.TIMETABLE_NOT_COVERED, FailureDetail.HOLIDAY_CALENDAR_EXCEPTION_MISSING);
		}
	}

	private static DayType calendarSignature(ServiceCalendar calendar) {
		if (calendar.monday() && calendar.tuesday() && calendar.wednesday() && calendar.thursday() && calendar.friday()
			&& !calendar.saturday() && !calendar.sunday()) return DayType.WEEKDAY;
		if (!calendar.monday() && !calendar.tuesday() && !calendar.wednesday() && !calendar.thursday() && !calendar.friday()
			&& calendar.saturday() && !calendar.sunday()) return DayType.SATURDAY;
		// 토요일 시간표가 없는 기관은 휴일 시간표를 토·일에 함께 쓴다(시간표 요일 정책). 그 달력도 휴일 서명이다.
		if (!calendar.monday() && !calendar.tuesday() && !calendar.wednesday() && !calendar.thursday() && !calendar.friday()
			&& calendar.sunday()) return DayType.SUNDAY_HOLIDAY;
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
			grouped.computeIfAbsent(departure.nextStationId(), ignored -> new ArrayList<>()).add(departure);
		}
		return grouped.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.map(entry -> new DirectionGroup(entry.getKey(), groupDirectionName(entry.getValue()), entry.getValue().stream()
				.sorted(Comparator.comparing(Departure::serviceDate).thenComparingInt(Departure::secondsFromServiceDayStart)).toList()))
			.toList();
	}

	// #476 설계 근거(F6): 원천 방면 이름은 그룹의 모든 출발이 같은 이름을 가질 때만 싣고, 모두 없으면 null이다.
	// 같은 다음 정차역 그룹에 이름 있는 열차와 없는 열차가 섞이거나 서로 다른 이름이 붙으면, 어느 쪽을 골라도 일부 열차의
	// 원천 값을 숨기거나 덮게 되므로 고르지 않고 원천 불일치로 실패한다(일관성 우선, 추정 금지).
	private static String groupDirectionName(List<Departure> departures) {
		Set<String> names = new HashSet<>();
		boolean missing = false;
		for (Departure departure : departures) {
			if (departure.directionName() == null) missing = true; else names.add(departure.directionName());
		}
		if (names.size() > 1 || missing && !names.isEmpty()) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		return names.isEmpty() ? null : names.iterator().next();
	}

	// #476 설계 근거(F6): 중복 출발 키는 (다음 정차역, 서비스일, 출발 초, 운행 유형, 운행 등급, 종착역)이다.
	// seq126 실측에서 1호선 7개 역(신도림 등)은 광명행과 인천행처럼 서로 다른 열차가 같은 초에 같은 다음 역으로 출발한다.
	// 종착역을 키에서 빼면 이 실제 열차들이 원천 중복으로 오판돼 역 전체가 503이 된다. 대가로 종착역만 다른 복제 행은
	// 중복으로 잡지 못하지만, 종착역까지 같은 행은 계속 원천 중복으로 실패시킨다.
	private static void validateDepartureOrderAndIdentity(List<Departure> departures) {
		Set<String> unique = new HashSet<>();
		for (Departure departure : departures) {
			String identity = departure.nextStationId() + "\u0000" + departure.serviceDate() + "\u0000"
				+ departure.secondsFromServiceDayStart() + "\u0000" + departure.servicePattern() + "\u0000" + departure.serviceClass()
				+ "\u0000" + departure.terminalStationId();
			if (!unique.add(identity)) throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
	}

	private static SourceIdentity sourceIdentity(SourceIdentity identity) {
		if (!text(identity.timetableArtifactId()) || !sha(identity.timetableSnapshotSha256())
			|| !text(identity.canonicalStationVersion()) || !sha(identity.canonicalStationSetSha256())
			|| !sha(identity.sourceLineageSha256()) || !sha(identity.evidenceHash())) {
			throw failure(Failure.TIMETABLE_IDENTITY_MISMATCH);
		}
		return identity;
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
		List<DirectionGroup> directionGroups, SourceIdentity sourceIdentity) {
		public SearchResult { directionGroups = List.copyOf(directionGroups); }
	}
	/** 다음 정차역 하나로 묶은 출발. {@code directionName}은 원천 방면 이름이 있을 때만 있고, 없으면 null이다. */
	public record DirectionGroup(String nextStationId, String directionName, List<Departure> departures) {
		public DirectionGroup { departures = List.copyOf(departures); }
	}
	public record Departure(String nextStationId, String directionName, LocalDate serviceDate, int secondsFromServiceDayStart,
		Instant departureAt, String servicePattern, String serviceClass, String terminalStationId) { }
	public record SourceIdentity(String timetableArtifactId, String timetableSnapshotSha256, String canonicalStationVersion,
		String canonicalStationSetSha256, String sourceLineageSha256, String evidenceHash, Instant freshUntil) { }
	private record Direction(String nextStationId, String terminalStationId, String directionName) { }
	private record DepartureCandidate(Direction direction, TransitTrip trip, int secondsFromServiceDayStart) { }
	public enum Failure { INVALID_JOURNEY_REQUEST, STATION_LINE_NOT_FOUND, TIMETABLE_NOT_COVERED, TIMETABLE_UNAVAILABLE, TIMETABLE_STALE, TIMETABLE_IDENTITY_MISMATCH }
	/** 같은 오류 코드 안에서 원인을 구분한다. 응답 형식(JourneyError)은 그대로이고 서버 로그로 남긴다. */
	public enum FailureDetail { NONE, HOLIDAY_CALENDAR_EXCEPTION_MISSING }
	public static final class FailureException extends RuntimeException {
		private final Failure failure;
		private final FailureDetail detail;
		public FailureException(Failure failure) { this(failure, FailureDetail.NONE); }
		public FailureException(Failure failure, FailureDetail detail) {
			super(detail == FailureDetail.NONE ? failure.name() : failure.name() + ":" + detail.name());
			this.failure = failure;
			this.detail = Objects.requireNonNull(detail, "detail");
		}
		public Failure failure() { return failure; }
		public FailureDetail detail() { return detail; }
	}
}
