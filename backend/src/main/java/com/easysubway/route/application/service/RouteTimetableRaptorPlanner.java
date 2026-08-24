package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeQuery;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableTripDeparture;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitFrequency;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.domain.BoardingSlackPolicy;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import com.easysubway.route.domain.RouteSearchResult.OfficialFare;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
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
	private static final int[] NO_TRANSITIONS = new int[0];
	private static final byte WARNING_LOW_CONFIDENCE = 1;
	private static final byte WARNING_STAIRS = 1 << 1;
	private static final byte WARNING_STALE = 1 << 2;
	private static final int WARNING_STATE_COUNT = 1 << 3;
	// #2534: PREFER_STEP_FREE의 선호 순서 — 계단 경고 부재가 1순위다(그 모드의 목적). 경고 0개
	// 후보가 없어도 유일한 무단차 후보가 뽑히도록 경고 수·시간은 그다음 키로 둔다. 표시 정렬과 별개다.
	private static final Comparator<Label> PREFERRED_WARNING_ORDER = Comparator
		.comparingInt((Label label) -> (label.warningBits() & WARNING_STAIRS) != 0 ? 1 : 0)
		.thenComparingInt(label -> warningCount(label.warningBits()))
		.thenComparingInt(Label::timeSeconds)
		.thenComparingInt(Label::boardings);
	private static final Label[] NO_WARNING_ALTERNATIVES = new Label[0];
	private static final int STRICT_PROFILE_MASK = profileMask(ConstraintMode.STRICT_STEP_FREE);
	private static final int PREFER_STEP_FREE_PROFILE_MASK = profileMask(ConstraintMode.PREFER_STEP_FREE);
	private static final int NON_STRICT_PROFILE_MASK = profileMask(
		ConstraintMode.PREFER_STEP_FREE, ConstraintMode.ALLOW_WITH_WARNINGS);
	private final ThreadLocal<ScanWorkspace> scanWorkspaces = ThreadLocal.withInitial(ScanWorkspace::new);

	List<RouteSearchResult> search(SearchRouteV2Command command, RouteTimetable timetable) {
		return search(command, compile(timetable));
	}

	List<RouteSearchResult> search(SearchRouteV2Command command, CompiledTimetable timetable) {
		return search(command, timetable, RealtimeOverlay.empty());
	}

	List<RouteSearchResult> search(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay
	) {
		ServiceDay serviceDay = serviceDay(command);
		return results(command, timetable, serviceDay, realtimeOverlay,
			scanDestinationLabels(command, timetable, serviceDay, serviceDay.departureSeconds(), false, realtimeOverlay));
	}

	List<JourneyItinerary> journeyItineraries(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay
	) {
		ServiceDay serviceDay = serviceDay(command);
		ScanResult scanResult = scanDestinationLabels(
			command, timetable, serviceDay, serviceDay.departureSeconds(), false, realtimeOverlay);
		return scanResult.labels().stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.limit(candidateLimit(command))
			.map(label -> toJourneyItinerary(command, timetable, serviceDay, realtimeOverlay, label))
			.toList();
	}

	SearchOutcome searchWithDiagnostics(SearchRouteV2Command command, CompiledTimetable timetable) {
		return searchWithDiagnostics(command, timetable, RealtimeOverlay.empty());
	}
	SearchOutcome searchWithDiagnostics(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay
	) {
		ServiceDay serviceDay = serviceDay(command);
		ScanResult found = scanDestinationLabels(
			command, timetable, serviceDay, serviceDay.departureSeconds(), false, realtimeOverlay);
		List<RouteSearchResult> itineraries = results(command, timetable, serviceDay, realtimeOverlay, found);
		if (!itineraries.isEmpty()) {
			return new SearchOutcome(itineraries, null);
		}
		ScanResult diagnostic = scanDestinationLabels(
			command, timetable, serviceDay, serviceDay.departureSeconds(), true, realtimeOverlay);
		if (diagnostic.labels().isEmpty()) {
			return new SearchOutcome(List.of(), null);
		}
		return new SearchOutcome(List.of(), blockedAccessibilityResult(command, serviceDay, diagnostic.labels().getFirst()));
	}
	private static List<RouteSearchResult> results(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		ServiceDay serviceDay,
		RealtimeOverlay realtimeOverlay,
		ScanResult scanResult
	) {
		return scanResult.labels().stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.limit(candidateLimit(command))
			.map(label -> toRouteSearchResult(command, label, serviceDay, timetable, realtimeOverlay))
			.toList();
	}

	private static JourneyItinerary toJourneyItinerary(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		ServiceDay serviceDay,
		RealtimeOverlay realtimeOverlay,
		Label label
	) {
		List<JourneyLegProjection> legs = new ArrayList<>();
		List<RideLeg> path = label.path();
		RideLeg firstRide = path.getFirst();
		int entryTransition = label.accessTransitions()[0];
		legs.add(journeyAccessLeg(
			JourneyAccessKind.ENTRY,
			command.originStationId(),
			firstRide.from().stationId(),
			command,
			timetable,
			entryTransition
		));
		for (int index = 0; index < path.size(); index += 1) {
			RideLeg ride = path.get(index);
			if (index > 0) {
				RideLeg previous = path.get(index - 1);
				legs.add(journeyAccessLeg(
					JourneyAccessKind.TRANSFER,
					previous.to().stationId(),
					ride.from().stationId(),
					command,
					timetable,
					label.accessTransitions()[index]
				));
			}
			RealtimeEvidence evidence = realtimeOverlay.evidence(ride.scheduledTrip());
			legs.add(new JourneyRideProjection(
				ride.lineId(),
				ride.tripId(),
				ride.scheduledTrip().stopTimes().getLast().stationId(),
				ride.from().stationId(),
				ride.to().stationId(),
				serviceInstant(serviceDay, ride.scheduledTrip().departureSeconds(ride.fromIndex())),
				serviceInstant(serviceDay, ride.scheduledTrip().arrivalSeconds(ride.toIndex())),
				evidence == null ? null : serviceInstant(serviceDay, ride.departureSeconds()),
				evidence == null ? null : serviceInstant(serviceDay, ride.arrivalSeconds())
			));
		}
		RideLeg lastRide = path.getLast();
		int exitDurationSeconds = journeyAccessSeconds(
			command, JourneyAccessKind.EXIT, timetable.transitionDurationSeconds(label.exitTransition()),
			timetable.transitionDistanceMeters(label.exitTransition()));
		legs.add(journeyAccessLeg(
			JourneyAccessKind.EXIT,
			lastRide.to().stationId(),
			command.destinationStationId(),
			command,
			timetable,
			label.exitTransition()
		));
		return new JourneyItinerary(
			serviceDay.date(),
			serviceInstant(serviceDay, label.startSeconds()),
			serviceInstant(serviceDay,
				lastRide.scheduledTrip().arrivalSeconds(lastRide.toIndex()) + exitDurationSeconds),
			realtimeOverlay.available() ? serviceInstant(serviceDay, label.startSeconds()) : null,
			realtimeOverlay.available()
				? serviceInstant(serviceDay, lastRide.arrivalSeconds() + exitDurationSeconds)
				: null,
			List.copyOf(legs)
		);
	}

	private static JourneyAccessProjection journeyAccessLeg(
		JourneyAccessKind kind,
		String fromStationId,
		String toStationId,
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		int transition
	) {
		return new JourneyAccessProjection(
			kind,
			fromStationId,
			toStationId,
			journeyAccessSeconds(command, kind, timetable.transitionDurationSeconds(transition),
				timetable.transitionDistanceMeters(transition)),
			timetable.transitionDistanceMeters(transition),
			timetable.transitionIncludesStairs(transition),
			timetable.transitionVerified(transition),
			timetable.transitionVerificationStatus(transition)
		);
	}

	private static Instant serviceInstant(ServiceDay serviceDay, int seconds) {
		return serviceDay.date().atStartOfDay(SERVICE_ZONE).plusSeconds(seconds).toInstant();
	}

	private static RouteSearchResult blockedAccessibilityResult(
		SearchRouteV2Command command,
		ServiceDay serviceDay,
		Label diagnostic
	) {
		List<RouteWarning> warnings = warnings(diagnostic.warningBits());
		if (warnings.isEmpty()) {
			warnings = List.of(new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE));
		}
		return new RouteSearchResult(
			"route-v2-raptor-blocked-" + serviceDay.date() + "-" + command.originStationId()
				+ "-" + command.destinationStationId(),
			command.originStationId(),
			command.originStationId(),
			command.destinationStationId(),
			command.destinationStationId(),
			command.mobilityType(),
			RouteSearchStatus.BLOCKED,
			"",
			"",
			0,
			List.of(),
			warnings,
			List.of("검증된 계단 없는 접근 경로를 확인할 수 없습니다."),
			LocalDateTime.of(serviceDay.date(), java.time.LocalTime.MIDNIGHT).plusSeconds(diagnostic.startSeconds())
		);
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
		return nextServiceTime(command, timetable, RealtimeOverlay.empty());
	}

	Optional<OffsetDateTime> nextServiceTime(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay
	) {
		ServiceDay serviceDay = serviceDay(command);
		for (int dayOffset = 0; dayOffset <= 7; dayOffset += 1) {
			LocalDate candidateServiceDate = serviceDay.date().plusDays(dayOffset);
			int startSeconds = candidateServiceDateStartSeconds(command, candidateServiceDate);
			Optional<Integer> departureSeconds = firstFeasibleDepartureSeconds(
				command,
				timetable,
				candidateServiceDate,
				startSeconds,
				dayOffset == 0 ? realtimeOverlay : RealtimeOverlay.empty()
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
		int startSeconds,
		RealtimeOverlay realtimeOverlay
	) {
		Map<String, List<BoardingStop>> boardingsByStation = timetable.activeServiceDay(serviceDate).boardingsByStation();
		Map<ReachabilityState, Boolean> reachabilityCache = new HashMap<>();
		Integer firstDepartureSeconds = null;
		int origin = timetable.stationIndex(command.originStationId());
		int accessProfileBit = profileBit(command.mobilityType(), command.constraintMode());
		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		for (BoardingStop boardingStop : boardingsByStation.getOrDefault(command.originStationId(), List.of())) {
			ScheduledTrip trip = boardingStop.trip();
			if (realtimeOverlay.cancelled(trip)) {
				continue;
			}
			int stopIndex = boardingStop.stopIndex();
			int boardingLine = timetable.lineIndex(trip.lineId(stopIndex));
			int entryTransition = origin < 0 || boardingLine < 0 ? -1 : timetable.entryTransition(
				origin, boardingLine, accessProfileBit, false, command.requiresVerifiedJourneyDistance());
			if (entryTransition < 0) {
				continue;
			}
			int entrySeconds = journeyAccessSeconds(
				command, JourneyAccessKind.ENTRY, timetable.transitionDurationSeconds(entryTransition),
				timetable.transitionDistanceMeters(entryTransition));
			int departureSeconds = realtimeOverlay.departureSeconds(trip, stopIndex);
			if (!trip.allowsPickup(stopIndex)
				|| departureSeconds < startSeconds + entrySeconds + slackSeconds) {
				continue;
			}
			if (canReachDestinationAfterBoarding(
				command,
				timetable,
				boardingsByStation,
				trip,
				stopIndex,
					0,
					accessProfileBit,
					new HashSet<>(),
					reachabilityCache,
					realtimeOverlay
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
		CompiledTimetable timetable,
		Map<String, List<BoardingStop>> boardingsByStation,
		ScheduledTrip trip,
		int boardingStopIndex,
		int transfersUsed,
		int accessProfileBit,
		Set<ReachabilityState> visiting,
		Map<ReachabilityState, Boolean> reachabilityCache,
		RealtimeOverlay realtimeOverlay
	) {
		if (realtimeOverlay.cancelled(trip)) {
			return false;
		}
		List<TransitStopTime> stopTimes = trip.stopTimes();
		for (int stopIndex = boardingStopIndex + 1; stopIndex < stopTimes.size(); stopIndex += 1) {
			TransitStopTime stopTime = stopTimes.get(stopIndex);
			if (!trip.allowsDropOff(stopIndex)) {
				continue;
			}
			if (command.destinationStationId().equals(stopTime.stationId())) {
				int destination = timetable.stationIndex(stopTime.stationId());
				int incomingLine = timetable.lineIndex(trip.lineId(stopIndex));
				if (destination >= 0 && incomingLine >= 0
					&& timetable.exitTransition(destination, incomingLine, accessProfileBit, false,
						command.requiresVerifiedJourneyDistance()) >= 0) {
					return true;
				}
			}
			if (canReachDestinationAfterAlighting(
				command,
				timetable,
				boardingsByStation,
				stopTime.stationId(),
					realtimeOverlay.arrivalSeconds(trip, stopIndex),
					timetable.lineIndex(trip.lineId(stopIndex)),
				transfersUsed,
				accessProfileBit,
				visiting,
					reachabilityCache,
					realtimeOverlay
			)) {
				return true;
			}
		}
		return false;
	}

	private boolean canReachDestinationAfterAlighting(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		Map<String, List<BoardingStop>> boardingsByStation,
		String stationId,
		int readySeconds,
		int incomingLine,
		int transfersUsed,
		int accessProfileBit,
		Set<ReachabilityState> visiting,
		Map<ReachabilityState, Boolean> reachabilityCache,
		RealtimeOverlay realtimeOverlay
	) {
		if (transfersUsed >= command.maxTransfers()) {
			return false;
		}
		ReachabilityState state = new ReachabilityState(stationId, readySeconds, incomingLine, transfersUsed);
		Boolean cached = reachabilityCache.get(state);
		if (cached != null) {
			return cached;
		}
		if (!visiting.add(state)) {
			return false;
		}
		int station = timetable.stationIndex(stationId);
		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		try {
			for (BoardingStop boardingStop : boardingsByStation.getOrDefault(stationId, List.of())) {
				ScheduledTrip trip = boardingStop.trip();
				if (realtimeOverlay.cancelled(trip)) {
					continue;
				}
				int stopIndex = boardingStop.stopIndex();
				int boardingLine = timetable.lineIndex(trip.lineId(stopIndex));
				int transferTransition = station < 0 || incomingLine < 0 || boardingLine < 0 ? -1
					: timetable.transferTransition(station, incomingLine, boardingLine, accessProfileBit, false,
						command.requiresVerifiedJourneyDistance());
				if (transferTransition < 0) {
					continue;
				}
				int transferSeconds = journeyAccessSeconds(
					command, JourneyAccessKind.TRANSFER, timetable.transitionDurationSeconds(transferTransition),
					timetable.transitionDistanceMeters(transferTransition));
				if (!trip.allowsPickup(stopIndex)
					|| realtimeOverlay.departureSeconds(trip, stopIndex)
						< readySeconds + transferSeconds + slackSeconds) {
					continue;
				}
				if (canReachDestinationAfterBoarding(
					command,
					timetable,
					boardingsByStation,
					trip,
					stopIndex,
					transfersUsed + 1,
					accessProfileBit,
					visiting,
					reachabilityCache,
					realtimeOverlay
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
		int startSeconds,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		ActiveServiceDay activeServiceDay = timetable.activeServiceDay(serviceDay.date());
		ScanWorkspace workspace = scanWorkspaces.get();
		workspace.prepare(timetable.stationCount(), timetable.lineCount(), timetable.routePatternCount());
		if (activeServiceDay.trips().isEmpty()) {
			return new ScanResult(serviceDay, List.of());
		}
		int origin = timetable.stationIndex(command.originStationId());
		int destination = timetable.stationIndex(command.destinationStationId());
		if (origin < 0 || destination < 0) {
			return new ScanResult(serviceDay, List.of());
		}
		workspace.arrivalSeconds[workspace.slot(0, origin, workspace.noIncomingLine(), 0)] = startSeconds;
		workspace.mark(origin);

		int slackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		int accessProfileBit = profileBit(command.mobilityType(), command.constraintMode());
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
					slackSeconds,
					accessProfileBit,
					command,
					ignoreAccessBlocks,
					realtimeOverlay
				);
			}
			workspace.finishRound();
		}

		List<Label> destinationLabels = limitDestinationLabels(
			destinationLabels(
				command.destinationStationId(), timetable, workspace, destination, startSeconds,
				accessProfileBit, command, ignoreAccessBlocks, realtimeOverlay),
			command);
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
		int slackSeconds,
		int accessProfileBit,
		SearchRouteV2Command command,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		workspace.expandedRoutes += 1;
		List<ScheduledTrip> trips = activeServiceDay.tripsByPattern(pattern);
		if (trips.isEmpty()) {
			return;
		}
		if (realtimeOverlay.affectsPattern(pattern)) {
			scanPatternWithRealtime(
				timetable, workspace, pattern, firstMarkedPosition, round, slackSeconds,
				accessProfileBit, command, ignoreAccessBlocks, realtimeOverlay, trips);
			return;
		}
		int[] stops = timetable.stopsByPattern(pattern);
		ScheduledTrip boardedTrip = null;
		int boardingPosition = -1;
		int boardingEarliestDepartureSeconds = UNREACHED;
		int boardingAccessTransition = -1;
		int boardingReadySlot = -1;
		byte boardingWarningBits = 0;
		for (int position = firstMarkedPosition; position < stops.length; position += 1) {
			int station = stops[position];
			int boardingLine = timetable.lineIndex(trips.getFirst().lineId(position));
			if (boardingLine < 0) {
				continue;
			}
			if (boardedTrip != null && position > boardingPosition && boardedTrip.allowsDropOff(position)) {
				workspace.relax(
					station,
					round + 1,
					boardingLine,
					boardedTrip.arrivalSeconds(position),
					boardedTrip.index(),
					boardingPosition,
					position,
					boardingAccessTransition,
					boardingReadySlot,
					boardingWarningBits
				);
			}
			ReadyBoarding ready = bestReadyBoarding(
				timetable, workspace, station, boardingLine, round, slackSeconds,
				accessProfileBit, command, ignoreAccessBlocks, UNREACHED);
			if (ready == null) {
				continue;
			}
			int earliestDepartureSeconds = ready.earliestDepartureSeconds();
			ScheduledTrip candidate = earliestBoardableTrip(
				trips,
				position,
				earliestDepartureSeconds
			);
			if (candidate != null) {
				ready = bestReadyBoarding(timetable, workspace, station, boardingLine, round, slackSeconds,
					accessProfileBit, command, ignoreAccessBlocks,
					candidate.departureSeconds(position));
					earliestDepartureSeconds = ready.earliestDepartureSeconds();
			}
			if (boardedTrip != null && boardedTrip.allowsPickup(position)
				&& boardedTrip.departureSeconds(position) >= earliestDepartureSeconds
				&& compareReadyBoardingKeys(
					earliestDepartureSeconds, ready.warningBits(), ready.readySlot(),
					boardingEarliestDepartureSeconds, boardingWarningBits, boardingReadySlot, true) < 0) {
				boardingPosition = position;
				boardingEarliestDepartureSeconds = earliestDepartureSeconds;
				boardingAccessTransition = ready.accessTransition();
				boardingReadySlot = ready.readySlot();
				boardingWarningBits = ready.warningBits();
			}
			if (candidate != null && (boardedTrip == null
				|| candidate.departureSeconds(position) < boardedTrip.departureSeconds(position)
				|| (candidate != boardedTrip
					&& candidate.departureSeconds(position) == boardedTrip.departureSeconds(position)
					&& candidate.arrivalSeconds(position) <= boardedTrip.arrivalSeconds(position)))) {
				boardedTrip = candidate;
				boardingPosition = position;
				boardingEarliestDepartureSeconds = earliestDepartureSeconds;
				boardingAccessTransition = ready.accessTransition();
				boardingReadySlot = ready.readySlot();
				boardingWarningBits = ready.warningBits();
				workspace.expandedTrips += 1;
			}
		}
	}

	private static void scanPatternWithRealtime(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int pattern,
		int firstMarkedPosition,
		int round,
		int slackSeconds,
		int accessProfileBit,
		SearchRouteV2Command command,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay,
		List<ScheduledTrip> trips
	) {
		int[] stops = timetable.stopsByPattern(pattern);
		for (ScheduledTrip trip : trips) {
			if (realtimeOverlay.cancelled(trip)) {
				continue;
			}
			int boardingPosition = -1;
			int boardingEarliestDepartureSeconds = UNREACHED;
			int boardingAccessTransition = -1;
			int boardingReadySlot = -1;
			byte boardingWarningBits = 0;
			for (int position = firstMarkedPosition; position < stops.length; position += 1) {
				int station = stops[position];
				int boardingLine = timetable.lineIndex(trip.lineId(position));
				if (boardingLine < 0) {
					continue;
				}
				if (boardingPosition >= 0 && position > boardingPosition && trip.allowsDropOff(position)) {
					workspace.relax(
						station, round + 1, boardingLine,
						realtimeOverlay.arrivalSeconds(trip, position), trip.index(),
						boardingPosition, position, boardingAccessTransition,
						boardingReadySlot, boardingWarningBits);
				}
				if (!trip.allowsPickup(position)) {
					continue;
				}
				int departureSeconds = realtimeOverlay.departureSeconds(trip, position);
				ReadyBoarding ready = bestReadyBoarding(
					timetable, workspace, station, boardingLine, round, slackSeconds,
					accessProfileBit, command, ignoreAccessBlocks, departureSeconds);
				if (ready != null && (boardingPosition < 0 || compareReadyBoardingKeys(
					ready.earliestDepartureSeconds(), ready.warningBits(), ready.readySlot(),
					boardingEarliestDepartureSeconds, boardingWarningBits, boardingReadySlot, true) < 0)) {
					boardingPosition = position;
					boardingEarliestDepartureSeconds = ready.earliestDepartureSeconds();
					boardingAccessTransition = ready.accessTransition();
					boardingReadySlot = ready.readySlot();
					boardingWarningBits = ready.warningBits();
					workspace.expandedTrips += 1;
				}
			}
		}
	}

	private static ReadyBoarding bestReadyBoarding(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int station,
		int boardingLine,
		int round,
		int slackSeconds,
		int accessProfileBit,
		SearchRouteV2Command command,
		boolean ignoreAccessBlocks,
		int boardingDeadlineSeconds
	) {
		ReadyBoarding best = null;
		int firstIncomingLine = round == 0 ? workspace.noIncomingLine() : 0;
		int lastIncomingLine = round == 0 ? firstIncomingLine + 1 : timetable.lineCount();
		for (int incomingLine = firstIncomingLine; incomingLine < lastIncomingLine; incomingLine += 1) {
			int accessTransition = round == 0
				? timetable.entryTransition(station, boardingLine, accessProfileBit, ignoreAccessBlocks,
					command.requiresVerifiedJourneyDistance())
				: timetable.transferTransition(
					station, incomingLine, boardingLine, accessProfileBit, ignoreAccessBlocks,
					command.requiresVerifiedJourneyDistance());
			if (accessTransition < 0) {
				continue;
			}
			for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
				int readySlot = workspace.slot(round, station, incomingLine, warningState);
				int readySeconds = workspace.arrivalSeconds[readySlot];
				if (readySeconds == UNREACHED) {
					continue;
				}
				JourneyAccessKind accessKind = round == 0 ? JourneyAccessKind.ENTRY : JourneyAccessKind.TRANSFER;
				int earliestDepartureSeconds = readySeconds
					+ journeyAccessSeconds(command, accessKind, timetable.transitionDurationSeconds(accessTransition),
						timetable.transitionDistanceMeters(accessTransition))
					+ slackSeconds;
				if (earliestDepartureSeconds > boardingDeadlineSeconds) {
					continue;
				}
				byte warningBits = (byte) (workspace.warningBits[readySlot]
					| timetable.transitionWarningCodes(accessTransition, accessProfileBit, ignoreAccessBlocks));
				if (best == null || compareReadyBoardingKeys(
					earliestDepartureSeconds, warningBits, readySlot,
					best.earliestDepartureSeconds(), best.warningBits(), best.readySlot(),
					boardingDeadlineSeconds != UNREACHED
				) < 0) {
					best = new ReadyBoarding(readySlot, accessTransition, earliestDepartureSeconds, warningBits);
				}
			}
		}
		return best;
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
		int startSeconds,
		int accessProfileBit,
		SearchRouteV2Command command,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		// #2534: 스캔은 경고를 Pareto 차원으로 유지하는데(ScanWorkspace.relax의 경고 부분집합 지배),
		// 추출이 환승 수마다 라벨 1개만 남기면 그 차원이 버려져 PREFER_* 프로파일에서
		// "느리지만 무단차"인 대안이 소실된다. PREFER_*에서는 경고 상태별 최선 후보를 함께 담고
		// 지배 판정은 paretoFront가 일괄 처리한다.
		// 표시 정렬은 compareLabels(시간 우선)로 그대로 두고 여기서는 보존 집합만 넓힌다.
		boolean preserveWarningAlternatives = prefersStepFree(command);
		List<Label> labels = new ArrayList<>(PARETO_LIMIT * timetable.lineCount());
		Label[] bestByWarningState = preserveWarningAlternatives
			? new Label[WARNING_STATE_COUNT]
			: NO_WARNING_ALTERNATIVES;
		for (int boardings = 1; boardings <= PARETO_LIMIT; boardings += 1) {
			Label bestForBoardings = null;
			Arrays.fill(bestByWarningState, null);
			for (int incomingLine = 0; incomingLine < timetable.lineCount(); incomingLine += 1) {
				int exitTransition = timetable.exitTransition(
					destination, incomingLine, accessProfileBit, ignoreAccessBlocks,
					command.requiresVerifiedJourneyDistance());
				if (exitTransition < 0) {
					continue;
				}
				for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
					int slot = workspace.slot(boardings, destination, incomingLine, warningState);
					if (workspace.arrivalSeconds[slot] == UNREACHED) {
						continue;
					}
					List<RideLeg> path = new ArrayList<>(boardings);
					int[] accessTransitions = new int[boardings];
					int currentSlot = slot;
					int currentBoardings = boardings;
					while (currentBoardings > 0) {
						ScheduledTrip trip = timetable.scheduledTrip(workspace.parentTrip[currentSlot]);
						int boardingPosition = workspace.parentBoardStop[currentSlot];
						int alightingPosition = workspace.parentAlightStop[currentSlot];
						path.add(new RideLeg(trip, boardingPosition, alightingPosition, realtimeOverlay));
						accessTransitions[currentBoardings - 1] = workspace.parentAccessTransition[currentSlot];
						currentSlot = workspace.parentLabelSlot[currentSlot];
						currentBoardings -= 1;
					}
					java.util.Collections.reverse(path);
					Label candidate = new Label(
						destinationStationId,
						workspace.arrivalSeconds[slot]
							+ journeyAccessSeconds(command, JourneyAccessKind.EXIT,
								timetable.transitionDurationSeconds(exitTransition),
								timetable.transitionDistanceMeters(exitTransition)),
						startSeconds,
						boardings,
						List.copyOf(path),
						accessTransitions,
						exitTransition,
						(byte) (workspace.warningBits[slot]
							| timetable.transitionWarningCodes(exitTransition, accessProfileBit, ignoreAccessBlocks))
					);
					if (bestForBoardings == null || compareDestinationLabels(candidate, bestForBoardings) < 0) {
						bestForBoardings = candidate;
					}
					if (preserveWarningAlternatives) {
						int candidateWarningState = Byte.toUnsignedInt(candidate.warningBits());
						Label incumbent = bestByWarningState[candidateWarningState];
						if (incumbent == null || compareDestinationLabels(candidate, incumbent) < 0) {
							bestByWarningState[candidateWarningState] = candidate;
						}
					}
				}
			}
			if (bestForBoardings == null) {
				continue;
			}
			// 기존 승자를 먼저 담아 동률 정렬(안정 정렬)에서의 표시 순서를 그대로 유지한다.
			labels.add(bestForBoardings);
			for (Label candidate : bestByWarningState) {
				// 버킷 안의 부분집합 지배도 paretoFront(labels, true)가 동일하게 걸러낸다.
				if (candidate != null && candidate != bestForBoardings) {
					labels.add(candidate);
				}
			}
		}
		return paretoFront(labels, preserveWarningAlternatives);
	}

	private static List<Label> paretoFront(List<Label> labels, boolean warningDimension) {
		List<Label> front = new ArrayList<>(labels.size());
		for (int index = 0; index < labels.size(); index += 1) {
			Label candidate = labels.get(index);
			boolean dominated = false;
			for (int other = 0; other < labels.size() && !dominated; other += 1) {
				dominated = other != index
					&& dominates(labels.get(other), candidate, warningDimension, other < index);
			}
			if (!dominated) {
				front.add(candidate);
			}
		}
		return List.copyOf(front);
	}

	private static boolean dominates(Label other, Label candidate, boolean warningDimension, boolean earlier) {
		if (other.boardings() > candidate.boardings() || other.timeSeconds() > candidate.timeSeconds()) {
			return false;
		}
		if (!warningDimension) {
			return true;
		}
		if ((other.warningBits() & candidate.warningBits()) != other.warningBits()) {
			return false;
		}
		// 모든 차원이 같은 쌍은 현재 생기지 않는다(버킷 안 라벨은 warningBits가 서로 다르고 버킷
		// 사이에는 boardings가 다르다). earlier는 향후 중복 라벨이 생길 때의 상호 소거 방어다.
		return other.boardings() < candidate.boardings()
			|| other.timeSeconds() < candidate.timeSeconds()
			|| other.warningBits() != candidate.warningBits()
			|| earlier;
	}

	// #2534: 후보가 상한을 넘으면 자리 하나를 선호 후보(PREFERRED_WARNING_ORDER)에 내준다.
	// 상한(candidateLimit) 자체는 그대로지만 PREFER_STEP_FREE에서는 실제 후보 수가 상한까지
	// 채워지는 빈도가 높아지므로, 후보당 하류 비용(toRouteSearchResult 재구성·실시간 재계산·
	// 직렬화)은 최악 상한배까지 늘 수 있다.
	private static List<Label> limitDestinationLabels(List<Label> labels, SearchRouteV2Command command) {
		List<Label> ordered = labels.stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.toList();
		int limit = candidateLimit(command);
		if (ordered.size() <= limit) {
			return ordered;
		}
		List<Label> limited = new ArrayList<>(ordered.subList(0, limit));
		if (!prefersStepFree(command)) {
			return List.copyOf(limited);
		}
		Label preferred = ordered.stream().min(PREFERRED_WARNING_ORDER).orElseThrow();
		if (limited.stream().anyMatch(label -> label == preferred)) {
			return List.copyOf(limited);
		}
		int victim = evictableIndex(limited);
		if (victim < 0) {
			return List.copyOf(limited);
		}
		limited.set(victim, preferred);
		limited.sort(RouteTimetableRaptorPlanner::compareLabels);
		return List.copyOf(limited);
	}

	// 축출 대상에서 두 가지를 뺀다. 인덱스 0(최속 라벨)은 표시 선두 계약이라 어떤 상한에서도
	// 지키고(상한이 1이면 후보가 없어 교체 자체가 일어나지 않는다), 환승 수가 그 안에서 유일한
	// 라벨은 해당 환승 수의 유일한 대안이라 건드리지 않는다. 남는 자리가 없으면 -1이다.
	private static int evictableIndex(List<Label> limited) {
		for (int index = limited.size() - 1; index >= 1; index -= 1) {
			int boardings = limited.get(index).boardings();
			if (limited.stream().filter(label -> label.boardings() == boardings).count() > 1) {
				return index;
			}
		}
		return -1;
	}

	private static boolean prefersStepFree(SearchRouteV2Command command) {
		return command.constraintMode() == ConstraintMode.PREFER_STEP_FREE;
	}
	private static int compareDestinationLabels(Label left, Label right) {
		RideLeg leftLast = left.path().getLast();
		RideLeg rightLast = right.path().getLast();
		return compareDestinationLabelKeys(
			left.timeSeconds(), left.warningBits(), leftLast.scheduledTrip().index(), leftLast.fromIndex(),
			right.timeSeconds(), right.warningBits(), rightLast.scheduledTrip().index(), rightLast.fromIndex()
		);
	}
	static int compareReadyBoardingKeys(
		int leftTime, byte leftWarnings, int leftSlot,
		int rightTime, byte rightWarnings, int rightSlot,
		boolean preferWarnings
	) {
		int comparison = preferWarnings
			? Integer.compare(warningCount(leftWarnings), warningCount(rightWarnings))
			: Integer.compare(leftTime, rightTime);
		if (comparison == 0) {
			comparison = preferWarnings
				? Integer.compare(leftTime, rightTime)
				: Integer.compare(warningCount(leftWarnings), warningCount(rightWarnings));
		}
		return comparison != 0 ? comparison : Integer.compare(leftSlot, rightSlot);
	}
	static int compareDestinationLabelKeys(
		int leftTime, byte leftWarnings, int leftTrip, int leftStop,
		int rightTime, byte rightWarnings, int rightTrip, int rightStop
	) {
		int comparison = compareReadyBoardingKeys(
			leftTime, leftWarnings, leftTrip,
			rightTime, rightWarnings, rightTrip, false);
		return comparison != 0 ? comparison : Integer.compare(leftStop, rightStop);
	}
	private static int warningCount(byte warningBits) {
		return Integer.bitCount(Byte.toUnsignedInt(warningBits));
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
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay
	) {
		List<RouteStep> steps = new ArrayList<>();
		int sequence = 1;
		int boardingSlackSeconds = BoardingSlackPolicy.secondsFor(command.mobilityType());
		List<RideLeg> path = label.path();
		RideLeg firstLeg = path.getFirst();
		RideLeg lastLeg = path.getLast();
		int entryTransition = label.accessTransitions()[0];
		int entryDurationSeconds = profiledWalkSeconds(
			command, timetable.transitionDurationSeconds(entryTransition),
			timetable.transitionDistanceMeters(entryTransition));
		steps.add(timetableAccessStep(
			sequence,
			"entry",
			command.originStationId(),
			firstLeg.from().stationId(),
			firstLeg.from().lineId(),
			firstLeg.lineName(),
			waitMinutesBeforeBoarding(label.startSeconds(), firstLeg.departureSeconds(), entryDurationSeconds, boardingSlackSeconds),
			entryDurationSeconds,
			serviceTime(serviceDay, label.startSeconds()),
			serviceTime(serviceDay, firstLeg.departureSeconds()),
			timetable,
			entryTransition
		));
		sequence += 1;
		for (int index = 0; index < path.size(); index += 1) {
			RideLeg leg = path.get(index);
			if (index > 0) {
				RideLeg previousLeg = path.get(index - 1);
				int transferTransition = label.accessTransitions()[index];
				int transferDurationSeconds = profiledWalkSeconds(
					command, timetable.transitionDurationSeconds(transferTransition),
					timetable.transitionDistanceMeters(transferTransition));
				steps.add(timetableAccessStep(
					sequence,
					"transfer",
					previousLeg.to().stationId(),
					leg.from().stationId(),
					leg.from().lineId(),
					leg.lineName(),
					waitMinutesBeforeBoarding(previousLeg.arrivalSeconds(), leg.departureSeconds(), transferDurationSeconds, boardingSlackSeconds),
					transferDurationSeconds,
					serviceTime(serviceDay, previousLeg.arrivalSeconds()),
					serviceTime(serviceDay, leg.departureSeconds()),
					timetable,
					transferTransition
				));
				sequence += 1;
			}
			String lineName = leg.lineName();
			RealtimeEvidence realtimeEvidence = realtimeOverlay.evidence(leg.scheduledTrip());
			boolean realtime = realtimeEvidence != null;
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
				realtime ? EtaSource.REALTIME.name() : EtaSource.PLANNED.name(),
				"TIMETABLE",
				realtime ? "실시간" : "시간표",
				realtime ? List.of("REALTIME_PRE_SCAN_OVERLAY") : List.of(),
				realtime ? realtimeEvidence.providerSnapshotId() : null,
				realtime ? formatInstant(realtimeEvidence.providerObservedAt()) : null,
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
		int exitDurationSeconds = profiledWalkSeconds(
			command, timetable.transitionDurationSeconds(label.exitTransition()),
			timetable.transitionDistanceMeters(label.exitTransition()));
		steps.add(timetableAccessStep(
			sequence,
			"exit",
			lastLeg.to().stationId(),
			command.destinationStationId(),
			lastLeg.to().lineId(),
			lastLeg.lineName(),
			(int) Math.ceil(exitDurationSeconds / 60.0),
			exitDurationSeconds,
			serviceTime(serviceDay, lastLeg.arrivalSeconds()),
			serviceTime(serviceDay, lastLeg.arrivalSeconds() + exitDurationSeconds),
			timetable,
			label.exitTransition()
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
			warnings(label.warningBits()),
			List.of(),
			LocalDateTime.of(serviceDay.date(), java.time.LocalTime.MIDNIGHT).plusSeconds(label.startSeconds()),
			List.of(),
			officialFare(timetable.source(), path)
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

	private static int profiledWalkSeconds(
		SearchRouteV2Command command,
		int baselineSeconds,
		int distanceMeters
	) {
		if (command.requiresVerifiedJourneyDistance()) {
			return ProfileWalkTimeCalculator.journeySeconds(
				distanceMeters,
				command.journeyWalkingSpeedMetersPerHour(),
				command.mobilityPreset(),
				false
			);
		}
		return ProfileWalkTimeCalculator.estimateSeconds(
			baselineSeconds,
			command.mobilityPreset(),
			WalkTimeSource.OFFICIAL_BASELINE,
			false
		).seconds();
	}

	private static int journeyAccessSeconds(
		SearchRouteV2Command command,
		JourneyAccessKind kind,
		int baselineSeconds,
		int distanceMeters
	) {
		if (kind == JourneyAccessKind.TRANSFER) {
			return profiledWalkSeconds(command, baselineSeconds, distanceMeters);
		}
		return ProfileWalkTimeCalculator.estimateSeconds(
			baselineSeconds,
			command.mobilityPreset(),
			WalkTimeSource.OFFICIAL_BASELINE,
			false
		).seconds();
	}

	static int profileBit(com.easysubway.profile.domain.MobilityType mobilityType, ConstraintMode constraintMode) {
		int mobility = switch (mobilityType) {
			case SENIOR -> 0;
			case STROLLER -> 1;
			case WHEELCHAIR -> 2;
			case PREGNANT -> 3;
			case TEMPORARY_INJURY -> 4;
			case LUGGAGE -> 5;
		};
		int constraint = switch (constraintMode) {
			case STRICT_STEP_FREE -> 0;
			case PREFER_STEP_FREE -> 1;
			case ALLOW_WITH_WARNINGS -> 2;
		};
		return 1 << (mobility * ConstraintMode.values().length + constraint);
	}
	private static int profileMask(ConstraintMode... constraintModes) {
		int mask = 0;
		for (var mobilityType : com.easysubway.profile.domain.MobilityType.values()) {
			for (ConstraintMode constraintMode : constraintModes) {
				mask |= profileBit(mobilityType, constraintMode);
			}
		}
		return mask;
	}
	private static RouteStep timetableAccessStep(
		int sequence,
		String stepType,
		String fromStationId,
		String toStationId,
		String lineId,
		String lineName,
		int estimatedMinutes,
		int walkSeconds,
		String plannedDepartureTime,
		String plannedArrivalTime,
		CompiledTimetable timetable,
		int transition
	) {
		boolean includesStairs = timetable.transitionIncludesStairs(transition);
		boolean verified = timetable.transitionVerified(transition);
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
			timetable.transitionDistanceMeters(transition),
			includesStairs,
			includesStairs ? "STAIR_ONLY" : verified ? "STEP_FREE" : "UNKNOWN",
			!verified,
			EtaSource.PLANNED.name(),
			"TIMETABLE",
			verified ? "검증됨" : "확인 필요",
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

	private static List<RouteWarning> warnings(byte warningBits) {
		List<RouteWarning> warnings = new ArrayList<>(3);
		if ((warningBits & WARNING_LOW_CONFIDENCE) != 0) {
			warnings.add(new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE));
		}
		if ((warningBits & WARNING_STAIRS) != 0) {
			warnings.add(new RouteWarning(RouteWarningCode.STAIR_ONLY_ACCESS));
		}
		if ((warningBits & WARNING_STALE) != 0) {
			warnings.add(new RouteWarning(RouteWarningCode.STALE_ACCESSIBILITY_DATA));
		}
		return List.copyOf(warnings);
	}
	private static String serviceTime(ServiceDay serviceDay, int seconds) {
		return serviceDay.date().atStartOfDay(SERVICE_ZONE)
			.plusSeconds(seconds)
			.toOffsetDateTime()
			.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}
	private static String formatInstant(Instant instant) {
		return instant == null ? null : instant.toString();
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

	List<TimetableRealtimeQuery> realtimeQueries(
		SearchRouteV2Command command,
		CompiledTimetable timetable
	) {
		ServiceDay serviceDay = serviceDay(command);
		Map<String, List<TimetableTripDeparture>> departuresByLine = new LinkedHashMap<>();
		for (ScheduledTrip trip : timetable.activeServiceDay(serviceDay.date()).trips()) {
			if (trip.trip().trainNo() == null) {
				continue;
			}
			for (int stopIndex = 0; stopIndex < trip.stopTimes().size(); stopIndex += 1) {
				TransitStopTime stop = trip.stopTimes().get(stopIndex);
				if (!command.originStationId().equals(stop.stationId()) || !trip.allowsPickup(stopIndex)) {
					continue;
				}
				departuresByLine.computeIfAbsent(stop.lineId(), ignored -> new ArrayList<>())
					.add(new TimetableTripDeparture(
						trip.trip().id(),
						trip.trip().trainNo(),
						trip.trip().servicePattern(),
						serviceDay.date().atStartOfDay(SERVICE_ZONE)
							.plusSeconds(trip.arrivalSeconds(stopIndex)).toInstant(),
						serviceDay.date().atStartOfDay(SERVICE_ZONE)
							.plusSeconds(trip.departureSeconds(stopIndex)).toInstant()
					));
				break;
			}
		}
		return departuresByLine.entrySet().stream()
			.map(entry -> new TimetableRealtimeQuery(
				command.originStationId(), entry.getKey(), command.departureTime().toInstant(), entry.getValue()))
			.toList();
	}

	List<TimetableRealtimeQuery> realtimeQueries(
		SearchRouteV2Command command,
		CompiledTimetable timetable,
		List<RouteSearchResult> itineraries,
		List<TimetableRealtimeQuery> queried
	) {
		Set<BoardingPoint> queriedPoints = new HashSet<>();
		for (TimetableRealtimeQuery query : queried) {
			queriedPoints.add(new BoardingPoint(query.stationId(), query.lineId()));
		}
		Map<BoardingPoint, Instant> readyAtByPoint = new LinkedHashMap<>();
		for (RouteSearchResult itinerary : itineraries) {
			for (int stepIndex = 0; stepIndex < itinerary.steps().size(); stepIndex += 1) {
				RouteStep step = itinerary.steps().get(stepIndex);
				if (!"ride".equals(step.stepType()) || step.fromStationId() == null || step.lineId() == null) {
					continue;
				}
				BoardingPoint point = new BoardingPoint(step.fromStationId(), step.lineId());
				if (queriedPoints.contains(point)) {
					continue;
				}
				Instant readyAt = realtimeReadyAt(command, itinerary.steps(), stepIndex);
				readyAtByPoint.merge(point, readyAt, (left, right) -> left.isBefore(right) ? left : right);
			}
		}
		if (readyAtByPoint.isEmpty()) {
			return List.of();
		}

		ServiceDay serviceDay = serviceDay(command);
		Map<BoardingPoint, List<TimetableTripDeparture>> departuresByPoint = new LinkedHashMap<>();
		readyAtByPoint.keySet().forEach(point -> departuresByPoint.put(point, new ArrayList<>()));
		for (ScheduledTrip trip : timetable.activeServiceDay(serviceDay.date()).trips()) {
			if (trip.trip().trainNo() == null) {
				continue;
			}
			for (int stopIndex = 0; stopIndex < trip.stopTimes().size(); stopIndex += 1) {
				TransitStopTime stop = trip.stopTimes().get(stopIndex);
				BoardingPoint point = new BoardingPoint(stop.stationId(), stop.lineId());
				List<TimetableTripDeparture> departures = departuresByPoint.get(point);
				if (departures == null || !trip.allowsPickup(stopIndex)) {
					continue;
				}
				departures.add(new TimetableTripDeparture(
					trip.trip().id(),
					trip.trip().trainNo(),
					trip.trip().servicePattern(),
					serviceDay.date().atStartOfDay(SERVICE_ZONE)
						.plusSeconds(trip.arrivalSeconds(stopIndex)).toInstant(),
					serviceDay.date().atStartOfDay(SERVICE_ZONE)
						.plusSeconds(trip.departureSeconds(stopIndex)).toInstant()
				));
			}
		}
		return departuresByPoint.entrySet().stream()
			.filter(entry -> !entry.getValue().isEmpty())
			.map(entry -> new TimetableRealtimeQuery(
				entry.getKey().stationId(),
				entry.getKey().lineId(),
				readyAtByPoint.get(entry.getKey()),
				entry.getValue()
			))
			.toList();
	}

	private static Instant realtimeReadyAt(
		SearchRouteV2Command command,
		List<RouteStep> steps,
		int rideStepIndex
	) {
		if (rideStepIndex > 0) {
			RouteStep access = steps.get(rideStepIndex - 1);
			if (("entry".equals(access.stepType()) || "transfer".equals(access.stepType()))
				&& access.plannedDepartureTime() != null && access.walkSeconds() != null) {
				return OffsetDateTime.parse(access.plannedDepartureTime())
					.plusSeconds(access.walkSeconds() + BoardingSlackPolicy.secondsFor(command.mobilityType()))
					.toInstant();
			}
		}
		RouteStep ride = steps.get(rideStepIndex);
		return ride.plannedDepartureTime() == null
			? command.departureTime().toInstant()
			: OffsetDateTime.parse(ride.plannedDepartureTime()).toInstant();
	}

	RealtimeOverlay compileRealtimeOverlay(
		CompiledTimetable timetable,
		TimetableRealtimeUpdates realtimeUpdates
	) {
		if (realtimeUpdates == null || !realtimeUpdates.available()) {
			return RealtimeOverlay.empty();
		}
		List<IndexedRealtimeUpdate> indexed = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		Set<Integer> affectedPatterns = new HashSet<>();
		for (TimetableRealtimeUpdate update : realtimeUpdates.updates()) {
			int scheduledTripIndex = timetable.uniqueScheduledTripIndex(update.tripId());
			if (scheduledTripIndex < 0 || !seen.add(scheduledTripIndex)
				|| !validRealtimeUpdate(timetable.scheduledTrip(scheduledTripIndex), update)) {
				return RealtimeOverlay.empty();
			}
			indexed.add(new IndexedRealtimeUpdate(scheduledTripIndex, update));
			affectedPatterns.add(timetable.patternOfScheduledTrip(scheduledTripIndex));
		}
		indexed.sort(Comparator.comparingInt(IndexedRealtimeUpdate::scheduledTripIndex));
		int[] tripIndexes = new int[indexed.size()];
		int[] arrivalDeltas = new int[indexed.size()];
		int[] departureDeltas = new int[indexed.size()];
		boolean[] cancelled = new boolean[indexed.size()];
		RealtimeEvidence[] evidence = new RealtimeEvidence[indexed.size()];
		for (int index = 0; index < indexed.size(); index += 1) {
			IndexedRealtimeUpdate value = indexed.get(index);
			TimetableRealtimeUpdate update = value.update();
			tripIndexes[index] = value.scheduledTripIndex();
			arrivalDeltas[index] = update.arrivalDeltaSeconds();
			departureDeltas[index] = update.departureDeltaSeconds();
			cancelled[index] = update.cancelled();
			evidence[index] = new RealtimeEvidence(
				update.providerSnapshotId(), update.providerObservedAt());
		}
		return new RealtimeOverlay(
			realtimeUpdates.version(), true, tripIndexes, arrivalDeltas, departureDeltas, cancelled, evidence,
			affectedPatterns.stream().mapToInt(Integer::intValue).sorted().toArray());
	}

	private static boolean validRealtimeUpdate(ScheduledTrip trip, TimetableRealtimeUpdate update) {
		if (update.cancelled()) {
			return true;
		}
		int previousDeparture = -1;
		try {
			for (int stopIndex = 0; stopIndex < trip.stopTimes().size(); stopIndex += 1) {
				int arrival = Math.addExact(trip.arrivalSeconds(stopIndex), update.arrivalDeltaSeconds());
				int departure = Math.addExact(trip.departureSeconds(stopIndex), update.departureDeltaSeconds());
				if (arrival < 0 || departure < arrival
					|| departure >= LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE
					|| previousDeparture > arrival) {
					return false;
				}
				previousDeparture = departure;
			}
			return true;
		} catch (ArithmeticException exception) {
			return false;
		}
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
		private final Map<String, Integer> lineIndex;
		private final Map<Integer, int[]> stopsByPattern;
		private final Map<Integer, int[]> patternsByStop;
		private final Map<Integer, List<ScheduledTrip>> tripsByPattern;
		private final int[] patternByScheduledTrip;
		private final Map<DayOfWeek, List<ServiceCalendar>> calendarsByDay;
		private final Map<LocalDate, List<ServiceCalendarDate>> exceptionsByDate;
		private final List<ScheduledTrip> scheduledTrips;
		private final AccessTransitions accessTransitions;
		private final LinkedHashMap<LocalDate, ActiveServiceDay> activeServiceDays = new LinkedHashMap<>(16, 0.75f, true);

		private CompiledTimetable(RouteTimetable source) {
			this.source = Objects.requireNonNull(source, "timetable must not be null");
			stationIndex = denseIndex(source.transitStopTimes().stream().map(TransitStopTime::stationId).toList());
			routeIndex = denseIndex(source.transitRoutes().stream().map(TransitRoute::id).toList());
			tripIndex = denseIndex(source.transitTrips().stream().map(TransitTrip::id).toList());
			lineIndex = denseIndex(source.transitStopTimes().stream().map(TransitStopTime::lineId).toList());

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
			patternByScheduledTrip = new int[scheduledTrips.size()];
			Arrays.fill(patternByScheduledTrip, -1);
			for (Map.Entry<Integer, List<ScheduledTrip>> entry : tripsByPattern.entrySet()) {
				for (ScheduledTrip trip : entry.getValue()) {
					patternByScheduledTrip[trip.index()] = entry.getKey();
				}
			}
			calendarsByDay = compileCalendarsByDay(source.serviceCalendars());
			exceptionsByDate = Map.copyOf(source.serviceCalendarDates().stream().collect(
				java.util.stream.Collectors.groupingBy(
					ServiceCalendarDate::date,
					java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), List::copyOf)
				)
			));
			accessTransitions = AccessTransitions.compile(source, stationIndex, lineIndex);
		}

		RouteTimetable source() {
			return source;
		}

		int stationCount() {
			return stationIndex.size();
		}

		int lineCount() {
			return lineIndex.size();
		}
		int stationIndex(String stationId) {
			return stationIndex.getOrDefault(stationId, -1);
		}

		int lineIndex(String lineId) {
			return lineIndex.getOrDefault(lineId, -1);
		}
		int entryTransition(
			int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance
		) {
			return accessTransitions.entry(station, line, profileBit, ignoreBlocked, requireVerifiedDistance);
		}
		int entryTransition(int station, int line, int profileBit, boolean ignoreBlocked) {
			return entryTransition(station, line, profileBit, ignoreBlocked, false);
		}
		int exitTransition(
			int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance
		) {
			return accessTransitions.exit(station, line, profileBit, ignoreBlocked, requireVerifiedDistance);
		}
		int exitTransition(int station, int line, int profileBit, boolean ignoreBlocked) {
			return exitTransition(station, line, profileBit, ignoreBlocked, false);
		}
		int transferTransition(
			int station, int fromLine, int toLine, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance
		) {
			return accessTransitions.transfer(
				station, fromLine, toLine, profileBit, ignoreBlocked, requireVerifiedDistance);
		}
		int transferTransition(int station, int fromLine, int toLine, int profileBit, boolean ignoreBlocked) {
			return transferTransition(station, fromLine, toLine, profileBit, ignoreBlocked, false);
		}
		int transitionDurationSeconds(int transition) {
			return accessTransitions.durationSeconds(transition);
		}
		int transitionDistanceMeters(int transition) {
			return accessTransitions.distanceMeters(transition);
		}
		String transitionVerificationStatus(int transition) {
			return accessTransitions.verificationStatus(transition);
		}
		byte transitionWarningCodes(int transition, int profileBit, boolean ignoreBlocked) {
			return accessTransitions.warningCodes(transition, profileBit, ignoreBlocked);
		}
		boolean transitionIncludesStairs(int transition) {
			return accessTransitions.includesStairs(transition);
		}
		boolean transitionVerified(int transition) {
			return accessTransitions.verified(transition);
		}
		int unsupportedTransferCount() {
			return accessTransitions.unsupportedTransferCount();
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

		int uniqueScheduledTripIndex(String tripId) {
			int selected = -1;
			for (ScheduledTrip trip : scheduledTrips) {
				if (!trip.trip().id().equals(tripId)) {
					continue;
				}
				if (selected >= 0) {
					return -1;
				}
				selected = trip.index();
			}
			return selected;
		}

		int patternOfScheduledTrip(int scheduledTripIndex) {
			return patternByScheduledTrip[scheduledTripIndex];
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
				List<String> lineSequence = trip.stopTimes().stream().map(TransitStopTime::lineId).toList();
				RoutePatternKey key = new RoutePatternKey(
					trip.trip().routeId(), stationSequence, lineSequence, accessSignature);
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

	private static final class AccessTransitions {
		private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
			.comparingInt(Candidate::durationSeconds)
			.thenComparingInt(candidate -> Integer.bitCount(candidate.warningCodes()))
			.thenComparingInt(Candidate::distanceMeters)
			.thenComparing(candidate -> candidate.edgeId() == null ? "" : candidate.edgeId());
		private final int lineCount;
		private final int[][] entryTransitions;
		private final int[][] exitTransitions;
		private final int[][] transferTransitions;
		private final int[] durationSeconds;
		private final int[] distanceMeters;
		private final int[] blockedProfiles;
		private final int[] warningProfiles;
		private final byte[] warningCodes;
		private final boolean[] includesStairs;
		private final String[] edgeIds;
		private final String[] verificationStatuses;
		private final int unsupportedTransferCount;
		private AccessTransitions(
			int lineCount,
			int[][] entryTransitions,
			int[][] exitTransitions,
			int[][] transferTransitions,
			List<Candidate> candidates,
			int unsupportedTransferCount
		) {
			this.lineCount = lineCount;
			this.entryTransitions = entryTransitions;
			this.exitTransitions = exitTransitions;
			this.transferTransitions = transferTransitions;
			this.unsupportedTransferCount = unsupportedTransferCount;
			durationSeconds = new int[candidates.size()];
			distanceMeters = new int[candidates.size()];
			blockedProfiles = new int[candidates.size()];
			warningProfiles = new int[candidates.size()];
			warningCodes = new byte[candidates.size()];
			includesStairs = new boolean[candidates.size()];
			edgeIds = new String[candidates.size()];
			verificationStatuses = new String[candidates.size()];
			for (int index = 0; index < candidates.size(); index += 1) {
				Candidate candidate = candidates.get(index);
				durationSeconds[index] = candidate.durationSeconds();
				distanceMeters[index] = candidate.distanceMeters();
				blockedProfiles[index] = candidate.blockedProfiles();
				warningProfiles[index] = candidate.warningProfiles();
				warningCodes[index] = candidate.warningCodes();
				includesStairs[index] = candidate.includesStairs();
				edgeIds[index] = candidate.edgeId();
				verificationStatuses[index] = candidate.verificationStatus();
			}
		}

		private static AccessTransitions compile(
			RouteTimetable timetable,
			Map<String, Integer> stationIndex,
			Map<String, Integer> lineIndex
		) {
			int stationCount = stationIndex.size();
			int lineCount = lineIndex.size();
			List<List<Candidate>> entries = candidateLists(stationCount * lineCount);
			List<List<Candidate>> exits = candidateLists(stationCount * lineCount);
			List<List<Candidate>> transfers = candidateLists(stationCount * lineCount * lineCount);
			Map<String, PathwayEdge> edges = new HashMap<>();
			Set<String> ambiguousEdgeIds = new HashSet<>();
			for (PathwayEdge edge : timetable.routeAccessData().pathwayEdges()) {
				indexEdge(edges, ambiguousEdgeIds, edge.id(), edge);
				indexEdge(edges, ambiguousEdgeIds, edge.legacyInternalRouteEdgeId(), edge);
			}
			Map<String, PathwayNode> nodes = new HashMap<>();
			for (PathwayNode node : timetable.routeAccessData().pathwayNodes()) {
				nodes.put(node.id(), node);
			}
			Map<EvidenceKey, List<RouteEdgeEvidence>> evidenceByIdentity = new HashMap<>();
			for (RouteEdgeEvidence evidence : timetable.routeAccessData().routeEdgeEvidence()) {
				PathwayEdge edge = edges.get(evidence.edgeId());
				evidenceByIdentity.computeIfAbsent(EvidenceKey.from(evidence, edge == null ? evidence.edgeId() : edge.id()),
					ignored -> new ArrayList<>())
					.add(evidence);
			}
			for (RouteEdgeEvidence evidence : timetable.routeAccessData().routeEdgeEvidence()) {
				Integer station = stationIndex.get(evidence.stationId());
				Integer line = evidence.lineId() == null ? null : lineIndex.get(evidence.lineId());
				PathwayEdge edge = edges.get(evidence.edgeId());
				if (station == null || line == null || edge == null
					|| !ownedByEvidence(edge, evidence, nodes)
					|| evidenceByIdentity.get(EvidenceKey.from(evidence, edge.id())).size() != 1) {
					continue;
				}
				Candidate candidate = candidate(edge, evidence, null, edge.durationSeconds(), true);
				if ("ENTRY".equals(evidence.edgeType())) {
					entries.get(stationLineKey(station, line, lineCount)).add(candidate);
				} else if ("EXIT".equals(evidence.edgeType())) {
					exits.get(stationLineKey(station, line, lineCount)).add(candidate);
				}
			}
			int unsupported = 0;
			for (TransferRule rule : timetable.routeAccessData().transferRules()) {
				if (!rule.fromStationId().equals(rule.toStationId()) || !"IN_STATION".equals(rule.transferType())) {
					unsupported += 1;
					continue;
				}
				Integer station = stationIndex.get(rule.fromStationId());
				Integer fromLine = lineIndex.get(rule.fromLineId());
				Integer toLine = lineIndex.get(rule.toLineId());
				if (station == null || fromLine == null || toLine == null) {
					continue;
				}
				List<Candidate> candidates = transfers.get(transferKey(station, fromLine, toLine, lineCount));
				PathwayEdge normalEdge = ownedByRule(edges.get(rule.pathwayEdgeId()), rule, nodes);
				PathwayEdge strictEdge = ownedByRule(edges.get(rule.strictStepFreePathwayEdgeId()), rule, nodes);
				if (normalEdge == null && strictEdge == null && rule.minTransferSeconds() > 0) {
					candidates.add(new Candidate(rule.minTransferSeconds(), TRANSFER_DISTANCE_METERS,
						STRICT_PROFILE_MASK, NON_STRICT_PROFILE_MASK, WARNING_LOW_CONFIDENCE,
						false, null, "MISSING"));
				}
				if (normalEdge != null) {
					boolean strictCandidate = normalEdge.id().equals(rule.strictStepFreePathwayEdgeId());
					candidates.add(candidate(
						normalEdge,
						uniqueTransferEvidence(evidenceByIdentity, rule, normalEdge.id()),
						rule,
						Math.max(rule.minTransferSeconds(), normalEdge.durationSeconds()),
						strictCandidate
					));
				}
				if (strictEdge != null && (normalEdge == null || !strictEdge.id().equals(normalEdge.id()))) {
					candidates.add(candidate(
						strictEdge,
						uniqueTransferEvidence(evidenceByIdentity, rule, strictEdge.id()),
						rule,
						Math.max(rule.minTransferSeconds(), strictEdge.durationSeconds()),
						true
					));
				}
			}
			boolean[][] served = new boolean[stationCount][lineCount];
			for (TransitStopTime stopTime : timetable.transitStopTimes()) {
				Integer station = stationIndex.get(stopTime.stationId());
				Integer line = lineIndex.get(stopTime.lineId());
				if (station != null && line != null) {
					served[station][line] = true;
				}
			}
			for (int station = 0; station < stationCount; station += 1) {
				for (int line = 0; line < lineCount; line += 1) {
					if (!served[station][line]) {
						continue;
					}
					addDefaultIfEmpty(entries.get(stationLineKey(station, line, lineCount)), ENTRY_DURATION_SECONDS, ENTRY_DISTANCE_METERS);
					addDefaultIfEmpty(exits.get(stationLineKey(station, line, lineCount)), EXIT_DURATION_SECONDS, EXIT_DISTANCE_METERS);
					for (int toLine = 0; toLine < lineCount; toLine += 1) {
						if (served[station][toLine]) {
							addDefaultIfEmpty(
								transfers.get(transferKey(station, line, toLine, lineCount)),
								TRANSFER_DURATION_SECONDS,
								TRANSFER_DISTANCE_METERS
							);
						}
					}
				}
			}
			List<Candidate> flattened = new ArrayList<>();
			int[][] entryIds = flatten(entries, flattened);
			int[][] exitIds = flatten(exits, flattened);
			int[][] transferIds = flatten(transfers, flattened);
			return new AccessTransitions(lineCount, entryIds, exitIds, transferIds, flattened, unsupported);
		}
		private static void indexEdge(Map<String, PathwayEdge> edges, Set<String> ambiguous, String id, PathwayEdge edge) {
			if (id == null || id.isBlank() || ambiguous.contains(id)) {
				return;
			}
			PathwayEdge existing = edges.putIfAbsent(id, edge);
			if (existing != null && existing != edge) {
				edges.remove(id);
				ambiguous.add(id);
			}
		}
		private static boolean ownedByEvidence(
			PathwayEdge edge, RouteEdgeEvidence evidence, Map<String, PathwayNode> nodes
		) {
			PathwayNode from = nodes.get(edge.fromNodeId()), to = nodes.get(edge.toNodeId());
			if (from == null || to == null || !evidence.stationId().equals(from.stationId())
				|| !evidence.stationId().equals(to.stationId())) {
				return false;
			}
			boolean forward = "ENTRY".equals(evidence.edgeType())
				? lineCompatible(from, evidence.lineId()) && evidence.lineId().equals(to.lineId())
				: "EXIT".equals(evidence.edgeType())
					&& evidence.lineId().equals(from.lineId()) && lineCompatible(to, evidence.lineId());
			boolean reverse = "ENTRY".equals(evidence.edgeType())
				? lineCompatible(to, evidence.lineId()) && evidence.lineId().equals(from.lineId())
				: "EXIT".equals(evidence.edgeType())
					&& evidence.lineId().equals(to.lineId()) && lineCompatible(from, evidence.lineId());
			return forward || edge.bidirectional() && reverse;
		}
		private static boolean lineCompatible(PathwayNode node, String lineId) {
			return node.lineId() == null || lineId.equals(node.lineId());
		}
		private static PathwayEdge ownedByRule(PathwayEdge edge, TransferRule rule, Map<String, PathwayNode> nodes) {
			if (edge == null) {
				return null;
			}
			PathwayNode from = nodes.get(edge.fromNodeId()), to = nodes.get(edge.toNodeId());
			boolean forward = from != null && to != null && rule.fromStationId().equals(from.stationId())
				&& rule.toStationId().equals(to.stationId()) && rule.fromLineId().equals(from.lineId())
				&& rule.toLineId().equals(to.lineId());
			boolean reverse = edge.bidirectional() && from != null && to != null
				&& rule.fromStationId().equals(to.stationId()) && rule.toStationId().equals(from.stationId())
				&& rule.fromLineId().equals(to.lineId()) && rule.toLineId().equals(from.lineId());
			return forward || reverse ? edge : null;
		}
		private static Candidate candidate(
			PathwayEdge edge,
			RouteEdgeEvidence evidence,
			TransferRule rule,
			int durationSeconds,
			boolean strictCandidate
		) {
			boolean verified = evidence != null
				&& "VERIFIED".equals(evidence.verificationStatus())
				&& "VERIFIED".equals(edge.verificationStatus())
				&& (rule == null || "VERIFIED".equals(rule.verificationStatus()));
			boolean trusted = evidence != null
				&& trustedProvenance(evidence.provenanceKind())
				&& trustedProvenance(edge.provenanceKind());
			boolean available = "AVAILABLE".equals(edge.accessibilityStatus());
			boolean unavailable = "UNAVAILABLE".equals(edge.accessibilityStatus())
				|| "UNDER_MAINTENANCE".equals(edge.accessibilityStatus());
			boolean strictAllowed = strictCandidate
				&& verified
				&& trusted
				&& available
				&& edge.reliabilityScore() >= 80
				&& evidence.strictRouteEligible()
				&& !edge.includesStairs();
			byte warnings = 0;
			if (!verified || !trusted || !available || edge.reliabilityScore() < 80
				|| evidence != null && !evidence.strictRouteEligible()) {
				warnings |= WARNING_LOW_CONFIDENCE;
			}
			if (edge.includesStairs()) {
				warnings |= WARNING_STAIRS;
			}
			if ("STALE".equals(edge.verificationStatus())
				|| evidence != null && "STALE".equals(evidence.verificationStatus())
				|| rule != null && "STALE".equals(rule.verificationStatus())) {
				warnings |= WARNING_STALE;
			}
			String verificationStatus = combinedVerificationStatus(edge, evidence, rule);
			return new Candidate(
				durationSeconds,
				edge.distanceMeters(),
				unavailable ? STRICT_PROFILE_MASK | NON_STRICT_PROFILE_MASK
					: (strictAllowed ? 0 : STRICT_PROFILE_MASK),
				warnings == 0 ? 0 : NON_STRICT_PROFILE_MASK,
				warnings,
				edge.includesStairs(),
				edge.id(),
				verificationStatus
			);
		}
		private static boolean trustedProvenance(String provenance) {
			return "OFFICIAL_SOURCE".equals(provenance)
				|| "OPERATOR_CONFIRMED".equals(provenance)
				|| "FIELD_VERIFIED".equals(provenance);
		}
		private static String combinedVerificationStatus(
			PathwayEdge edge,
			RouteEdgeEvidence evidence,
			TransferRule rule
		) {
			if (evidence == null) {
				return "MISSING";
			}
			List<String> statuses = rule == null
				? List.of(edge.verificationStatus(), evidence.verificationStatus())
				: List.of(edge.verificationStatus(), evidence.verificationStatus(), rule.verificationStatus());
			if (statuses.contains("STALE")) {
				return "STALE";
			}
			if (statuses.contains("GENERATED")) {
				return "GENERATED";
			}
			if (statuses.contains("MISSING")) {
				return "MISSING";
			}
			return statuses.stream().allMatch("VERIFIED"::equals) ? "VERIFIED" : "UNKNOWN";
		}

		private static RouteEdgeEvidence uniqueTransferEvidence(
			Map<EvidenceKey, List<RouteEdgeEvidence>> evidenceByIdentity,
			TransferRule rule,
			String edgeId
		) {
			List<RouteEdgeEvidence> evidence = evidenceByIdentity.getOrDefault(
				new EvidenceKey(rule.toStationId(), rule.toLineId(), edgeId, "TRANSFER"),
				List.of()
			);
			return evidence.size() == 1 ? evidence.getFirst() : null;
		}

		private static void addDefaultIfEmpty(List<Candidate> candidates, int durationSeconds, int distanceMeters) {
			if (candidates.isEmpty()) {
				candidates.add(new Candidate(
					durationSeconds,
					distanceMeters,
					STRICT_PROFILE_MASK,
					NON_STRICT_PROFILE_MASK,
					WARNING_LOW_CONFIDENCE,
					false,
					null,
					"MISSING"
				));
			}
		}
		private static List<List<Candidate>> candidateLists(int size) {
			List<List<Candidate>> candidates = new ArrayList<>(size);
			for (int index = 0; index < size; index += 1) {
				candidates.add(new ArrayList<>());
			}
			return candidates;
		}
		private static int[][] flatten(List<List<Candidate>> source, List<Candidate> flattened) {
			int[][] indexes = new int[source.size()][];
			for (int key = 0; key < source.size(); key += 1) {
				List<Candidate> candidates = source.get(key);
				if (candidates.isEmpty()) {
					indexes[key] = NO_TRANSITIONS;
					continue;
				}
				candidates.sort(CANDIDATE_ORDER);
				int[] ids = new int[candidates.size()];
				for (int index = 0; index < candidates.size(); index += 1) {
					ids[index] = flattened.size();
					flattened.add(candidates.get(index));
				}
				indexes[key] = ids;
			}
			return indexes;
		}
		private static int stationLineKey(int station, int line, int lineCount) {
			return station * lineCount + line;
		}
		private static int transferKey(int station, int fromLine, int toLine, int lineCount) {
			return (station * lineCount + fromLine) * lineCount + toLine;
		}
		private int entry(int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance) {
			return select(entryTransitions[stationLineKey(station, line, lineCount)], profileBit, ignoreBlocked,
				requireVerifiedDistance, false);
		}
		private int exit(int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance) {
			return select(exitTransitions[stationLineKey(station, line, lineCount)], profileBit, ignoreBlocked,
				requireVerifiedDistance, false);
		}
		private int transfer(
			int station, int fromLine, int toLine, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance
		) {
			return select(transferTransitions[transferKey(station, fromLine, toLine, lineCount)], profileBit,
				ignoreBlocked, requireVerifiedDistance, requireVerifiedDistance);
		}
		private int select(
			int[] candidates,
			int profileBit,
			boolean ignoreBlocked,
			boolean requireVerified,
			boolean requirePositiveDistance
		) {
			if (requireVerified) {
				int selected = -1;
				for (int transition : candidates) {
					if ((ignoreBlocked || (blockedProfiles[transition] & profileBit) == 0)
						&& (!requirePositiveDistance || distanceMeters[transition] > 0)
						&& "VERIFIED".equals(verificationStatuses[transition])
						&& (warningCodes[transition] & (WARNING_LOW_CONFIDENCE | WARNING_STALE)) == 0
						&& (selected < 0 || isPreferredVerifiedTransition(transition, selected, profileBit))) {
						selected = transition;
					}
				}
				return selected;
			}
			for (int transition : candidates) {
				if (ignoreBlocked || (blockedProfiles[transition] & profileBit) == 0) {
					return transition;
				}
			}
			return -1;
		}
		private boolean isPreferredVerifiedTransition(int candidate, int selected, int profileBit) {
			boolean preferStepFree = (profileBit & PREFER_STEP_FREE_PROFILE_MASK) != 0;
			boolean candidateHasStairs = (warningCodes[candidate] & WARNING_STAIRS) != 0;
			boolean selectedHasStairs = (warningCodes[selected] & WARNING_STAIRS) != 0;
			if (preferStepFree && candidateHasStairs != selectedHasStairs) {
				return !candidateHasStairs;
			}
			return distanceMeters[candidate] < distanceMeters[selected];
		}
		private int durationSeconds(int transition) {
			return durationSeconds[transition];
		}
		private int distanceMeters(int transition) {
			return distanceMeters[transition];
		}
		private String verificationStatus(int transition) {
			return verificationStatuses[transition];
		}
		private byte warningCodes(int transition, int profileBit, boolean ignoreBlocked) {
			return ignoreBlocked || (warningProfiles[transition] & profileBit) != 0 ? warningCodes[transition] : 0;
		}
		private boolean includesStairs(int transition) {
			return includesStairs[transition];
		}
		private boolean verified(int transition) {
			return "VERIFIED".equals(verificationStatuses[transition])
				&& (warningCodes[transition] & (WARNING_LOW_CONFIDENCE | WARNING_STALE)) == 0;
		}
		private int unsupportedTransferCount() {
			return unsupportedTransferCount;
		}
		private record Candidate(
			int durationSeconds,
			int distanceMeters,
			int blockedProfiles,
			int warningProfiles,
			byte warningCodes,
			boolean includesStairs,
			String edgeId,
			String verificationStatus
		) {
		}
		private record EvidenceKey(String stationId, String lineId, String edgeId, String edgeType) {
			private static EvidenceKey from(RouteEdgeEvidence evidence, String edgeId) {
				return new EvidenceKey(
					evidence.stationId(), evidence.lineId(), edgeId, evidence.edgeType());
			}
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
		private int lineStateCount;
		private int[] arrivalSeconds = new int[0];
		private int[] parentTrip = new int[0];
		private int[] parentBoardStop = new int[0];
		private int[] parentAlightStop = new int[0];
		private int[] parentAccessTransition = new int[0];
		private int[] parentLabelSlot = new int[0];
		private byte[] warningBits = new byte[0];
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

		private void prepare(int requiredStationCount, int lineCount, int patternCount) {
			stationCount = requiredStationCount;
			lineStateCount = Math.addExact(lineCount, 1);
			int labelSlots = Math.multiplyExact(Math.multiplyExact(requiredStationCount, LABEL_SLOT_COUNT),
				Math.multiplyExact(lineStateCount, WARNING_STATE_COUNT));
			if (arrivalSeconds.length < labelSlots) {
				arrivalSeconds = new int[labelSlots];
				parentTrip = new int[labelSlots];
				parentBoardStop = new int[labelSlots];
				parentAlightStop = new int[labelSlots];
				parentAccessTransition = new int[labelSlots];
				parentLabelSlot = new int[labelSlots];
				warningBits = new byte[labelSlots];
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
			Arrays.fill(parentAccessTransition, 0, labelSlots, -1);
			Arrays.fill(parentLabelSlot, 0, labelSlots, -1);
			Arrays.fill(warningBits, 0, labelSlots, (byte) 0);
			Arrays.fill(marked, 0, requiredStationCount, false);
			Arrays.fill(nextMarked, 0, requiredStationCount, false);
			Arrays.fill(firstMarkedPosition, 0, patternCount, -1);
			markedStopCount = 0;
			nextMarkedStopCount = 0;
			markedPatternCount = 0;
			expandedRoutes = 0;
			expandedTrips = 0;
		}

		private int slot(int boardings, int station, int incomingLine, int warningState) {
			return ((boardings * stationCount + station) * lineStateCount + incomingLine) * WARNING_STATE_COUNT + warningState;
		}
		private int noIncomingLine() {
			return lineStateCount - 1;
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
			int incomingLine,
			int candidateArrivalSeconds,
			int trip,
			int boardStop,
			int alightStop,
			int accessTransition,
			int previousLabelSlot,
			byte accumulatedWarnings
		) {
			int candidateWarningState = Byte.toUnsignedInt(accumulatedWarnings);
			int candidateSlot = slot(boardings, station, incomingLine, candidateWarningState);
			int existingArrivalSeconds = arrivalSeconds[candidateSlot];
			if (existingArrivalSeconds < candidateArrivalSeconds) {
				return;
			}
			if (existingArrivalSeconds == candidateArrivalSeconds && (parentTrip[candidateSlot] < trip
				|| parentTrip[candidateSlot] == trip && parentBoardStop[candidateSlot] <= boardStop)) {
				return;
			}
			for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
				if (warningState != candidateWarningState
					&& (warningState & candidateWarningState) == warningState
					&& arrivalSeconds[slot(boardings, station, incomingLine, warningState)] <= candidateArrivalSeconds) {
					return;
				}
				for (int fewerBoardings = 0; fewerBoardings < boardings; fewerBoardings += 1) {
					if ((warningState & candidateWarningState) == warningState
						&& arrivalSeconds[slot(fewerBoardings, station, incomingLine, warningState)]
							<= candidateArrivalSeconds) {
						return;
					}
				}
			}
			arrivalSeconds[candidateSlot] = candidateArrivalSeconds;
			parentTrip[candidateSlot] = trip;
			parentBoardStop[candidateSlot] = boardStop;
			parentAlightStop[candidateSlot] = alightStop;
			parentAccessTransition[candidateSlot] = accessTransition;
			parentLabelSlot[candidateSlot] = previousLabelSlot;
			warningBits[candidateSlot] = accumulatedWarnings;
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

	private record ReadyBoarding(
		int readySlot,
		int accessTransition,
		int earliestDepartureSeconds,
		byte warningBits
	) {
	}
	record SearchOutcome(List<RouteSearchResult> itineraries, RouteSearchResult blockedAccessibility) {
		SearchOutcome {
			itineraries = List.copyOf(itineraries);
		}
	}
	private record BoardingPoint(String stationId, String lineId) {
	}
	record ScanMetrics(int expandedRoutes, int expandedTrips, int workspaceIdentity) {
	}

	record JourneyItinerary(
		LocalDate serviceDate,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime,
		List<JourneyLegProjection> legs
	) {
		JourneyItinerary {
			legs = List.copyOf(legs);
		}
	}

	sealed interface JourneyLegProjection permits JourneyAccessProjection, JourneyRideProjection {
	}

	enum JourneyAccessKind {
		ENTRY,
		TRANSFER,
		EXIT
	}

	record JourneyAccessProjection(
		JourneyAccessKind kind,
		String fromStationId,
		String toStationId,
		int durationSeconds,
		int distanceMeters,
		boolean includesStairs,
		boolean verified,
		String verificationStatus
	) implements JourneyLegProjection {
	}

	record JourneyRideProjection(
		String lineId,
		String tripId,
		String directionStationId,
		String fromStationId,
		String toStationId,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime
	) implements JourneyLegProjection {
	}

	static final class RealtimeOverlay {

		private static final RealtimeOverlay EMPTY = new RealtimeOverlay(
			null, false, new int[0], new int[0], new int[0], new boolean[0], new RealtimeEvidence[0], new int[0]);
		private final String version;
		private final boolean available;
		private final int[] tripIndexes;
		private final int[] arrivalDeltas;
		private final int[] departureDeltas;
		private final boolean[] cancelled;
		private final RealtimeEvidence[] evidence;
		private final int[] affectedPatterns;

		private RealtimeOverlay(
			String version,
			boolean available,
			int[] tripIndexes,
			int[] arrivalDeltas,
			int[] departureDeltas,
			boolean[] cancelled,
			RealtimeEvidence[] evidence,
			int[] affectedPatterns
		) {
			this.version = version;
			this.available = available;
			this.tripIndexes = tripIndexes;
			this.arrivalDeltas = arrivalDeltas;
			this.departureDeltas = departureDeltas;
			this.cancelled = cancelled;
			this.evidence = evidence;
			this.affectedPatterns = affectedPatterns;
		}

		static RealtimeOverlay empty() {
			return EMPTY;
		}

		String version() {
			return version;
		}

		boolean available() {
			return available;
		}

		boolean isEmpty() {
			return tripIndexes.length == 0;
		}

		boolean affectsPattern(int pattern) {
			return Arrays.binarySearch(affectedPatterns, pattern) >= 0;
		}

		int arrivalSeconds(ScheduledTrip trip, int stopIndex) {
			int entry = entry(trip.index());
			return entry < 0 ? trip.arrivalSeconds(stopIndex)
				: Math.addExact(trip.arrivalSeconds(stopIndex), arrivalDeltas[entry]);
		}

		int departureSeconds(ScheduledTrip trip, int stopIndex) {
			int entry = entry(trip.index());
			return entry < 0 ? trip.departureSeconds(stopIndex)
				: Math.addExact(trip.departureSeconds(stopIndex), departureDeltas[entry]);
		}

		boolean cancelled(ScheduledTrip trip) {
			int entry = entry(trip.index());
			return entry >= 0 && cancelled[entry];
		}

		RealtimeEvidence evidence(ScheduledTrip trip) {
			int entry = entry(trip.index());
			return entry < 0 || cancelled[entry] ? null : evidence[entry];
		}

		private int entry(int tripIndex) {
			return Arrays.binarySearch(tripIndexes, tripIndex);
		}
	}

	private record RoutePatternKey(
		String routeId,
		List<Integer> stationSequence,
		List<String> lineSequence,
		List<Integer> accessSignature
	) {
	}

	private record CompiledRoutePatterns(
		Map<Integer, int[]> stopsByPattern,
		Map<Integer, List<ScheduledTrip>> tripsByPattern
	) {
	}

	private record Label(
		String stationId,
		int timeSeconds,
		int startSeconds,
		int boardings,
		List<RideLeg> path,
		int[] accessTransitions,
		int exitTransition,
		byte warningBits
	) {
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
		private String lineId(int stopIndex) {
			return stopTimes.get(stopIndex).lineId();
		}
	}

	private record BoardingStop(ScheduledTrip trip, int stopIndex, TransitStopTime stopTime) {
	}

	private record ReachabilityState(String stationId, int readySeconds, int incomingLine, int transfersUsed) {
	}

	private record RideLeg(
		ScheduledTrip scheduledTrip,
		int fromIndex,
		int toIndex,
		RealtimeOverlay realtimeOverlay
	) {
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
			return realtimeOverlay.departureSeconds(scheduledTrip, fromIndex);
		}

		int arrivalSeconds() {
			return realtimeOverlay.arrivalSeconds(scheduledTrip, toIndex);
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

	private record IndexedRealtimeUpdate(int scheduledTripIndex, TimetableRealtimeUpdate update) {
	}

	private record RealtimeEvidence(String providerSnapshotId, Instant providerObservedAt) {
	}
}
