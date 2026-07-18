package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.domain.BoardingSlackPolicy;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteSearchResult.OfficialFare;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

class RouteTimetableRaptorPlanner {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int SERVICE_DAY_CUTOFF_HOUR = 3;
	private static final int PARETO_LIMIT = 4;
	private static final int ENTRY_DURATION_SECONDS = 240;
	private static final int ENTRY_DISTANCE_METERS = 180;
	private static final int TRANSFER_DURATION_SECONDS = 360;
	private static final int TRANSFER_DISTANCE_METERS = 260;
	private static final int EXIT_DURATION_SECONDS = 180;
	private static final int EXIT_DISTANCE_METERS = 120;
	private static final int ACTIVE_SERVICE_DAY_CACHE_SIZE = 8;

	List<RouteSearchResult> search(SearchRouteV2Command command, RouteTimetable timetable) {
		return search(command, compile(timetable));
	}

	List<RouteSearchResult> search(SearchRouteV2Command command, CompiledTimetable timetable) {
		ServiceDay serviceDay = serviceDay(command);
		return scanDestinationLabels(command, timetable, serviceDay, serviceDay.departureSeconds()).labels().stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.limit(candidateLimit(command))
			.map(label -> toRouteSearchResult(command, label, serviceDay, timetable.source()))
			.toList();
	}

	boolean isFeedStale(SearchRouteV2Command command, RouteTimetable timetable) {
		return isFeedStale(command, compile(timetable));
	}

	boolean isFeedStale(SearchRouteV2Command command, CompiledTimetable timetable) {
		LocalDate feedEndDate = timetable.source().feedEndDate();
		return feedEndDate != null && serviceDay(command).date().isAfter(feedEndDate);
	}

	Optional<OffsetDateTime> nextServiceTime(SearchRouteV2Command command, RouteTimetable timetable) {
		return nextServiceTime(command, compile(timetable));
	}

	Optional<OffsetDateTime> nextServiceTime(SearchRouteV2Command command, CompiledTimetable timetable) {
		ServiceDay serviceDay = serviceDay(command);
		for (int dayOffset = 0; dayOffset <= 7; dayOffset += 1) {
			LocalDate candidateServiceDate = serviceDay.date().plusDays(dayOffset);
			int startSeconds = candidateServiceDateStartSeconds(command, candidateServiceDate);
			Optional<Integer> departureSeconds = firstFeasibleDepartureSeconds(
				command,
				timetable,
				candidateServiceDate,
				startSeconds
			);
			if (departureSeconds.isPresent()) {
				return Optional.of(candidateServiceDate.atStartOfDay(SERVICE_ZONE)
					.plusSeconds(departureSeconds.get())
					.toOffsetDateTime());
			}
		}
		return Optional.empty();
	}

	private static int candidateServiceDateStartSeconds(SearchRouteV2Command command, LocalDate candidateServiceDate) {
		long seconds = Duration.between(
			candidateServiceDate.atStartOfDay(SERVICE_ZONE),
			command.departureTime().atZoneSameInstant(SERVICE_ZONE)
		).toSeconds();
		if (seconds >= LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE) {
			return LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE;
		}
		return Math.toIntExact(seconds);
	}

	private Optional<Integer> firstFeasibleDepartureSeconds(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		LocalDate serviceDate,
		int startSeconds
	) {
		Map<String, List<BoardingStop>> boardingsByStation = timetable.activeServiceDay(serviceDate).boardingsByStation();
		Map<ReachabilityState, Boolean> reachabilityCache = new HashMap<>();
		Integer firstDepartureSeconds = null;
		int entrySeconds = profiledWalkSeconds(command, ENTRY_DURATION_SECONDS);
		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		for (BoardingStop boardingStop : boardingsByStation.getOrDefault(command.originStationId(), List.of())) {
			ScheduledTrip trip = boardingStop.trip();
			int stopIndex = boardingStop.stopIndex();
			int departureSeconds = trip.departureSeconds(stopIndex);
			if (!trip.allowsPickup(stopIndex)
				|| departureSeconds < startSeconds + entrySeconds + slackSeconds) {
				continue;
			}
			if (canReachDestinationAfterBoarding(
				command,
				boardingsByStation,
				trip,
				stopIndex,
				0,
				new HashSet<>(),
				reachabilityCache
			)) {
				firstDepartureSeconds = firstDepartureSeconds == null
					? departureSeconds
					: Math.min(firstDepartureSeconds, departureSeconds);
			}
		}
		return Optional.ofNullable(firstDepartureSeconds);
	}

	private boolean canReachDestinationAfterBoarding(
		SearchRouteV2Command command,
		Map<String, List<BoardingStop>> boardingsByStation,
		ScheduledTrip trip,
		int boardingStopIndex,
		int transfersUsed,
		Set<ReachabilityState> visiting,
		Map<ReachabilityState, Boolean> reachabilityCache
	) {
		List<TransitStopTime> stopTimes = trip.stopTimes();
		for (int stopIndex = boardingStopIndex + 1; stopIndex < stopTimes.size(); stopIndex += 1) {
			TransitStopTime stopTime = stopTimes.get(stopIndex);
			if (!trip.allowsDropOff(stopIndex)) {
				continue;
			}
			if (command.destinationStationId().equals(stopTime.stationId())) {
				return true;
			}
			if (canReachDestinationAfterAlighting(
				command,
				boardingsByStation,
				stopTime.stationId(),
				trip.arrivalSeconds(stopIndex),
				transfersUsed,
				visiting,
				reachabilityCache
			)) {
				return true;
			}
		}
		return false;
	}

	private boolean canReachDestinationAfterAlighting(
		SearchRouteV2Command command,
		Map<String, List<BoardingStop>> boardingsByStation,
		String stationId,
		int readySeconds,
		int transfersUsed,
		Set<ReachabilityState> visiting,
		Map<ReachabilityState, Boolean> reachabilityCache
	) {
		if (transfersUsed >= command.maxTransfers()) {
			return false;
		}
		ReachabilityState state = new ReachabilityState(stationId, readySeconds, transfersUsed);
		Boolean cached = reachabilityCache.get(state);
		if (cached != null) {
			return cached;
		}
		if (!visiting.add(state)) {
			return false;
		}
		int transferSeconds = profiledWalkSeconds(command, TRANSFER_DURATION_SECONDS);
		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		try {
			for (BoardingStop boardingStop : boardingsByStation.getOrDefault(stationId, List.of())) {
				ScheduledTrip trip = boardingStop.trip();
				int stopIndex = boardingStop.stopIndex();
				if (!trip.allowsPickup(stopIndex)
					|| trip.departureSeconds(stopIndex) < readySeconds + transferSeconds + slackSeconds) {
					continue;
				}
				if (canReachDestinationAfterBoarding(
					command,
					boardingsByStation,
					trip,
					stopIndex,
					transfersUsed + 1,
					visiting,
					reachabilityCache
				)) {
					reachabilityCache.put(state, true);
					return true;
				}
			}
			reachabilityCache.put(state, false);
			return false;
		} finally {
			visiting.remove(state);
		}
	}

	private ScanResult scanDestinationLabels(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		ServiceDay serviceDay,
		int startSeconds
	) {
		List<ScheduledTrip> trips = timetable.activeServiceDay(serviceDay.date()).trips();
		if (trips.isEmpty()) {
			return new ScanResult(serviceDay, List.of());
		}

		Map<String, List<Label>> labels = new HashMap<>();
		labels.put(command.originStationId(), List.of(new Label(
			command.originStationId(),
			startSeconds,
			startSeconds,
			0,
			List.of()
		)));

		for (int round = 0; round <= command.maxTransfers(); round += 1) {
			for (ScheduledTrip trip : trips) {
				scanTrip(command, labels, trip, round);
			}
		}

		List<Label> destinationLabels = labels.getOrDefault(command.destinationStationId(), List.of()).stream()
			.filter(label -> !label.path().isEmpty())
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.limit(candidateLimit(command))
			.toList();
		return new ScanResult(serviceDay, destinationLabels);
	}

	private void scanTrip(
		SearchRouteV2Command command,
		Map<String, List<Label>> labels,
		ScheduledTrip trip,
		int round
	) {
		Boarding boarding = null;
		List<TransitStopTime> stopTimes = trip.stopTimes();
		for (int stopIndex = 0; stopIndex < stopTimes.size(); stopIndex += 1) {
			TransitStopTime stopTime = stopTimes.get(stopIndex);
			for (Label label : List.copyOf(labels.getOrDefault(stopTime.stationId(), List.of()))) {
				if (canBoard(command, label, trip, stopIndex, round) && trip.allowsPickup(stopIndex)) {
					boarding = betterBoarding(boarding, label, stopIndex);
				}
			}
			if (boarding == null || stopIndex <= boarding.stopIndex() || !trip.allowsDropOff(stopIndex)) {
				continue;
			}
			addLabel(labels, new Label(
				stopTime.stationId(),
				trip.arrivalSeconds(stopIndex),
				boarding.label().startSeconds(),
				boarding.label().boardings() + 1,
				withLeg(boarding.label().path(), new RideLeg(trip, boarding.stopIndex(), stopIndex))
			));
		}
	}

	private boolean canBoard(SearchRouteV2Command command, Label label, ScheduledTrip trip, int stopIndex, int round) {
		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		int accessSeconds = profiledWalkSeconds(
			command,
			label.boardings() > 0 ? TRANSFER_DURATION_SECONDS : ENTRY_DURATION_SECONDS
		);
		return label.boardings() == round
			&& trip.departureSeconds(stopIndex) >= label.timeSeconds() + accessSeconds + slackSeconds;
	}

	private Boarding betterBoarding(Boarding current, Label label, int stopIndex) {
		if (current == null || label.timeSeconds() < current.label().timeSeconds()) {
			return new Boarding(label, stopIndex);
		}
		return current;
	}

	private void addLabel(Map<String, List<Label>> labels, Label candidate) {
		List<Label> stationLabels = labels.getOrDefault(candidate.stationId(), List.of());
		if (stationLabels.stream().anyMatch(existing -> sameLabel(existing, candidate) || dominates(existing, candidate))) {
			return;
		}
		List<Label> kept = new ArrayList<>();
		for (Label existing : stationLabels) {
			if (!dominates(candidate, existing)) {
				kept.add(existing);
			}
		}
		kept.add(candidate);
		kept.sort(RouteTimetableRaptorPlanner::compareLabels);
		List<Label> bestByBoardings = new ArrayList<>();
		for (Label label : kept) {
			if (bestByBoardings.stream().noneMatch(existing -> existing.boardings() == label.boardings())) {
				bestByBoardings.add(label);
			}
		}
		labels.put(candidate.stationId(), List.copyOf(bestByBoardings.stream().limit(PARETO_LIMIT).toList()));
	}

	private static int candidateLimit(SearchRouteV2Command command) {
		return Math.max(command.alternativeCount(), command.maxTransfers() + 1);
	}

	private static boolean dominates(Label left, Label right) {
		return left.timeSeconds() <= right.timeSeconds()
			&& left.boardings() <= right.boardings()
			&& (left.timeSeconds() < right.timeSeconds() || left.boardings() < right.boardings());
	}

	private static boolean sameLabel(Label left, Label right) {
		return left.timeSeconds() == right.timeSeconds()
			&& left.boardings() == right.boardings()
			&& left.path().stream().map(RideLeg::tripId).toList().equals(right.path().stream().map(RideLeg::tripId).toList());
	}

	private static int compareLabels(Label left, Label right) {
		return Comparator.comparingInt(Label::timeSeconds)
			.thenComparingInt(Label::boardings)
			.thenComparingInt(label -> label.path().size())
			.compare(left, right);
	}

	private static List<RideLeg> withLeg(List<RideLeg> path, RideLeg leg) {
		List<RideLeg> next = new ArrayList<>(path);
		next.add(leg);
		return List.copyOf(next);
	}

	private static RouteSearchResult toRouteSearchResult(
		SearchRouteV2Command command,
		Label label,
		ServiceDay serviceDay,
		RouteTimetable timetable
	) {
		List<RouteStep> steps = new ArrayList<>();
		int sequence = 1;
		int boardingSlackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		int entryDurationSeconds = profiledWalkSeconds(command, ENTRY_DURATION_SECONDS);
		int transferDurationSeconds = profiledWalkSeconds(command, TRANSFER_DURATION_SECONDS);
		int exitDurationSeconds = profiledWalkSeconds(command, EXIT_DURATION_SECONDS);
		List<RideLeg> path = label.path();
		RideLeg firstLeg = path.getFirst();
		RideLeg lastLeg = path.getLast();
		steps.add(timetableAccessStep(
			sequence,
			"entry",
			command.originStationId(),
			firstLeg.from().stationId(),
			firstLeg.lineId(),
			firstLeg.lineName(),
			waitMinutesBeforeBoarding(label.startSeconds(), firstLeg.departureSeconds(), entryDurationSeconds, boardingSlackSeconds),
			ENTRY_DISTANCE_METERS,
			entryDurationSeconds,
			serviceTime(serviceDay, label.startSeconds()),
			serviceTime(serviceDay, firstLeg.departureSeconds())
		));
		sequence += 1;
		for (int index = 0; index < path.size(); index += 1) {
			RideLeg leg = path.get(index);
			if (index > 0) {
				RideLeg previousLeg = path.get(index - 1);
				steps.add(timetableAccessStep(
					sequence,
					"transfer",
					previousLeg.to().stationId(),
					leg.from().stationId(),
					leg.lineId(),
					leg.lineName(),
					waitMinutesBeforeBoarding(previousLeg.arrivalSeconds(), leg.departureSeconds(), transferDurationSeconds, boardingSlackSeconds),
					TRANSFER_DISTANCE_METERS,
					transferDurationSeconds,
					serviceTime(serviceDay, previousLeg.arrivalSeconds()),
					serviceTime(serviceDay, leg.departureSeconds())
				));
				sequence += 1;
			}
			String lineName = leg.lineName();
			steps.add(new RouteStep(
				sequence,
				"ride",
				lineName + " 승차",
				leg.from().stationId() + "에서 " + leg.to().stationId() + "까지 시간표 기준으로 이동",
				leg.lineId(),
				lineName,
				leg.from().stationId(),
				leg.to().stationId(),
				Math.max(1, (int) Math.ceil((leg.arrivalSeconds() - leg.departureSeconds()) / 60.0)),
				0,
				false,
				"UNKNOWN",
				false,
				EtaSource.PLANNED.name(),
				"TIMETABLE",
				"시간표",
				List.of(),
				null,
				null,
				null,
				null,
				null,
				leg.tripId(),
				leg.trip().trainNo(),
				leg.trip().serviceClass(),
				leg.trip().servicePattern(),
				serviceTime(serviceDay, leg.departureSeconds()),
				serviceTime(serviceDay, leg.arrivalSeconds())
			));
			sequence += 1;
		}
		steps.add(timetableAccessStep(
			sequence,
			"exit",
			lastLeg.to().stationId(),
			command.destinationStationId(),
			lastLeg.lineId(),
			lastLeg.lineName(),
			(int) Math.ceil(exitDurationSeconds / 60.0),
			EXIT_DISTANCE_METERS,
			exitDurationSeconds,
			serviceTime(serviceDay, lastLeg.arrivalSeconds()),
			serviceTime(serviceDay, lastLeg.arrivalSeconds() + exitDurationSeconds)
		));
		return new RouteSearchResult(
			"route-v2-raptor-" + serviceDay.date() + "-" + command.originStationId() + "-" + command.destinationStationId()
				+ "-" + label.timeSeconds() + "-" + pathDiscriminator(label.path()),
			command.originStationId(),
			command.originStationId(),
			command.destinationStationId(),
			command.destinationStationId(),
			command.mobilityType(),
			RouteSearchStatus.FOUND,
			label.path().getFirst().lineId(),
			label.path().getFirst().lineName(),
			Math.max(1, (label.timeSeconds() - label.startSeconds()) / 60),
			List.copyOf(steps),
			List.of(),
			List.of(),
			LocalDateTime.of(serviceDay.date(), java.time.LocalTime.MIDNIGHT).plusSeconds(label.startSeconds()),
			List.of(),
			officialFare(timetable, path)
		);
	}

	private static OfficialFare officialFare(RouteTimetable timetable, List<RideLeg> path) {
		List<LoadRouteTimetablePort.OfficialFare> selected = new ArrayList<>();
		for (RideLeg leg : path) {
			var fare = timetable.officialFares().stream()
				.filter(candidate -> candidate.tripId().equals(leg.tripId()))
				.filter(candidate -> candidate.originStationId().equals(leg.from().stationId()))
				.filter(candidate -> candidate.destinationStationId().equals(leg.to().stationId()))
				.findFirst();
			if (fare.isEmpty()) {
				return null;
			}
			selected.add(fare.get());
		}
		return new OfficialFare(
			selected.stream().mapToInt(LoadRouteTimetablePort.OfficialFare::adultFareWon).sum(),
			"KRW",
			"SUM_OF_OFFICIAL_RIDE_OD_FARES",
			selected.stream().map(LoadRouteTimetablePort.OfficialFare::sourceId).distinct().sorted().toList(),
			selected.stream().map(LoadRouteTimetablePort.OfficialFare::sourceSnapshotId).distinct().sorted().toList()
		);
	}

	private static int profiledWalkSeconds(SearchRouteV2Command command, int baselineSeconds) {
		return ProfileWalkTimeCalculator.estimateSeconds(
			baselineSeconds,
			command.mobilityPreset(),
			WalkTimeSource.OFFICIAL_BASELINE,
			false
		).seconds();
	}

	private static RouteStep timetableAccessStep(
		int sequence,
		String stepType,
		String fromStationId,
		String toStationId,
		String lineId,
		String lineName,
		int estimatedMinutes,
		int distanceMeters,
		int walkSeconds,
		String plannedDepartureTime,
		String plannedArrivalTime
	) {
		return new RouteStep(
			sequence,
			stepType,
			lineName + " 접근 동선 확인",
			"시간표 경로의 승하차 접근성과 환승 동선을 확인합니다.",
			lineId,
			lineName,
			fromStationId,
			toStationId,
			estimatedMinutes,
			distanceMeters,
			false,
			"UNKNOWN",
			true,
			EtaSource.PLANNED.name(),
			"TIMETABLE",
			"시간표",
			List.of(),
			null,
			null,
			null,
			null,
			walkSeconds,
			null,
			null,
			null,
			null,
			plannedDepartureTime,
			plannedArrivalTime
		);
	}

	private static String serviceTime(ServiceDay serviceDay, int seconds) {
		return serviceDay.date().atStartOfDay(SERVICE_ZONE)
			.plusSeconds(seconds)
			.toOffsetDateTime()
			.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	private static int waitMinutesBeforeBoarding(
		int readySeconds,
		int departureSeconds,
		int movementSeconds,
		int slackSeconds
	) {
		int waitSeconds = Math.max(movementSeconds + slackSeconds, departureSeconds - readySeconds);
		return (int) Math.ceil(waitSeconds / 60.0);
	}

	private static String pathDiscriminator(List<RideLeg> path) {
		StringBuilder key = new StringBuilder();
		for (RideLeg leg : path) {
			if (!key.isEmpty()) {
				key.append('>');
			}
			key.append(leg.tripId())
				.append('@')
				.append(leg.departureSeconds())
				.append('-')
				.append(leg.arrivalSeconds());
		}
		return Integer.toUnsignedString(key.toString().hashCode(), 36);
	}

	CompiledTimetable compile(RouteTimetable timetable) {
		return new CompiledTimetable(timetable);
	}

	private static Map<String, TransitRoute> routesById(RouteTimetable timetable) {
		Map<String, TransitRoute> routes = new HashMap<>();
		for (TransitRoute route : timetable.transitRoutes()) {
			routes.put(route.id(), route);
		}
		return routes;
	}

	private static Map<String, List<TransitStopTime>> stopTimesByTrip(RouteTimetable timetable) {
		Map<String, List<TransitStopTime>> stopTimes = new HashMap<>();
		for (TransitStopTime stopTime : timetable.transitStopTimes()) {
			stopTimes.computeIfAbsent(stopTime.tripId(), ignored -> new ArrayList<>()).add(stopTime);
		}
		for (Map.Entry<String, List<TransitStopTime>> entry : stopTimes.entrySet()) {
			entry.setValue(entry.getValue().stream()
				.sorted(Comparator.comparingInt(TransitStopTime::stopSequence))
				.toList());
		}
		return stopTimes;
	}

	private static Map<String, List<TransitFrequency>> frequenciesByTrip(RouteTimetable timetable) {
		Map<String, List<TransitFrequency>> frequencies = new HashMap<>();
		for (TransitFrequency frequency : timetable.transitFrequencies()) {
			frequencies.computeIfAbsent(frequency.tripId(), ignored -> new ArrayList<>()).add(frequency);
		}
		return frequencies;
	}

	private static List<ScheduledTrip> scheduledTrips(
		TransitTrip trip,
		TransitRoute route,
		List<TransitStopTime> stopTimes,
		List<TransitFrequency> frequencies
	) {
		if (frequencies.isEmpty()) {
			return List.of(new ScheduledTrip(trip, route, stopTimes, new PrimitiveTripTimes(stopTimes)));
		}
		int firstDepartureSeconds = stopTimes.getFirst().departureSeconds();
		List<ScheduledTrip> scheduledTrips = new ArrayList<>();
		for (TransitFrequency frequency : frequencies) {
			if (frequency.headwaySeconds() <= 0) {
				continue;
			}
			for (int departureSeconds = frequency.startTimeSeconds();
				 departureSeconds < frequency.endTimeSeconds();
				 departureSeconds += frequency.headwaySeconds()) {
				shiftedStopTimes(stopTimes, departureSeconds - firstDepartureSeconds)
					.ifPresent(shifted -> scheduledTrips.add(
						new ScheduledTrip(trip, route, shifted, new PrimitiveTripTimes(shifted))));
			}
		}
		return List.copyOf(scheduledTrips);
	}

	private static java.util.Optional<List<TransitStopTime>> shiftedStopTimes(List<TransitStopTime> stopTimes, int offsetSeconds) {
		List<TransitStopTime> shifted = new ArrayList<>();
		for (TransitStopTime stopTime : stopTimes) {
			int arrivalSeconds = stopTime.arrivalSeconds() + offsetSeconds;
			int departureSeconds = stopTime.departureSeconds() + offsetSeconds;
			if (arrivalSeconds < 0
				|| departureSeconds < 0
				|| arrivalSeconds >= LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE
				|| departureSeconds >= LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE) {
				return java.util.Optional.empty();
			}
			shifted.add(new TransitStopTime(
				stopTime.tripId(),
				stopTime.stopSequence(),
				stopTime.stationId(),
				stopTime.lineId(),
				arrivalSeconds,
				departureSeconds,
				stopTime.pickupType(),
				stopTime.dropOffType()
			));
		}
		return java.util.Optional.of(List.copyOf(shifted));
	}

	private static Map<String, List<BoardingStop>> boardingsByStation(List<ScheduledTrip> trips) {
		Map<String, List<BoardingStop>> boardings = new HashMap<>();
		for (ScheduledTrip trip : trips) {
			List<TransitStopTime> stopTimes = trip.stopTimes();
			for (int stopIndex = 0; stopIndex < stopTimes.size(); stopIndex += 1) {
				TransitStopTime stopTime = stopTimes.get(stopIndex);
				boardings.computeIfAbsent(stopTime.stationId(), ignored -> new ArrayList<>())
					.add(new BoardingStop(trip, stopIndex, stopTime));
			}
		}
		for (Map.Entry<String, List<BoardingStop>> entry : boardings.entrySet()) {
			entry.setValue(entry.getValue().stream()
				.sorted(Comparator.comparingInt(
					boarding -> boarding.trip().departureSeconds(boarding.stopIndex())))
				.toList());
		}
		return Map.copyOf(boardings);
	}

	private static boolean runsOn(ServiceCalendar calendar, DayOfWeek dayOfWeek) {
		return switch (dayOfWeek) {
			case MONDAY -> calendar.monday();
			case TUESDAY -> calendar.tuesday();
			case WEDNESDAY -> calendar.wednesday();
			case THURSDAY -> calendar.thursday();
			case FRIDAY -> calendar.friday();
			case SATURDAY -> calendar.saturday();
			case SUNDAY -> calendar.sunday();
		};
	}

	static final class CompiledTimetable {

		private final RouteTimetable source;
		private final Map<String, Integer> stationIndex;
		private final Map<String, Integer> routeIndex;
		private final Map<String, Integer> tripIndex;
		private final Map<Integer, int[]> stopsByPattern;
		private final Map<Integer, int[]> patternsByStop;
		private final Map<Integer, List<ScheduledTrip>> tripsByPattern;
		private final Map<DayOfWeek, List<ServiceCalendar>> calendarsByDay;
		private final Map<LocalDate, List<ServiceCalendarDate>> exceptionsByDate;
		private final List<ScheduledTrip> scheduledTrips;
		private final LinkedHashMap<LocalDate, ActiveServiceDay> activeServiceDays = new LinkedHashMap<>(16, 0.75f, true);

		private CompiledTimetable(RouteTimetable source) {
			this.source = Objects.requireNonNull(source, "timetable must not be null");
			stationIndex = denseIndex(source.transitStopTimes().stream().map(TransitStopTime::stationId).toList());
			routeIndex = denseIndex(source.transitRoutes().stream().map(TransitRoute::id).toList());
			tripIndex = denseIndex(source.transitTrips().stream().map(TransitTrip::id).toList());

			Map<String, TransitRoute> routesById = routesById(source);
			Map<String, List<TransitStopTime>> stopTimesByTrip = stopTimesByTrip(source);
			Map<String, List<TransitFrequency>> frequenciesByTrip = frequenciesByTrip(source);
			List<ScheduledTrip> compiledTrips = new ArrayList<>();
			for (TransitTrip trip : source.transitTrips()) {
				List<TransitStopTime> stopTimes = stopTimesByTrip.getOrDefault(trip.id(), List.of());
				if (stopTimes.size() < 2) {
					continue;
				}
				compiledTrips.addAll(scheduledTrips(
					trip,
					routesById.get(trip.routeId()),
					stopTimes,
					frequenciesByTrip.getOrDefault(trip.id(), List.of())
				));
			}
			compiledTrips.sort(Comparator.comparing((ScheduledTrip scheduledTrip) -> scheduledTrip.trip().id())
				.thenComparingInt(scheduledTrip -> scheduledTrip.departureSeconds(0)));
			scheduledTrips = List.copyOf(compiledTrips);
			CompiledRoutePatterns routePatterns = compileRoutePatterns(scheduledTrips, routeIndex, stationIndex);
			stopsByPattern = routePatterns.stopsByPattern();
			patternsByStop = invertPatterns(stopsByPattern, stationIndex.size());
			tripsByPattern = routePatterns.tripsByPattern();
			calendarsByDay = compileCalendarsByDay(source.serviceCalendars());
			exceptionsByDate = Map.copyOf(source.serviceCalendarDates().stream().collect(
				java.util.stream.Collectors.groupingBy(
					ServiceCalendarDate::date,
					java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), List::copyOf)
				)
			));
		}

		RouteTimetable source() {
			return source;
		}

		int stationCount() {
			return stationIndex.size();
		}

		Set<String> coveredStationIds() {
			return stationIndex.keySet();
		}

		int routeCount() {
			return routeIndex.size();
		}

		int tripCount() {
			return tripIndex.size();
		}

		int routePatternCount() {
			return stopsByPattern.size();
		}

		int routePatternTripLinkCount() {
			return tripsByPattern.values().stream().mapToInt(List::size).sum();
		}

		int scheduledTripCount() {
			return scheduledTrips.size();
		}

		int primitiveTimeArrayCount() {
			return Math.toIntExact(scheduledTrips.stream().filter(trip -> trip.times() != null).count());
		}

		synchronized ActiveServiceDay activeServiceDay(LocalDate serviceDate) {
			ActiveServiceDay cached = activeServiceDays.get(serviceDate);
			if (cached != null) {
				return cached;
			}
			Set<String> activeServiceIds = activeServiceIds(serviceDate);
			List<ScheduledTrip> activeTrips = scheduledTrips.stream()
				.filter(trip -> activeServiceIds.contains(trip.trip().serviceId()))
				.toList();
			ActiveServiceDay compiled = new ActiveServiceDay(activeTrips);
			activeServiceDays.put(serviceDate, compiled);
			if (activeServiceDays.size() > ACTIVE_SERVICE_DAY_CACHE_SIZE) {
				activeServiceDays.remove(activeServiceDays.sequencedKeySet().getFirst());
			}
			return compiled;
		}

		synchronized int activeServiceDayCacheSize() {
			return activeServiceDays.size();
		}

		synchronized boolean isServiceDayCached(LocalDate serviceDate) {
			return activeServiceDays.containsKey(serviceDate);
		}

		int activeTripCount(LocalDate serviceDate) {
			return activeServiceDay(serviceDate).trips().size();
		}

		private Set<String> activeServiceIds(LocalDate serviceDate) {
			Set<String> active = new HashSet<>();
			for (ServiceCalendar calendar : calendarsByDay.getOrDefault(serviceDate.getDayOfWeek(), List.of())) {
				if (!serviceDate.isBefore(calendar.startDate()) && !serviceDate.isAfter(calendar.endDate())) {
					active.add(calendar.serviceId());
				}
			}
			for (ServiceCalendarDate exception : exceptionsByDate.getOrDefault(serviceDate, List.of())) {
				if (exception.exceptionType() == 1) {
					active.add(exception.serviceId());
				} else {
					active.remove(exception.serviceId());
				}
			}
			return active;
		}

		private static Map<String, Integer> denseIndex(List<String> ids) {
			Map<String, Integer> index = new HashMap<>();
			ids.stream().distinct().sorted().forEach(id -> index.put(id, index.size()));
			return Map.copyOf(index);
		}

		private static CompiledRoutePatterns compileRoutePatterns(
			List<ScheduledTrip> scheduledTrips,
			Map<String, Integer> routeIndex,
			Map<String, Integer> stationIndex
		) {
			Map<RoutePatternKey, Integer> patternIds = new LinkedHashMap<>();
			Map<Integer, int[]> stopsByPattern = new HashMap<>();
			Map<Integer, List<ScheduledTrip>> tripsByPattern = new HashMap<>();
			for (ScheduledTrip trip : scheduledTrips) {
				Integer denseRoute = routeIndex.get(trip.trip().routeId());
				if (denseRoute == null || trip.stopTimes().isEmpty()) {
					continue;
				}
				List<Integer> stationSequence = trip.stopTimes().stream()
					.map(stopTime -> stationIndex.get(stopTime.stationId()))
					.toList();
				RoutePatternKey key = new RoutePatternKey(denseRoute, stationSequence);
				int patternId = patternIds.computeIfAbsent(key, ignored -> patternIds.size());
				stopsByPattern.putIfAbsent(
					patternId,
					stationSequence.stream().mapToInt(Integer::intValue).toArray()
				);
				tripsByPattern.computeIfAbsent(patternId, ignored -> new ArrayList<>()).add(trip);
			}
			tripsByPattern.replaceAll((ignored, trips) -> trips.stream()
				.sorted(Comparator.comparingInt(trip -> trip.departureSeconds(0)))
				.toList());
			return new CompiledRoutePatterns(Map.copyOf(stopsByPattern), Map.copyOf(tripsByPattern));
		}

		private static Map<Integer, int[]> invertPatterns(Map<Integer, int[]> stopsByPattern, int stationCount) {
			List<List<Integer>> patternLists = new ArrayList<>(stationCount);
			for (int index = 0; index < stationCount; index += 1) {
				patternLists.add(new ArrayList<>());
			}
			for (Map.Entry<Integer, int[]> entry : stopsByPattern.entrySet()) {
				for (int station : entry.getValue()) {
					List<Integer> patterns = patternLists.get(station);
					if (!patterns.contains(entry.getKey())) {
						patterns.add(entry.getKey());
					}
				}
			}
			Map<Integer, int[]> patternsByStop = new HashMap<>();
			for (int index = 0; index < patternLists.size(); index += 1) {
				if (!patternLists.get(index).isEmpty()) {
					patternsByStop.put(
						index,
						patternLists.get(index).stream().mapToInt(Integer::intValue).sorted().toArray()
					);
				}
			}
			return Map.copyOf(patternsByStop);
		}

		private static Map<DayOfWeek, List<ServiceCalendar>> compileCalendarsByDay(List<ServiceCalendar> calendars) {
			Map<DayOfWeek, List<ServiceCalendar>> byDay = new EnumMap<>(DayOfWeek.class);
			for (DayOfWeek day : DayOfWeek.values()) {
				byDay.put(day, calendars.stream().filter(calendar -> runsOn(calendar, day)).toList());
			}
			return Map.copyOf(byDay);
		}
	}

	static final class ActiveServiceDay {

		private final List<ScheduledTrip> trips;
		private volatile Map<String, List<BoardingStop>> boardingsByStation;

		private ActiveServiceDay(List<ScheduledTrip> trips) {
			this.trips = List.copyOf(trips);
		}

		private List<ScheduledTrip> trips() {
			return trips;
		}

		boolean boardingIndexInitialized() {
			return boardingsByStation != null;
		}

		private Map<String, List<BoardingStop>> boardingsByStation() {
			Map<String, List<BoardingStop>> snapshot = boardingsByStation;
			if (snapshot != null) {
				return snapshot;
			}
			synchronized (this) {
				snapshot = boardingsByStation;
				if (snapshot == null) {
					snapshot = RouteTimetableRaptorPlanner.boardingsByStation(trips);
					boardingsByStation = snapshot;
				}
				return snapshot;
			}
		}
	}

	private static final class PrimitiveTripTimes {

		private final int[] arrivalSeconds;
		private final int[] departureSeconds;
		private final byte[] pickupTypes;
		private final byte[] dropOffTypes;

		private PrimitiveTripTimes(List<TransitStopTime> stopTimes) {
			arrivalSeconds = new int[stopTimes.size()];
			departureSeconds = new int[stopTimes.size()];
			pickupTypes = new byte[stopTimes.size()];
			dropOffTypes = new byte[stopTimes.size()];
			for (int index = 0; index < stopTimes.size(); index += 1) {
				TransitStopTime stopTime = stopTimes.get(index);
				arrivalSeconds[index] = stopTime.arrivalSeconds();
				departureSeconds[index] = stopTime.departureSeconds();
				pickupTypes[index] = (byte) stopTime.pickupType();
				dropOffTypes[index] = (byte) stopTime.dropOffType();
			}
		}

		private int arrivalSeconds(int stopIndex) {
			return arrivalSeconds[stopIndex];
		}

		private int departureSeconds(int stopIndex) {
			return departureSeconds[stopIndex];
		}

		private boolean allowsPickup(int stopIndex) {
			return pickupTypes[stopIndex] != 1;
		}

		private boolean allowsDropOff(int stopIndex) {
			return dropOffTypes[stopIndex] != 1;
		}
	}

	private static ServiceDay serviceDay(SearchRouteV2Command command) {
		ZonedDateTime departure = command.departureTime().atZoneSameInstant(SERVICE_ZONE);
		LocalDate serviceDate = departure.toLocalDate();
		if (departure.getHour() < SERVICE_DAY_CUTOFF_HOUR) {
			serviceDate = serviceDate.minusDays(1);
		}
		int departureSeconds = Math.toIntExact(Duration.between(
			serviceDate.atStartOfDay(SERVICE_ZONE),
			departure
		).toSeconds());
		return new ServiceDay(serviceDate, departureSeconds);
	}

	private record ServiceDay(LocalDate date, int departureSeconds) {
	}

	private record ScanResult(ServiceDay serviceDay, List<Label> labels) {
	}

	private record RoutePatternKey(int routeIndex, List<Integer> stationSequence) {
	}

	private record CompiledRoutePatterns(
		Map<Integer, int[]> stopsByPattern,
		Map<Integer, List<ScheduledTrip>> tripsByPattern
	) {
	}

	private record Label(String stationId, int timeSeconds, int startSeconds, int boardings, List<RideLeg> path) {
	}

	private record Boarding(Label label, int stopIndex) {
	}

	private record ScheduledTrip(
		TransitTrip trip,
		TransitRoute route,
		List<TransitStopTime> stopTimes,
		PrimitiveTripTimes times
	) {
		private int arrivalSeconds(int stopIndex) {
			return times.arrivalSeconds(stopIndex);
		}

		private int departureSeconds(int stopIndex) {
			return times.departureSeconds(stopIndex);
		}

		private boolean allowsPickup(int stopIndex) {
			return times.allowsPickup(stopIndex);
		}

		private boolean allowsDropOff(int stopIndex) {
			return times.allowsDropOff(stopIndex);
		}
	}

	private record BoardingStop(ScheduledTrip trip, int stopIndex, TransitStopTime stopTime) {
	}

	private record ReachabilityState(String stationId, int readySeconds, int transfersUsed) {
	}

	private record RideLeg(ScheduledTrip scheduledTrip, int fromIndex, int toIndex) {
		TransitTrip trip() {
			return scheduledTrip.trip();
		}

		TransitStopTime from() {
			return scheduledTrip.stopTimes().get(fromIndex);
		}

		TransitStopTime to() {
			return scheduledTrip.stopTimes().get(toIndex);
		}

		int departureSeconds() {
			return scheduledTrip.departureSeconds(fromIndex);
		}

		int arrivalSeconds() {
			return scheduledTrip.arrivalSeconds(toIndex);
		}

		String tripId() {
			return trip().id();
		}

		String lineId() {
			return scheduledTrip.route() == null ? from().lineId() : scheduledTrip.route().lineId();
		}

		String lineName() {
			TransitRoute route = scheduledTrip.route();
			if (route == null) {
				return from().lineId();
			}
			String routeLongName = route.routeLongName();
			if (routeLongName != null && !routeLongName.isBlank()) {
				return routeLongName;
			}
			String routeShortName = route.routeShortName();
			if (routeShortName != null && !routeShortName.isBlank()) {
				return routeShortName;
			}
			return route.lineId();
		}
	}
}
