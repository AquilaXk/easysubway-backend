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
import java.util.Arrays;
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
	private static final int LABEL_SLOT_COUNT = PARETO_LIMIT + 1;
	private static final int UNREACHED = Integer.MAX_VALUE;
	private static final int[] NO_PATTERNS = new int[0];
	private final ThreadLocal<ScanWorkspace> scanWorkspaces = ThreadLocal.withInitial(ScanWorkspace::new);

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

	ScanMetrics lastScanMetrics() {
		ScanWorkspace workspace = scanWorkspaces.get();
		return new ScanMetrics(
			workspace.expandedRoutes,
			workspace.expandedTrips,
			System.identityHashCode(workspace)
		);
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
		ActiveServiceDay activeServiceDay = timetable.activeServiceDay(serviceDay.date());
		ScanWorkspace workspace = scanWorkspaces.get();
		workspace.prepare(timetable.stationCount(), timetable.routePatternCount());
		if (activeServiceDay.trips().isEmpty()) {
			return new ScanResult(serviceDay, List.of());
		}
		int origin = timetable.stationIndex(command.originStationId());
		int destination = timetable.stationIndex(command.destinationStationId());
		if (origin < 0 || destination < 0) {
			return new ScanResult(serviceDay, List.of());
		}
		workspace.arrivalSeconds[workspace.slot(0, origin)] = startSeconds;
		workspace.mark(origin);

		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		int entrySeconds = profiledWalkSeconds(command, ENTRY_DURATION_SECONDS);
		int transferSeconds = profiledWalkSeconds(command, TRANSFER_DURATION_SECONDS);
		for (int round = 0; round <= command.maxTransfers() && workspace.markedStopCount > 0; round += 1) {
			collectMarkedPatterns(timetable, workspace);
			Arrays.sort(workspace.markedPatterns, 0, workspace.markedPatternCount);
			for (int index = 0; index < workspace.markedPatternCount; index += 1) {
				int pattern = workspace.markedPatterns[index];
				scanPattern(
					timetable,
					activeServiceDay,
					workspace,
					pattern,
					workspace.firstMarkedPosition[pattern],
					round,
					round == 0 ? entrySeconds : transferSeconds,
					slackSeconds
				);
			}
			workspace.finishRound();
		}

		List<Label> destinationLabels = destinationLabels(
			command.destinationStationId(), timetable, workspace, destination, startSeconds)
			.stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.limit(candidateLimit(command))
			.toList();
		return new ScanResult(serviceDay, destinationLabels);
	}

	private static void collectMarkedPatterns(CompiledTimetable timetable, ScanWorkspace workspace) {
		for (int index = 0; index < workspace.markedStopCount; index += 1) {
			int station = workspace.markedStops[index];
			for (int pattern : timetable.patternsByStop(station)) {
				int position = indexOf(timetable.stopsByPattern(pattern), station);
				if (workspace.firstMarkedPosition[pattern] < 0) {
					workspace.markedPatterns[workspace.markedPatternCount++] = pattern;
					workspace.firstMarkedPosition[pattern] = position;
				} else if (position < workspace.firstMarkedPosition[pattern]) {
					workspace.firstMarkedPosition[pattern] = position;
				}
			}
		}
	}

	private static int indexOf(int[] values, int target) {
		for (int index = 0; index < values.length; index += 1) {
			if (values[index] == target) {
				return index;
			}
		}
		return -1;
	}

	private static void scanPattern(
		CompiledTimetable timetable,
		ActiveServiceDay activeServiceDay,
		ScanWorkspace workspace,
		int pattern,
		int firstMarkedPosition,
		int round,
		int accessSeconds,
		int slackSeconds
	) {
		workspace.expandedRoutes += 1;
		List<ScheduledTrip> trips = activeServiceDay.tripsByPattern(pattern);
		if (trips.isEmpty()) {
			return;
		}
		int[] stops = timetable.stopsByPattern(pattern);
		ScheduledTrip boardedTrip = null;
		int boardingPosition = -1;
		int boardingReadySeconds = UNREACHED;
		for (int position = firstMarkedPosition; position < stops.length; position += 1) {
			int station = stops[position];
			if (boardedTrip != null && position > boardingPosition && boardedTrip.allowsDropOff(position)) {
				workspace.relax(
					station,
					round + 1,
					boardedTrip.arrivalSeconds(position),
					boardedTrip.index(),
					boardingPosition,
					position
				);
			}
			int readySeconds = workspace.arrivalSeconds[workspace.slot(round, station)];
			if (readySeconds == UNREACHED) {
				continue;
			}
			int earliestDepartureSeconds = readySeconds + accessSeconds + slackSeconds;
			if (boardedTrip != null
				&& readySeconds < boardingReadySeconds
				&& boardedTrip.allowsPickup(position)
				&& boardedTrip.departureSeconds(position) >= earliestDepartureSeconds) {
				boardingPosition = position;
				boardingReadySeconds = readySeconds;
			}
			ScheduledTrip candidate = earliestBoardableTrip(
				trips,
				position,
				earliestDepartureSeconds
			);
			if (candidate != null && (boardedTrip == null
				|| candidate.departureSeconds(position) < boardedTrip.departureSeconds(position)
				|| (candidate != boardedTrip
					&& candidate.departureSeconds(position) == boardedTrip.departureSeconds(position)
					&& candidate.arrivalSeconds(position) <= boardedTrip.arrivalSeconds(position)))) {
				boardedTrip = candidate;
				boardingPosition = position;
				boardingReadySeconds = readySeconds;
				workspace.expandedTrips += 1;
			}
		}
	}

	private static ScheduledTrip earliestBoardableTrip(
		List<ScheduledTrip> trips,
		int stopPosition,
		int earliestDepartureSeconds
	) {
		int low = 0;
		int high = trips.size();
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (trips.get(middle).departureSeconds(stopPosition) < earliestDepartureSeconds) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		while (low < trips.size()) {
			ScheduledTrip trip = trips.get(low++);
			if (trip.allowsPickup(stopPosition)) {
				return trip;
			}
		}
		return null;
	}

	private static List<Label> destinationLabels(
		String destinationStationId,
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int destination,
		int startSeconds
	) {
		List<Label> labels = new ArrayList<>(PARETO_LIMIT);
		for (int boardings = 1; boardings <= PARETO_LIMIT; boardings += 1) {
			int slot = workspace.slot(boardings, destination);
			if (workspace.arrivalSeconds[slot] == UNREACHED) {
				continue;
			}
			List<RideLeg> path = new ArrayList<>(boardings);
			int station = destination;
			int currentBoardings = boardings;
			while (currentBoardings > 0) {
				int currentSlot = workspace.slot(currentBoardings, station);
				ScheduledTrip trip = timetable.scheduledTrip(workspace.parentTrip[currentSlot]);
				int boardingPosition = workspace.parentBoardStop[currentSlot];
				int alightingPosition = workspace.parentAlightStop[currentSlot];
				path.add(new RideLeg(trip, boardingPosition, alightingPosition));
				station = timetable.stationIndex(trip.stopTimes().get(boardingPosition).stationId());
				currentBoardings -= 1;
			}
			java.util.Collections.reverse(path);
			labels.add(new Label(
				destinationStationId,
				workspace.arrivalSeconds[slot],
				startSeconds,
				boardings,
				List.copyOf(path)
			));
		}
		return labels;
	}

	private static int candidateLimit(SearchRouteV2Command command) {
		return Math.max(command.alternativeCount(), command.maxTransfers() + 1);
	}

	private static int compareLabels(Label left, Label right) {
		return Comparator.comparingInt(Label::timeSeconds)
			.thenComparingInt(Label::boardings)
			.thenComparingInt(label -> label.path().size())
			.compare(left, right);
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
			return List.of(new ScheduledTrip(-1, trip, route, stopTimes, new PrimitiveTripTimes(stopTimes)));
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
						new ScheduledTrip(-1, trip, route, shifted, new PrimitiveTripTimes(shifted))));
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
			for (int index = 0; index < compiledTrips.size(); index += 1) {
				compiledTrips.set(index, compiledTrips.get(index).withIndex(index));
			}
			scheduledTrips = List.copyOf(compiledTrips);
			CompiledRoutePatterns routePatterns = compileRoutePatterns(scheduledTrips, stationIndex);
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

		int stationIndex(String stationId) {
			return stationIndex.getOrDefault(stationId, -1);
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

		int[] stopsByPattern(int pattern) {
			return stopsByPattern.get(pattern);
		}

		int[] patternsByStop(int station) {
			return patternsByStop.getOrDefault(station, NO_PATTERNS);
		}

		ScheduledTrip scheduledTrip(int index) {
			return scheduledTrips.get(index);
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
			Map<Integer, List<ScheduledTrip>> activeTripsByPattern = new HashMap<>();
			for (Map.Entry<Integer, List<ScheduledTrip>> entry : tripsByPattern.entrySet()) {
				List<ScheduledTrip> patternTrips = entry.getValue().stream()
					.filter(trip -> activeServiceIds.contains(trip.trip().serviceId()))
					.toList();
				if (!patternTrips.isEmpty()) {
					activeTripsByPattern.put(entry.getKey(), patternTrips);
				}
			}
			ActiveServiceDay compiled = new ActiveServiceDay(activeTrips, Map.copyOf(activeTripsByPattern));
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
			Map<String, Integer> stationIndex
		) {
			Map<RoutePatternKey, List<ScheduledTrip>> groupedTrips = new LinkedHashMap<>();
			Map<Integer, int[]> stopsByPattern = new HashMap<>();
			Map<Integer, List<ScheduledTrip>> tripsByPattern = new HashMap<>();
			for (ScheduledTrip trip : scheduledTrips) {
				if (trip.stopTimes().isEmpty()) {
					continue;
				}
				List<Integer> stationSequence = trip.stopTimes().stream()
					.map(stopTime -> stationIndex.get(stopTime.stationId()))
					.toList();
				List<Integer> accessSignature = trip.stopTimes().stream()
					.map(stopTime -> (stopTime.pickupType() << 16) | (stopTime.dropOffType() & 0xffff))
					.toList();
				RoutePatternKey key = new RoutePatternKey(trip.trip().routeId(), stationSequence, accessSignature);
				groupedTrips.computeIfAbsent(key, ignored -> new ArrayList<>()).add(trip);
			}
			for (Map.Entry<RoutePatternKey, List<ScheduledTrip>> entry : groupedTrips.entrySet()) {
				List<List<ScheduledTrip>> nonOvertakingGroups = new ArrayList<>();
				List<ScheduledTrip> orderedTrips = entry.getValue().stream()
					.sorted(Comparator.comparingInt((ScheduledTrip trip) -> trip.departureSeconds(0))
						.thenComparingInt(ScheduledTrip::index))
					.toList();
				for (ScheduledTrip trip : orderedTrips) {
					List<ScheduledTrip> selectedGroup = null;
					for (List<ScheduledTrip> group : nonOvertakingGroups) {
						if (canShareScanPattern(group.getLast(), trip)) {
							selectedGroup = group;
							break;
						}
					}
					if (selectedGroup == null) {
						selectedGroup = new ArrayList<>();
						nonOvertakingGroups.add(selectedGroup);
					}
					selectedGroup.add(trip);
				}
				for (List<ScheduledTrip> group : nonOvertakingGroups) {
					int patternId = stopsByPattern.size();
					stopsByPattern.put(
						patternId,
						entry.getKey().stationSequence().stream().mapToInt(Integer::intValue).toArray()
					);
					tripsByPattern.put(patternId, List.copyOf(group));
				}
			}
			return new CompiledRoutePatterns(Map.copyOf(stopsByPattern), Map.copyOf(tripsByPattern));
		}

		private static boolean canShareScanPattern(ScheduledTrip earlier, ScheduledTrip later) {
			for (int stop = 0; stop < earlier.stopTimes().size(); stop += 1) {
				if (earlier.arrivalSeconds(stop) > later.arrivalSeconds(stop)
					|| earlier.departureSeconds(stop) > later.departureSeconds(stop)
					|| (stop > 0
						&& earlier.arrivalSeconds(stop) == later.arrivalSeconds(stop)
						&& later.index() < earlier.index())) {
					return false;
				}
			}
			return true;
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
		private final Map<Integer, List<ScheduledTrip>> tripsByPattern;
		private volatile Map<String, List<BoardingStop>> boardingsByStation;

		private ActiveServiceDay(List<ScheduledTrip> trips, Map<Integer, List<ScheduledTrip>> tripsByPattern) {
			this.trips = List.copyOf(trips);
			this.tripsByPattern = tripsByPattern;
		}

		private List<ScheduledTrip> trips() {
			return trips;
		}

		private List<ScheduledTrip> tripsByPattern(int pattern) {
			return tripsByPattern.getOrDefault(pattern, List.of());
		}

		int routePatternTripLinkCount() {
			return tripsByPattern.values().stream()
				.mapToInt(List::size)
				.sum();
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

	private static final class ScanWorkspace {

		private int stationCount;
		private int[] arrivalSeconds = new int[0];
		private int[] parentTrip = new int[0];
		private int[] parentBoardStop = new int[0];
		private int[] parentAlightStop = new int[0];
		private int[] markedStops = new int[0];
		private int[] nextMarkedStops = new int[0];
		private boolean[] marked = new boolean[0];
		private boolean[] nextMarked = new boolean[0];
		private int markedStopCount;
		private int nextMarkedStopCount;
		private int[] markedPatterns = new int[0];
		private int[] firstMarkedPosition = new int[0];
		private int markedPatternCount;
		private int expandedRoutes;
		private int expandedTrips;

		private void prepare(int requiredStationCount, int patternCount) {
			stationCount = requiredStationCount;
			int labelSlots = Math.multiplyExact(requiredStationCount, LABEL_SLOT_COUNT);
			if (arrivalSeconds.length < labelSlots) {
				arrivalSeconds = new int[labelSlots];
				parentTrip = new int[labelSlots];
				parentBoardStop = new int[labelSlots];
				parentAlightStop = new int[labelSlots];
			}
			if (markedStops.length < requiredStationCount) {
				markedStops = new int[requiredStationCount];
				nextMarkedStops = new int[requiredStationCount];
				marked = new boolean[requiredStationCount];
				nextMarked = new boolean[requiredStationCount];
			}
			if (markedPatterns.length < patternCount) {
				markedPatterns = new int[patternCount];
				firstMarkedPosition = new int[patternCount];
			}
			Arrays.fill(arrivalSeconds, 0, labelSlots, UNREACHED);
			Arrays.fill(parentTrip, 0, labelSlots, -1);
			Arrays.fill(parentBoardStop, 0, labelSlots, -1);
			Arrays.fill(parentAlightStop, 0, labelSlots, -1);
			Arrays.fill(marked, 0, requiredStationCount, false);
			Arrays.fill(nextMarked, 0, requiredStationCount, false);
			Arrays.fill(firstMarkedPosition, 0, patternCount, -1);
			markedStopCount = 0;
			nextMarkedStopCount = 0;
			markedPatternCount = 0;
			expandedRoutes = 0;
			expandedTrips = 0;
		}

		private int slot(int boardings, int station) {
			return boardings * stationCount + station;
		}

		private void mark(int station) {
			if (!marked[station]) {
				marked[station] = true;
				markedStops[markedStopCount++] = station;
			}
		}

		private void relax(
			int station,
			int boardings,
			int candidateArrivalSeconds,
			int trip,
			int boardStop,
			int alightStop
		) {
			int candidateSlot = slot(boardings, station);
			int existingArrivalSeconds = arrivalSeconds[candidateSlot];
			if (existingArrivalSeconds < candidateArrivalSeconds) {
				return;
			}
			if (existingArrivalSeconds == candidateArrivalSeconds
				&& (parentTrip[candidateSlot] < trip
					|| (parentTrip[candidateSlot] == trip && parentBoardStop[candidateSlot] <= boardStop))) {
				return;
			}
			for (int fewerBoardings = 0; fewerBoardings < boardings; fewerBoardings += 1) {
				if (arrivalSeconds[slot(fewerBoardings, station)] <= candidateArrivalSeconds) {
					return;
				}
			}
			arrivalSeconds[candidateSlot] = candidateArrivalSeconds;
			parentTrip[candidateSlot] = trip;
			parentBoardStop[candidateSlot] = boardStop;
			parentAlightStop[candidateSlot] = alightStop;
			if (!nextMarked[station]) {
				nextMarked[station] = true;
				nextMarkedStops[nextMarkedStopCount++] = station;
			}
		}

		private void finishRound() {
			for (int index = 0; index < markedStopCount; index += 1) {
				marked[markedStops[index]] = false;
			}
			for (int index = 0; index < markedPatternCount; index += 1) {
				firstMarkedPosition[markedPatterns[index]] = -1;
			}
			int[] oldMarkedStops = markedStops;
			markedStops = nextMarkedStops;
			nextMarkedStops = oldMarkedStops;
			boolean[] oldMarked = marked;
			marked = nextMarked;
			nextMarked = oldMarked;
			markedStopCount = nextMarkedStopCount;
			nextMarkedStopCount = 0;
			markedPatternCount = 0;
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

	record ScanMetrics(int expandedRoutes, int expandedTrips, int workspaceIdentity) {
	}

	private record RoutePatternKey(
		String routeId,
		List<Integer> stationSequence,
		List<Integer> accessSignature
	) {
	}

	private record CompiledRoutePatterns(
		Map<Integer, int[]> stopsByPattern,
		Map<Integer, List<ScheduledTrip>> tripsByPattern
	) {
	}

	private record Label(String stationId, int timeSeconds, int startSeconds, int boardings, List<RideLeg> path) {
	}

	private record ScheduledTrip(
		int index,
		TransitTrip trip,
		TransitRoute route,
		List<TransitStopTime> stopTimes,
		PrimitiveTripTimes times
	) {
		private ScheduledTrip withIndex(int denseIndex) {
			return new ScheduledTrip(denseIndex, trip, route, stopTimes, times);
		}

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
