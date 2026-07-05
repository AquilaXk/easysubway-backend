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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class RouteTimetableRaptorPlanner {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int SERVICE_DAY_CUTOFF_HOUR = 3;
	private static final int PARETO_LIMIT = 3;
	private static final int ENTRY_DURATION_SECONDS = 240;
	private static final int ENTRY_DISTANCE_METERS = 180;
	private static final int TRANSFER_DURATION_SECONDS = 360;
	private static final int TRANSFER_DISTANCE_METERS = 260;
	private static final int EXIT_DURATION_SECONDS = 180;
	private static final int EXIT_DISTANCE_METERS = 120;

	List<RouteSearchResult> search(SearchRouteV2Command command, RouteTimetable timetable) {
		ServiceDay serviceDay = serviceDay(command);
		return scanDestinationLabels(command, timetable, serviceDay, serviceDay.departureSeconds()).labels().stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.limit(Math.min(command.alternativeCount(), PARETO_LIMIT))
			.map(label -> toRouteSearchResult(command, label, serviceDay))
			.toList();
	}

	Optional<OffsetDateTime> nextServiceTime(SearchRouteV2Command command, RouteTimetable timetable) {
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
		RouteTimetable timetable,
		LocalDate serviceDate,
		int startSeconds
	) {
		List<ScheduledTrip> trips = activeScheduledTrips(timetable, serviceDate);
		Map<String, List<BoardingStop>> boardingsByStation = boardingsByStation(trips);
		Map<ReachabilityState, Boolean> reachabilityCache = new HashMap<>();
		Integer firstDepartureSeconds = null;
		int entrySeconds = profiledWalkSeconds(command, ENTRY_DURATION_SECONDS);
		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		for (BoardingStop boardingStop : boardingsByStation.getOrDefault(command.originStationId(), List.of())) {
			TransitStopTime stopTime = boardingStop.stopTime();
			if (!allowsPickup(stopTime)
				|| stopTime.departureSeconds() < startSeconds + entrySeconds + slackSeconds) {
				continue;
			}
			if (canReachDestinationAfterBoarding(
				command,
				boardingsByStation,
				boardingStop.trip(),
				boardingStop.stopIndex(),
				0,
				new HashSet<>(),
				reachabilityCache
			)) {
				firstDepartureSeconds = firstDepartureSeconds == null
					? stopTime.departureSeconds()
					: Math.min(firstDepartureSeconds, stopTime.departureSeconds());
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
			if (!allowsDropOff(stopTime)) {
				continue;
			}
			if (command.destinationStationId().equals(stopTime.stationId())) {
				return true;
			}
			if (canReachDestinationAfterAlighting(
				command,
				boardingsByStation,
				stopTime.stationId(),
				stopTime.arrivalSeconds(),
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
				TransitStopTime stopTime = boardingStop.stopTime();
				if (!allowsPickup(stopTime)
					|| stopTime.departureSeconds() < readySeconds + transferSeconds + slackSeconds) {
					continue;
				}
				if (canReachDestinationAfterBoarding(
					command,
					boardingsByStation,
					boardingStop.trip(),
					boardingStop.stopIndex(),
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
		RouteTimetable timetable,
		ServiceDay serviceDay,
		int startSeconds
	) {
		List<ScheduledTrip> trips = activeScheduledTrips(timetable, serviceDay.date());
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
			.limit(PARETO_LIMIT)
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
		for (TransitStopTime stopTime : trip.stopTimes()) {
			for (Label label : List.copyOf(labels.getOrDefault(stopTime.stationId(), List.of()))) {
				if (canBoard(command, label, stopTime, round) && allowsPickup(stopTime)) {
					boarding = betterBoarding(boarding, label, stopTime);
				}
			}
			if (boarding == null || stopTime.stopSequence() <= boarding.stopTime().stopSequence() || !allowsDropOff(stopTime)) {
				continue;
			}
			addLabel(labels, new Label(
				stopTime.stationId(),
				stopTime.arrivalSeconds(),
				boarding.label().startSeconds(),
				boarding.label().boardings() + 1,
				withLeg(boarding.label().path(), new RideLeg(trip.trip(), trip.route(), boarding.stopTime(), stopTime))
			));
		}
	}

	private boolean canBoard(SearchRouteV2Command command, Label label, TransitStopTime stopTime, int round) {
		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		int accessSeconds = profiledWalkSeconds(
			command,
			label.boardings() > 0 ? TRANSFER_DURATION_SECONDS : ENTRY_DURATION_SECONDS
		);
		return label.boardings() == round && stopTime.departureSeconds() >= label.timeSeconds() + accessSeconds + slackSeconds;
	}

	private Boarding betterBoarding(Boarding current, Label label, TransitStopTime stopTime) {
		if (current == null || label.timeSeconds() < current.label().timeSeconds()) {
			return new Boarding(label, stopTime);
		}
		return current;
	}

	private boolean allowsPickup(TransitStopTime stopTime) {
		return stopTime.pickupType() != 1;
	}

	private boolean allowsDropOff(TransitStopTime stopTime) {
		return stopTime.dropOffType() != 1;
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
		labels.put(candidate.stationId(), List.copyOf(kept.stream().limit(PARETO_LIMIT).toList()));
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

	private static RouteSearchResult toRouteSearchResult(SearchRouteV2Command command, Label label, ServiceDay serviceDay) {
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
			waitMinutesBeforeBoarding(label.startSeconds(), firstLeg.from().departureSeconds(), entryDurationSeconds, boardingSlackSeconds),
			ENTRY_DISTANCE_METERS,
			entryDurationSeconds
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
					waitMinutesBeforeBoarding(previousLeg.to().arrivalSeconds(), leg.from().departureSeconds(), transferDurationSeconds, boardingSlackSeconds),
					TRANSFER_DISTANCE_METERS,
					transferDurationSeconds
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
				Math.max(1, (int) Math.ceil((leg.to().arrivalSeconds() - leg.from().departureSeconds()) / 60.0)),
				0,
				false,
				"UNKNOWN",
				false,
				EtaSource.PLANNED.name(),
				"TIMETABLE",
				"시간표"
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
			exitDurationSeconds
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
			LocalDateTime.of(serviceDay.date(), java.time.LocalTime.MIDNIGHT).plusSeconds(label.startSeconds())
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
		int walkSeconds
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
			walkSeconds
		);
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
				.append(leg.from().departureSeconds())
				.append('-')
				.append(leg.to().arrivalSeconds());
		}
		return Integer.toUnsignedString(key.toString().hashCode(), 36);
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
			return List.of(new ScheduledTrip(trip, route, stopTimes));
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
					.ifPresent(shifted -> scheduledTrips.add(new ScheduledTrip(trip, route, shifted)));
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

	private List<ScheduledTrip> activeScheduledTrips(RouteTimetable timetable, LocalDate serviceDate) {
		Set<String> activeServiceIds = activeServiceIds(timetable, serviceDate);
		if (activeServiceIds.isEmpty()) {
			return List.of();
		}
		Map<String, TransitRoute> routesById = routesById(timetable);
		Map<String, List<TransitStopTime>> stopTimesByTrip = stopTimesByTrip(timetable);
		Map<String, List<TransitFrequency>> frequenciesByTrip = frequenciesByTrip(timetable);
		return timetable.transitTrips().stream()
			.filter(trip -> activeServiceIds.contains(trip.serviceId()))
			.filter(trip -> stopTimesByTrip.getOrDefault(trip.id(), List.of()).size() > 1)
			.flatMap(trip -> scheduledTrips(trip, routesById.get(trip.routeId()), stopTimesByTrip.get(trip.id()), frequenciesByTrip.getOrDefault(trip.id(), List.of())).stream())
			.sorted(Comparator.comparing((ScheduledTrip scheduledTrip) -> scheduledTrip.trip().id())
				.thenComparingInt(scheduledTrip -> scheduledTrip.stopTimes().getFirst().departureSeconds()))
			.toList();
	}

	private Map<String, List<BoardingStop>> boardingsByStation(List<ScheduledTrip> trips) {
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
				.sorted(Comparator.comparingInt(boarding -> boarding.stopTime().departureSeconds()))
				.toList());
		}
		return Map.copyOf(boardings);
	}

	private static Set<String> activeServiceIds(RouteTimetable timetable, LocalDate serviceDate) {
		Set<String> active = new HashSet<>();
		for (ServiceCalendar calendar : timetable.serviceCalendars()) {
			if (!serviceDate.isBefore(calendar.startDate())
				&& !serviceDate.isAfter(calendar.endDate())
				&& runsOn(calendar, serviceDate)) {
				active.add(calendar.serviceId());
			}
		}
		for (ServiceCalendarDate exception : timetable.serviceCalendarDates()) {
			if (!serviceDate.equals(exception.date())) {
				continue;
			}
			if (exception.exceptionType() == 1) {
				active.add(exception.serviceId());
			} else {
				active.remove(exception.serviceId());
			}
		}
		return active;
	}

	private static boolean runsOn(ServiceCalendar calendar, LocalDate serviceDate) {
		return switch (serviceDate.getDayOfWeek()) {
			case MONDAY -> calendar.monday();
			case TUESDAY -> calendar.tuesday();
			case WEDNESDAY -> calendar.wednesday();
			case THURSDAY -> calendar.thursday();
			case FRIDAY -> calendar.friday();
			case SATURDAY -> calendar.saturday();
			case SUNDAY -> calendar.sunday();
		};
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

	private record Label(String stationId, int timeSeconds, int startSeconds, int boardings, List<RideLeg> path) {
	}

	private record Boarding(Label label, TransitStopTime stopTime) {
	}

	private record ScheduledTrip(TransitTrip trip, TransitRoute route, List<TransitStopTime> stopTimes) {
	}

	private record BoardingStop(ScheduledTrip trip, int stopIndex, TransitStopTime stopTime) {
	}

	private record ReachabilityState(String stationId, int readySeconds, int transfersUsed) {
	}

	private record RideLeg(TransitTrip trip, TransitRoute route, TransitStopTime from, TransitStopTime to) {
		String tripId() {
			return trip.id();
		}

		String lineId() {
			return route == null ? from.lineId() : route.lineId();
		}

		String lineName() {
			if (route == null) {
				return from.lineId();
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
