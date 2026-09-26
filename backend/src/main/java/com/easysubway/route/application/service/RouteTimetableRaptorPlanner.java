package com.easysubway.route.application.service;

import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdate;
import com.easysubway.route.application.port.in.RouteSearchUseCase.TimetableRealtimeUpdates;
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
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorPort.ScanMetrics;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

class RouteTimetableRaptorPlanner {

	private static final ZoneId SERVICE_ZONE = ServiceDayResolver.ZONE;
	private static final int PARETO_LIMIT = 4;
	private static final int ENTRY_DURATION_SECONDS = 240;
	private static final int ENTRY_DISTANCE_METERS = 180;
	private static final int TRANSFER_DURATION_SECONDS = 360;
	private static final int TRANSFER_DISTANCE_METERS = 260;
	private static final int EXIT_DURATION_SECONDS = 180;
	private static final int EXIT_DISTANCE_METERS = 120;
	private static final int ACTIVE_SERVICE_DAY_CACHE_SIZE = 8;
	private static final int LABEL_SLOT_COUNT = PARETO_LIMIT + 1;
	static final int UNREACHED = Integer.MAX_VALUE;
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
		.thenComparingInt(Label::virtualCostSeconds)
		.thenComparingInt(Label::boardings);
	private static final Label[] NO_WARNING_ALTERNATIVES = new Label[0];
	private static final int STRICT_PROFILE_MASK = profileMask(ConstraintMode.STRICT_STEP_FREE);
	private static final int PREFER_STEP_FREE_PROFILE_MASK = profileMask(ConstraintMode.PREFER_STEP_FREE);
	private static final int NON_STRICT_PROFILE_MASK = profileMask(
		ConstraintMode.PREFER_STEP_FREE, ConstraintMode.ALLOW_WITH_WARNINGS);
	private final ThreadLocal<ScanWorkspace> scanWorkspaces = ThreadLocal.withInitial(ScanWorkspace::new);

	JourneyPlan journeyItineraries(
		JourneyRaptorQuery query,
		RouteTimetable timetable
	) {
		return journeyItineraries(query, compile(timetable));
	}

	JourneyPlan journeyItineraries(
		JourneyRaptorQuery query,
		CompiledTimetable timetable
	) {
		return journeyItineraries(query, timetable, RealtimeOverlay.empty());
	}

	JourneyPlan journeyItineraries(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay
	) {
		return journeyItineraries(query, timetable, realtimeOverlay,
			new JourneyRequestMeasurement(query.requestId()), query.requestId(), "test-bundle", 1L);
	}

	JourneyPlan journeyItineraries(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay,
		JourneyRequestMeasurement requestMeasurement,
		String requestId,
		String routeBundleSha256,
		long generation
	) {
		return journeyItineraries(scanInput(query), timetable, realtimeOverlay,
			requestMeasurement, requestId, routeBundleSha256, generation);
	}

	private JourneyPlan journeyItineraries(
		ScanInput input,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay,
		JourneyRequestMeasurement requestMeasurement,
		String requestId,
		String routeBundleSha256,
		long generation
	) {
		ScanResult scanResult = scanDestinationLabels(input, timetable, false, realtimeOverlay);
		List<JourneyItinerary> itineraries = journeyItineraries(
			input, timetable, realtimeOverlay, scanResult);
		var measurementObservation = requestMeasurement.observeDirectRaptor(
			requestId, routeBundleSha256, generation);
		return new JourneyPlan(itineraries, scanResult.scanMetrics(), measurementObservation);
	}

	private static List<JourneyItinerary> journeyItineraries(
		ScanInput input,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay,
		ScanResult scanResult
	) {
		return scanResult.labels().stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.limit(input.candidateLimit())
			.map(label -> toJourneyItinerary(input, timetable, label))
			.toList();
	}


	private static JourneyItinerary toJourneyItinerary(
		ScanInput input,
		CompiledTimetable timetable,
		Label label
	) {
		List<JourneyLegProjection> legs = new ArrayList<>();
		List<RideLeg> path = label.path();
		RideLeg firstRide = path.getFirst();
		int entryTransition = label.accessTransitions()[0];
		legs.add(journeyAccessLeg(
			JourneyAccessKind.ENTRY,
			input.originStationId(),
			firstRide.from().stationId(),
			input,
			timetable,
			entryTransition
		));
		for (int index = 0; index < path.size(); index += 1) {
			RideLeg ride = path.get(index);
			if (index > 0) {
				RideLeg previous = path.get(index - 1);
				int transferTransition = label.accessTransitions()[index];
				boolean isOutOfStation = timetable.isOutOfStationTransition(transferTransition);
				String transferType = isOutOfStation ? "OUT_OF_STATION" : null;
				Boolean farePenaltyApplies = null;
				Integer additionalFareWon = null;
				Integer transferLimitMinutes = null;
				if (isOutOfStation) {
					int elapsed = ride.departureSeconds() - previous.arrivalSeconds();
					int limit = getTransferLimitSeconds(previous.arrivalSeconds(), ride.departureSeconds());
					boolean timeout = elapsed > limit;
					farePenaltyApplies = timeout;
					additionalFareWon = timeout ? 1400 : 0;
					transferLimitMinutes = limit / 60;
				}
				legs.add(new JourneyAccessProjection(
					JourneyAccessKind.TRANSFER,
					previous.to().stationId(),
					ride.from().stationId(),
					journeyAccessSeconds(input, JourneyAccessKind.TRANSFER,
						timetable.transitionDurationSeconds(transferTransition),
						timetable.transitionDistanceMeters(transferTransition)),
					timetable.transitionDistanceMeters(transferTransition),
					timetable.transitionIncludesStairs(transferTransition),
					timetable.transitionVerified(transferTransition),
					timetable.transitionVerificationStatus(transferTransition),
					transferType,
					farePenaltyApplies,
					additionalFareWon,
					transferLimitMinutes
				));
			}
			RealtimeEvidence evidence = ride.realtimeOverlay().evidence(ride.scheduledTrip());
			legs.add(new JourneyRideProjection(
				ride.lineId(),
				ride.tripId(),
				ride.scheduledTrip().stopTimes().getLast().stationId(),
				ride.from().stationId(),
				ride.to().stationId(),
				ride.plannedDepartureTime(input.serviceDay()),
				ride.plannedArrivalTime(input.serviceDay()),
				evidence == null ? null : ride.realtimeDepartureTime(input.serviceDay()),
				evidence == null ? null : ride.realtimeArrivalTime(input.serviceDay())
			));
		}
		RideLeg lastRide = path.getLast();
		int exitDurationSeconds = journeyAccessSeconds(
			input, JourneyAccessKind.EXIT, timetable.transitionDurationSeconds(label.exitTransition()),
			timetable.transitionDistanceMeters(label.exitTransition()));
		legs.add(journeyAccessLeg(
			JourneyAccessKind.EXIT,
			lastRide.to().stationId(),
			input.destinationStationId(),
			input,
			timetable,
			label.exitTransition()
		));
		return new JourneyItinerary(
			input.serviceDay().date(),
			serviceInstant(input.serviceDay(), label.startSeconds()),
			lastRide.plannedArrivalTime(input.serviceDay()).plusSeconds(exitDurationSeconds),
			firstRide.realtimeOverlay().available()
				? serviceInstant(input.serviceDay(), label.startSeconds()) : null,
			lastRide.realtimeOverlay().available()
				? lastRide.realtimeArrivalTime(input.serviceDay()).plusSeconds(exitDurationSeconds)
				: null,
			itineraryMetrics(legs, input.boardingSlackSeconds()),
			List.copyOf(legs)
		);
	}

	static JourneyProfileRaptorPort.ItineraryMetrics itineraryMetrics(
		List<JourneyLegProjection> legs,
		int boardingSlackSeconds
	) {
		Objects.requireNonNull(legs, "legs");
		if (boardingSlackSeconds < 0) throw new IllegalArgumentException("boardingSlackSeconds must not be negative");
		long accessMovementSeconds = 0;
		long accessDistanceMeters = 0;
		long accessibilityBurden = 0;
		int rideCount = 0;
		JourneyRideProjection previousRide = null;
		JourneyAccessProjection pendingTransfer = null;
		long minimumTransferSlack = Long.MAX_VALUE;
		for (JourneyLegProjection leg : legs) {
			if (leg instanceof JourneyAccessProjection access) {
				if (access.verified()) {
					accessMovementSeconds = Math.addExact(accessMovementSeconds, access.durationSeconds());
					accessDistanceMeters = Math.addExact(accessDistanceMeters, access.distanceMeters());
					if (access.includesStairs()) accessibilityBurden = Math.addExact(accessibilityBurden, 1);
				}
				if (access.kind() == JourneyAccessKind.TRANSFER) pendingTransfer = access;
				continue;
			}
			JourneyRideProjection ride = (JourneyRideProjection) leg;
			if (previousRide != null) {
				if (pendingTransfer == null) {
					throw new IllegalArgumentException("consecutive rides require a verified transfer");
				}
				Instant previousArrival = effectiveArrival(previousRide);
				Instant nextDeparture = effectiveDeparture(ride);
				long slack = Duration.between(previousArrival, nextDeparture).getSeconds()
					- pendingTransfer.durationSeconds() - boardingSlackSeconds;
				if (slack < 0) throw new IllegalArgumentException("selected transfer must remain feasible");
				minimumTransferSlack = Math.min(minimumTransferSlack, slack);
				pendingTransfer = null;
			}
			previousRide = ride;
			rideCount += 1;
		}
		if (rideCount == 0) throw new IllegalArgumentException("profile metrics require at least one ride");
		int transfersUsed = rideCount - 1;
		JourneyProfileRaptorPort.ConnectionSlack connectionSlack = transfersUsed == 0
			? new JourneyProfileRaptorPort.NoTransfer()
			: new JourneyProfileRaptorPort.MinimumTransferSeconds(minimumTransferSlack);
		return new JourneyProfileRaptorPort.ItineraryMetrics(
			transfersUsed, accessMovementSeconds, accessDistanceMeters, accessibilityBurden, connectionSlack);
	}

	private static Instant effectiveDeparture(JourneyRideProjection ride) {
		return ride.realtimeDepartureTime() == null ? ride.plannedDepartureTime() : ride.realtimeDepartureTime();
	}

	private static Instant effectiveArrival(JourneyRideProjection ride) {
		return ride.realtimeArrivalTime() == null ? ride.plannedArrivalTime() : ride.realtimeArrivalTime();
	}

	private static JourneyAccessProjection journeyAccessLeg(
		JourneyAccessKind kind,
		String fromStationId,
		String toStationId,
		ScanInput input,
		CompiledTimetable timetable,
		int transition
	) {
		return new JourneyAccessProjection(
			kind,
			fromStationId,
			toStationId,
			journeyAccessSeconds(input, kind, timetable.transitionDurationSeconds(transition),
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


	private ScanResult scanDestinationLabels(
		ScanInput input,
		CompiledTimetable timetable,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		ActiveServiceDay activeServiceDay = timetable.activeServiceDay(input.serviceDay().date());
		ScanWorkspace workspace = scanWorkspaces.get();
		workspace.prepare(timetable);
		if (activeServiceDay.trips().isEmpty()) {
			return new ScanResult(input.serviceDay(), List.of(), scanMetrics(workspace));
		}
		int origin = timetable.stationIndex(input.originStationId());
		int destination = timetable.stationIndex(input.destinationStationId());
		if (origin < 0 || destination < 0) {
			return new ScanResult(input.serviceDay(), List.of(), scanMetrics(workspace));
		}
		workspace.setTargetStation(destination);
		workspace.improveOrigin(origin, input.readyAtSeconds());

		int slackSeconds = input.boardingSlackSeconds();
		int accessProfileBit = input.accessProfileBit();
		scanMarkedRounds(
			input, timetable, activeServiceDay, workspace, slackSeconds, accessProfileBit, ignoreAccessBlocks, realtimeOverlay);
		return destinationScanResult(
			input, timetable, workspace, destination, accessProfileBit, ignoreAccessBlocks, realtimeOverlay);
	}

	private static void scanMarkedRounds(
		ScanInput input,
		CompiledTimetable timetable,
		ActiveServiceDay activeServiceDay,
		ScanWorkspace workspace,
		int slackSeconds,
		int accessProfileBit,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		for (int round = 0; round <= input.maxTransfers() && workspace.markedStopCount > 0; round += 1) {
			throwIfCancelled(input);
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
					input,
					ignoreAccessBlocks,
					realtimeOverlay
				);
			}
			relaxFootpaths(timetable, workspace, round + 1);
			workspace.finishRound();
		}
	}

	static void relaxFootpaths(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int round
	) {
		int count = workspace.nextMarkedStopCount;
		for (int i = 0; i < count; i += 1) {
			int fromStation = workspace.nextMarkedStops[i];
			OutOfStationFootpath[] footpaths = timetable.footpathsFromStation(fromStation);
			if (footpaths == null) {
				continue;
			}
			for (OutOfStationFootpath footpath : footpaths) {
				boolean reached = false;
				for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
					if (workspace.arrivalSeconds[workspace.slot(round, fromStation, footpath.fromLine(), w)] != UNREACHED) {
						reached = true;
						break;
					}
				}
				if (reached) {
					workspace.markNext(footpath.toStation());
				}
			}
		}
	}

	private static ScanResult destinationScanResult(
		ScanInput input,
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int destination,
		int accessProfileBit,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		List<Label> destinationLabels = limitDestinationLabels(
			destinationLabels(
				input.destinationStationId(), timetable, workspace, destination, input.readyAtSeconds(),
				accessProfileBit, input, ignoreAccessBlocks, realtimeOverlay),
			input);
		return new ScanResult(input.serviceDay(), destinationLabels, scanMetrics(workspace));
	}

	private static ScanMetrics scanMetrics(ScanWorkspace workspace) {
		return new ScanMetrics(workspace.expandedRoutes, workspace.expandedTrips, workspace.expandedTransfers);
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

	@SuppressWarnings({"java:S107", "java:S3776"})
	private static void scanPattern(
		CompiledTimetable timetable,
		ActiveServiceDay activeServiceDay,
		ScanWorkspace workspace,
		int pattern,
		int firstMarkedPosition,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
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
				accessProfileBit, input, ignoreAccessBlocks, realtimeOverlay, trips);
			return;
		}
		int[] stops = timetable.stopsByPattern(pattern);
		workspace.clearBag();
		for (int position = firstMarkedPosition; position < stops.length; position += 1) {
			throwIfCancelled(input);
			int station = stops[position];
			int boardingLine = timetable.lineIndex(trips.getFirst().lineId(position));
			if (boardingLine < 0) {
				continue;
			}
			for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
				ScheduledTrip boardedTrip = workspace.bagTrips[w];
				if (boardedTrip != null && position > workspace.bagBoardPositions[w] && boardedTrip.allowsDropOff(position)) {
					workspace.relax(
						station,
						round + 1,
						boardingLine,
						boardedTrip.arrivalSeconds(position),
						boardedTrip.index(),
						workspace.bagBoardPositions[w],
						position,
						workspace.bagAccessTransitions[w],
						workspace.bagReadySlots[w],
						workspace.bagWarningBits[w]
					);
				}
			}
			collectReadyBoardings(
				timetable, workspace, station, boardingLine, round, slackSeconds,
				accessProfileBit, input, ignoreAccessBlocks, UNREACHED, realtimeOverlay);

			for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
				if (!workspace.readyActive[w]) {
					continue;
				}
				int earliestDepartureSeconds = workspace.readyEarliestDepartureSeconds[w];
				ScheduledTrip candidate = earliestBoardableTrip(trips, position, earliestDepartureSeconds);
				if (candidate == null) {
					continue;
				}
				int readySlot = workspace.readySlots[w];
				int accessTransition = workspace.readyAccessTransitions[w];
				byte warningBits = workspace.readyWarningBits[w];

				ScheduledTrip incumbent = workspace.bagTrips[w];
				if (incumbent != null && incumbent.allowsPickup(position)
					&& incumbent.departureSeconds(position) >= earliestDepartureSeconds
					&& compareReadyBoardingKeys(
						earliestDepartureSeconds, warningBits, readySlot,
						workspace.bagEarliestDepartureSeconds[w],
						workspace.bagWarningBits[w],
						workspace.bagReadySlots[w], true) < 0) {
					workspace.bagBoardPositions[w] = position;
					workspace.bagEarliestDepartureSeconds[w] = earliestDepartureSeconds;
					workspace.bagAccessTransitions[w] = accessTransition;
					workspace.bagReadySlots[w] = readySlot;
					workspace.bagWarningBits[w] = warningBits;
				}

				updateBagWithCandidate(workspace, w, candidate, position, earliestDepartureSeconds,
					accessTransition, readySlot, warningBits);
			}
			enforceBagCapacity(workspace, position, PARETO_LIMIT);
		}
	}

	@SuppressWarnings({"java:S107", "java:S3776"})
	private static void scanPatternWithRealtime(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int pattern,
		int firstMarkedPosition,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay,
		List<ScheduledTrip> trips
	) {
		int[] stops = timetable.stopsByPattern(pattern);
		for (ScheduledTrip trip : trips) {
			throwIfCancelled(input);
			if (realtimeOverlay.cancelled(trip)) {
				continue;
			}
			workspace.clearRealtimeBag();
			for (int position = firstMarkedPosition; position < stops.length; position += 1) {
				throwIfCancelled(input);
				int station = stops[position];
				int boardingLine = timetable.lineIndex(trip.lineId(position));
				if (boardingLine < 0) {
					continue;
				}
				if (trip.allowsDropOff(position)) {
					for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
						if (workspace.rtActive[w] && position > workspace.rtBoardingPositions[w]) {
							workspace.relax(
								station, round + 1, boardingLine,
								realtimeOverlay.arrivalSeconds(trip, position), trip.index(),
								workspace.rtBoardingPositions[w], position, workspace.rtAccessTransitions[w],
								workspace.rtReadySlots[w], workspace.rtWarningBits[w]);
						}
					}
				}
				if (!trip.allowsPickup(position)) {
					continue;
				}
				int departureSeconds = realtimeOverlay.departureSeconds(trip, position);
				collectReadyBoardings(
					timetable, workspace, station, boardingLine, round, slackSeconds,
					accessProfileBit, input, ignoreAccessBlocks, departureSeconds, realtimeOverlay);

				for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
					if (!workspace.readyActive[w]) {
						continue;
					}
					int earliestDepartureSeconds = workspace.readyEarliestDepartureSeconds[w];
					if (departureSeconds < earliestDepartureSeconds) {
						continue;
					}
					updateRealtimeBagWithCandidate(
						workspace,
						w,
						position,
						earliestDepartureSeconds,
						workspace.readyAccessTransitions[w],
						workspace.readySlots[w],
						workspace.readyWarningBits[w]
					);
				}
				enforceRealtimeBagCapacity(workspace, PARETO_LIMIT);
			}
		}
	}

	static void updateRealtimeBagWithCandidate(
		ScanWorkspace workspace,
		int warningState,
		int position,
		int earliestDepartureSeconds,
		int accessTransition,
		int readySlot,
		byte warningBits
	) {
		for (int sub = 0; sub < WARNING_STATE_COUNT; sub += 1) {
			if (workspace.rtActive[sub] && sub != warningState && (sub & warningState) == sub) {
				return;
			}
		}
		for (int sup = 0; sup < WARNING_STATE_COUNT; sup += 1) {
			if (workspace.rtActive[sup] && sup != warningState && (warningState & sup) == warningState) {
				workspace.rtActive[sup] = false;
			}
		}
		if (!workspace.rtActive[warningState] || compareReadyBoardingKeys(
			earliestDepartureSeconds, warningBits, readySlot,
			workspace.rtEarliestDepartureSeconds[warningState],
			workspace.rtWarningBits[warningState],
			workspace.rtReadySlots[warningState], true) < 0) {
			workspace.rtActive[warningState] = true;
			workspace.rtBoardingPositions[warningState] = position;
			workspace.rtEarliestDepartureSeconds[warningState] = earliestDepartureSeconds;
			workspace.rtAccessTransitions[warningState] = accessTransition;
			workspace.rtReadySlots[warningState] = readySlot;
			workspace.rtWarningBits[warningState] = warningBits;
			workspace.expandedTrips += 1;
		}
	}

	@SuppressWarnings("java:S107")
	static void updateBagWithCandidate(
		ScanWorkspace workspace,
		int warningState,
		ScheduledTrip candidate,
		int position,
		int earliestDepartureSeconds,
		int accessTransition,
		int readySlot,
		byte warningBits
	) {
		int candidateDep = candidate.departureSeconds(position);
		int candidateArr = candidate.arrivalSeconds(position);

		for (int sub = 0; sub < WARNING_STATE_COUNT; sub += 1) {
			ScheduledTrip subTrip = workspace.bagTrips[sub];
			if (subTrip != null && sub != warningState && (sub & warningState) == sub) {
				int subDep = subTrip.departureSeconds(position);
				int subArr = subTrip.arrivalSeconds(position);
				if (subArr < candidateArr || (subArr == candidateArr && subDep <= candidateDep)) {
					return;
				}
			}
		}

		for (int sup = 0; sup < WARNING_STATE_COUNT; sup += 1) {
			ScheduledTrip supTrip = workspace.bagTrips[sup];
			if (supTrip != null && sup != warningState && (warningState & sup) == warningState) {
				int supDep = supTrip.departureSeconds(position);
				int supArr = supTrip.arrivalSeconds(position);
				if (candidateArr < supArr || (candidateArr == supArr && candidateDep <= supDep)) {
					workspace.bagTrips[sup] = null;
				}
			}
		}

		ScheduledTrip incumbent = workspace.bagTrips[warningState];
		if (incumbent == null
			|| candidateDep < incumbent.departureSeconds(position)
			|| (candidate != incumbent
				&& candidateDep == incumbent.departureSeconds(position)
				&& candidateArr <= incumbent.arrivalSeconds(position))) {
			workspace.bagTrips[warningState] = candidate;
			workspace.bagBoardPositions[warningState] = position;
			workspace.bagEarliestDepartureSeconds[warningState] = earliestDepartureSeconds;
			workspace.bagAccessTransitions[warningState] = accessTransition;
			workspace.bagReadySlots[warningState] = readySlot;
			workspace.bagWarningBits[warningState] = warningBits;
			workspace.expandedTrips += 1;
		}
	}

	static void enforceBagCapacity(ScanWorkspace workspace, int position, int capacity) {
		int count = 0;
		for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
			if (workspace.bagTrips[w] != null) {
				count += 1;
			}
		}
		while (count > capacity) {
			int worstW = -1;
			int worstDep = -1;
			int worstWarningCount = -1;
			for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
				ScheduledTrip trip = workspace.bagTrips[w];
				if (trip == null) {
					continue;
				}
				int dep = trip.departureSeconds(position);
				int wc = warningCount(workspace.bagWarningBits[w]);
				if (worstW < 0
					|| dep > worstDep
					|| (dep == worstDep && (wc > worstWarningCount || (wc == worstWarningCount && w > worstW)))) {
					worstW = w;
					worstDep = dep;
					worstWarningCount = wc;
				}
			}
			workspace.bagTrips[worstW] = null;
			count -= 1;
		}
	}

	static void enforceRealtimeBagCapacity(ScanWorkspace workspace, int capacity) {
		int count = 0;
		for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
			if (workspace.rtActive[w]) {
				count += 1;
			}
		}
		while (count > capacity) {
			int worstW = -1;
			int worstDep = -1;
			int worstWarningCount = -1;
			for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
				if (!workspace.rtActive[w]) {
					continue;
				}
				int dep = workspace.rtEarliestDepartureSeconds[w];
				int wc = warningCount(workspace.rtWarningBits[w]);
				if (worstW < 0
					|| dep > worstDep
					|| (dep == worstDep && (wc > worstWarningCount || (wc == worstWarningCount && w > worstW)))) {
					worstW = w;
					worstDep = dep;
					worstWarningCount = wc;
				}
			}
			workspace.rtActive[worstW] = false;
			count -= 1;
		}
	}

	static void enforceReadyCapacity(ScanWorkspace workspace, int capacity) {
		int count = 0;
		for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
			if (workspace.readyActive[w]) {
				count += 1;
			}
		}
		while (count > capacity) {
			int worstW = -1;
			int worstDep = -1;
			int worstWarningCount = -1;
			for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
				if (!workspace.readyActive[w]) {
					continue;
				}
				int dep = workspace.readyEarliestDepartureSeconds[w];
				int wc = warningCount(workspace.readyWarningBits[w]);
				if (worstW < 0
					|| dep > worstDep
					|| (dep == worstDep && (wc > worstWarningCount || (wc == worstWarningCount && w > worstW)))) {
					worstW = w;
					worstDep = dep;
					worstWarningCount = wc;
				}
			}
			workspace.readyActive[worstW] = false;
			count -= 1;
		}
	}

	static void collectReadyBoardings(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int station,
		int boardingLine,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		int boardingDeadlineSeconds
	) {
		collectReadyBoardings(
			timetable, workspace, station, boardingLine, round, slackSeconds,
			accessProfileBit, input, ignoreAccessBlocks, boardingDeadlineSeconds, null);
	}

	@SuppressWarnings({"java:S107", "java:S3776"})
	static void collectReadyBoardings(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int station,
		int boardingLine,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		int boardingDeadlineSeconds,
		RealtimeOverlay realtimeOverlay
	) {
		workspace.clearReady();
		int lineCount = round == 0 ? 1 : timetable.stationLineCount(station);
		int lineOffset = round == 0 ? 0 : timetable.stationLineOffset(station);
		for (int i = 0; i < lineCount; i += 1) {
			int incomingLine = round == 0 ? workspace.noIncomingLine() : timetable.stationLine(lineOffset + i);
			int canonicalTransition = round == 0
				? timetable.entryTransition(station, boardingLine, accessProfileBit, ignoreAccessBlocks,
					input.requiresVerifiedJourneyDistance(), realtimeOverlay)
				: timetable.transferTransition(station, incomingLine, boardingLine, accessProfileBit, ignoreAccessBlocks,
					input.requiresVerifiedJourneyDistance(), realtimeOverlay);
			if (canonicalTransition < 0) {
				continue;
			}
			if (round > 0) {
				workspace.expandedTransfers += 1;
			}
			evaluateTransitionIntoReady(
				timetable, workspace, station, incomingLine, canonicalTransition, round, slackSeconds,
				accessProfileBit, input, ignoreAccessBlocks, boardingDeadlineSeconds);

			if (input.prefersStepFree()) {
				int[] candidates = round == 0
					? timetable.entryTransitions(station, boardingLine)
					: timetable.transferTransitions(station, incomingLine, boardingLine);
				byte canonicalWarnings = timetable.transitionWarningCodes(
					canonicalTransition, accessProfileBit, ignoreAccessBlocks);
				int bestAlternative = -1;
				for (int alt : candidates) {
					if (alt == canonicalTransition
						|| (realtimeOverlay != null && realtimeOverlay.isTransitionBlocked(alt))
						|| !timetable.isTransitionEligible(alt, accessProfileBit, ignoreAccessBlocks,
							input.requiresVerifiedJourneyDistance(), round > 0)) {
						continue;
					}
					byte altWarnings = timetable.transitionWarningCodes(alt, accessProfileBit, ignoreAccessBlocks);
					if (altWarnings != canonicalWarnings
						&& (bestAlternative < 0
							|| timetable.transitionDurationSeconds(alt) < timetable.transitionDurationSeconds(bestAlternative))) {
						bestAlternative = alt;
					}
				}
				if (bestAlternative >= 0) {
					evaluateTransitionIntoReady(
						timetable, workspace, station, incomingLine, bestAlternative, round, slackSeconds,
						accessProfileBit, input, ignoreAccessBlocks, boardingDeadlineSeconds);
				}
			}
		}
		if (round > 0) {
			evaluateFootpathsIntoReady(
				timetable, workspace, station, boardingLine, round, slackSeconds,
				accessProfileBit, input, ignoreAccessBlocks, boardingDeadlineSeconds, realtimeOverlay);
		}
		enforceReadyCapacity(workspace, PARETO_LIMIT);
	}

	@SuppressWarnings("java:S107")
	private static void evaluateTransitionIntoReady(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int station,
		int incomingLine,
		int accessTransition,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		int boardingDeadlineSeconds
	) {
		for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
			int readySlot = workspace.slot(round, station, incomingLine, warningState);
			int readySeconds = workspace.arrivalSeconds[readySlot];
			if (readySeconds == UNREACHED) {
				continue;
			}
			JourneyAccessKind accessKind = round == 0 ? JourneyAccessKind.ENTRY : JourneyAccessKind.TRANSFER;
			int earliestDepartureSeconds = readySeconds
				+ journeyAccessSeconds(input, accessKind, timetable.transitionDurationSeconds(accessTransition),
					timetable.transitionDistanceMeters(accessTransition))
				+ slackSeconds;
			if (earliestDepartureSeconds > boardingDeadlineSeconds) {
				continue;
			}
			byte warningBits = (byte) (workspace.warningBits[readySlot]
				| timetable.transitionWarningCodes(accessTransition, accessProfileBit, ignoreAccessBlocks));
			int candidateWarningState = Byte.toUnsignedInt(warningBits);
			if (workspace.isDominatedByTarget(station, earliestDepartureSeconds, candidateWarningState)) {
				continue;
			}
			updateReadyBoarding(
				workspace, candidateWarningState, readySlot, accessTransition,
				earliestDepartureSeconds, warningBits, boardingDeadlineSeconds != UNREACHED);
		}
	}

	static void updateReadyBoarding(
		ScanWorkspace workspace,
		int candidateWarningState,
		int readySlot,
		int accessTransition,
		int earliestDepartureSeconds,
		byte warningBits,
		boolean hasDeadline
	) {
		for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
			if (workspace.readyActive[w]
				&& w != candidateWarningState
				&& (w & candidateWarningState) == w
				&& workspace.readyEarliestDepartureSeconds[w] <= earliestDepartureSeconds) {
				return;
			}
		}
		for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
			if (workspace.readyActive[w]
				&& w != candidateWarningState
				&& (candidateWarningState & w) == candidateWarningState
				&& earliestDepartureSeconds <= workspace.readyEarliestDepartureSeconds[w]) {
				workspace.readyActive[w] = false;
			}
		}
		if (workspace.readyActive[candidateWarningState]) {
			if (compareReadyBoardingKeys(
				earliestDepartureSeconds, warningBits, readySlot,
				workspace.readyEarliestDepartureSeconds[candidateWarningState],
				workspace.readyWarningBits[candidateWarningState],
				workspace.readySlots[candidateWarningState],
				hasDeadline) < 0) {
				workspace.readyEarliestDepartureSeconds[candidateWarningState] = earliestDepartureSeconds;
				workspace.readyAccessTransitions[candidateWarningState] = accessTransition;
				workspace.readySlots[candidateWarningState] = readySlot;
				workspace.readyWarningBits[candidateWarningState] = warningBits;
			}
		} else {
			workspace.readyActive[candidateWarningState] = true;
			workspace.readyEarliestDepartureSeconds[candidateWarningState] = earliestDepartureSeconds;
			workspace.readyAccessTransitions[candidateWarningState] = accessTransition;
			workspace.readySlots[candidateWarningState] = readySlot;
			workspace.readyWarningBits[candidateWarningState] = warningBits;
		}
	}

	@SuppressWarnings({"java:S107", "java:S3776"})
	private static void evaluateFootpathsIntoReady(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int station,
		int boardingLine,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		int boardingDeadlineSeconds,
		RealtimeOverlay realtimeOverlay
	) {
		OutOfStationFootpath[] footpaths = timetable.footpathsToStationLine(station, boardingLine);
		if (footpaths == null) {
			return;
		}
		boolean earlyPruned = false;
		for (int fpIndex = 0; fpIndex < footpaths.length; fpIndex += 1) {
			OutOfStationFootpath footpath = footpaths[fpIndex];
			boolean footpathDominated = true;
			int minDepartureForFootpath = Integer.MAX_VALUE;
			for (int accessTransition : footpath.candidateTransitions()) {
				if ((realtimeOverlay != null && realtimeOverlay.isTransitionBlocked(accessTransition))
					|| !timetable.isTransitionEligible(
					accessTransition, accessProfileBit, ignoreAccessBlocks,
					input.requiresVerifiedJourneyDistance(), true)) {
					continue;
				}
				for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
					int readySlot = workspace.slot(round, footpath.fromStation(), footpath.fromLine(), warningState);
					int readySeconds = workspace.arrivalSeconds[readySlot];
					if (readySeconds == UNREACHED) {
						continue;
					}
					int earliestDepartureSeconds = readySeconds
						+ journeyAccessSeconds(input, JourneyAccessKind.TRANSFER,
							timetable.transitionDurationSeconds(accessTransition),
							timetable.transitionDistanceMeters(accessTransition))
						+ slackSeconds;
					if (earliestDepartureSeconds < minDepartureForFootpath) {
						minDepartureForFootpath = earliestDepartureSeconds;
					}
					if (earliestDepartureSeconds > boardingDeadlineSeconds) {
						continue;
					}
					byte warningBits = (byte) (workspace.warningBits[readySlot]
						| timetable.transitionWarningCodes(accessTransition, accessProfileBit, ignoreAccessBlocks));
					int candidateWarningState = Byte.toUnsignedInt(warningBits);
					if (workspace.isDominatedByTarget(station, earliestDepartureSeconds, candidateWarningState)) {
						continue;
					}
					footpathDominated = false;
					updateReadyBoarding(
						workspace, candidateWarningState, readySlot, accessTransition,
						earliestDepartureSeconds, warningBits, boardingDeadlineSeconds != UNREACHED);
				}
				if (footpathDominated && minDepartureForFootpath != Integer.MAX_VALUE
					&& workspace.isTargetDominatingDeparture(station, minDepartureForFootpath)) {
					break;
				}
			}
			if (footpathDominated && minDepartureForFootpath != Integer.MAX_VALUE
				&& workspace.isTargetDominatingDeparture(station, minDepartureForFootpath)) {
				boolean allRemainingDominated = true;
				for (int nextIdx = fpIndex + 1; nextIdx < footpaths.length; nextIdx += 1) {
					OutOfStationFootpath nextFp = footpaths[nextIdx];
					boolean thisFootpathDominated = true;
					for (int nextCandidate : nextFp.candidateTransitions()) {
						if ((realtimeOverlay != null && realtimeOverlay.isTransitionBlocked(nextCandidate))
							|| !timetable.isTransitionEligible(
							nextCandidate, accessProfileBit, ignoreAccessBlocks,
							input.requiresVerifiedJourneyDistance(), true)) {
							continue;
						}
						for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
							int slot = workspace.slot(round, nextFp.fromStation(), nextFp.fromLine(), warningState);
							int ready = workspace.arrivalSeconds[slot];
							if (ready == UNREACHED) {
								continue;
							}
							int dep = ready
								+ journeyAccessSeconds(input, JourneyAccessKind.TRANSFER,
									timetable.transitionDurationSeconds(nextCandidate),
									timetable.transitionDistanceMeters(nextCandidate))
								+ slackSeconds;
							byte warningBits = (byte) (workspace.warningBits[slot]
								| timetable.transitionWarningCodes(nextCandidate, accessProfileBit, ignoreAccessBlocks));
							int candidateWarningState = Byte.toUnsignedInt(warningBits);
							if (!workspace.isDominatedByTarget(station, dep, candidateWarningState)) {
								thisFootpathDominated = false;
								break;
							}
						}
						if (!thisFootpathDominated) {
							break;
						}
					}
					if (!thisFootpathDominated) {
						allRemainingDominated = false;
						break;
					}
				}
				if (allRemainingDominated) {
					earlyPruned = true;
					break;
				}
			}
			if (earlyPruned) {
				break;
			}
		}
	}

	static ReadyBoarding updateBestReadyBoarding(
		ReadyBoarding currentBest,
		int readySlot,
		int accessTransition,
		int earliestDepartureSeconds,
		byte warningBits,
		boolean hasDeadline
	) {
		if (currentBest == null || compareReadyBoardingKeys(
			earliestDepartureSeconds, warningBits, readySlot,
			currentBest.earliestDepartureSeconds(), currentBest.warningBits(), currentBest.readySlot(),
			hasDeadline
		) < 0) {
			return new ReadyBoarding(readySlot, accessTransition, earliestDepartureSeconds, warningBits);
		}
		return currentBest;
	}

	static ReadyBoarding bestReadyBoarding(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		int station,
		int boardingLine,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		int boardingDeadlineSeconds
	) {
		collectReadyBoardings(
			timetable, workspace, station, boardingLine, round, slackSeconds,
			accessProfileBit, input, ignoreAccessBlocks, boardingDeadlineSeconds);
		ReadyBoarding best = null;
		for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
			if (!workspace.readyActive[w]) {
				continue;
			}
			best = updateBestReadyBoarding(
				best,
				workspace.readySlots[w],
				workspace.readyAccessTransitions[w],
				workspace.readyEarliestDepartureSeconds[w],
				workspace.readyWarningBits[w],
				boardingDeadlineSeconds != UNREACHED);
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
		ScanInput input,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		// #2534: 스캔은 경고를 Pareto 차원으로 유지하는데(ScanWorkspace.relax의 경고 부분집합 지배),
		// 추출이 환승 수마다 라벨 1개만 남기면 그 차원이 버려져 PREFER_* 프로파일에서
		// "느리지만 무단차"인 대안이 소실된다. PREFER_*에서는 경고 상태별 최선 후보를 함께 담고
		// 지배 판정은 paretoFront가 일괄 처리한다.
		// 표시 정렬은 compareLabels(시간 우선)로 그대로 두고 여기서는 보존 집합만 넓힌다.
		boolean preserveWarningAlternatives = input.prefersStepFree();
		List<Label> labels = new ArrayList<>(PARETO_LIMIT * timetable.lineCount());
		Label[] bestByWarningState = preserveWarningAlternatives
			? new Label[WARNING_STATE_COUNT]
			: NO_WARNING_ALTERNATIVES;
		for (int boardings = 1; boardings <= PARETO_LIMIT; boardings += 1) {
			Label bestForBoardings = null;
			Arrays.fill(bestByWarningState, null);
			int lineOffset = timetable.stationLineOffset(destination);
			int lineCountAtStation = timetable.stationLineCount(destination);
			for (int lineIdx = 0; lineIdx < lineCountAtStation; lineIdx += 1) {
				int incomingLine = timetable.stationLine(lineOffset + lineIdx);
				int exitTransition = timetable.exitTransition(
					destination, incomingLine, accessProfileBit, ignoreAccessBlocks,
					input.requiresVerifiedJourneyDistance(), realtimeOverlay);
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
					int penaltySeconds = 0;
					for (int i = 1; i < path.size(); i += 1) {
						int transition = accessTransitions[i];
						if (timetable.isOutOfStationTransition(transition)) {
							RideLeg prevLeg = path.get(i - 1);
							RideLeg nextLeg = path.get(i);
							int elapsed = nextLeg.departureSeconds() - prevLeg.arrivalSeconds();
							int limit = getTransferLimitSeconds(prevLeg.arrivalSeconds(), nextLeg.departureSeconds());
							penaltySeconds += calculateJourneyPenalty(elapsed, limit);
						}
					}
					Label candidate = new Label(
						destinationStationId,
						workspace.arrivalSeconds[slot]
							+ journeyAccessSeconds(input, JourneyAccessKind.EXIT,
								timetable.transitionDurationSeconds(exitTransition),
								timetable.transitionDistanceMeters(exitTransition)),
						startSeconds,
						boardings,
						List.copyOf(path),
						accessTransitions,
						exitTransition,
						(byte) (workspace.warningBits[slot]
							| timetable.transitionWarningCodes(exitTransition, accessProfileBit, ignoreAccessBlocks)),
						penaltySeconds
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

	static boolean dominates(Label other, Label candidate, boolean warningDimension, boolean earlier) {
		if (other.boardings() > candidate.boardings() || other.virtualCostSeconds() > candidate.virtualCostSeconds()) {
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
			|| other.virtualCostSeconds() < candidate.virtualCostSeconds()
			|| other.warningBits() != candidate.warningBits()
			|| earlier;
	}

	// #2534: 후보가 상한을 넘으면 자리 하나를 선호 후보(PREFERRED_WARNING_ORDER)에 내준다.
	// 상한(candidateLimit) 자체는 그대로지만 PREFER_STEP_FREE에서는 실제 후보 수가 상한까지
	// 채워지는 빈도가 높아지므로, 후보당 하류 비용(toRouteSearchResult 재구성·실시간 재계산·
	// 직렬화)은 최악 상한배까지 늘 수 있다.
	static List<Label> limitDestinationLabels(List<Label> labels, ScanInput input) {
		List<Label> ordered = labels.stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.toList();
		int limit = input.candidateLimit();
		if (ordered.size() <= limit) {
			return ordered;
		}
		List<Label> limited = new ArrayList<>(ordered.subList(0, limit));
		if (!input.prefersStepFree()) {
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
			if (hasDuplicateBoardings(limited, limited.get(index).boardings())) {
				return index;
			}
		}
		return -1;
	}

	private static boolean hasDuplicateBoardings(List<Label> labels, int boardings) {
		int count = 0;
		for (Label label : labels) {
			if (label.boardings() == boardings) {
				count += 1;
				if (count > 1) {
					return true;
				}
			}
		}
		return false;
	}

	private static int compareDestinationLabels(Label left, Label right) {
		RideLeg leftLast = left.path().getLast();
		RideLeg rightLast = right.path().getLast();
		return compareDestinationLabelKeys(
			left.virtualCostSeconds(), left.warningBits(), leftLast.scheduledTrip().index(), leftLast.fromIndex(),
			right.virtualCostSeconds(), right.warningBits(), rightLast.scheduledTrip().index(), rightLast.fromIndex()
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


	private static int compareLabels(Label left, Label right) {
		return Comparator.comparingInt(Label::virtualCostSeconds)
			.thenComparingInt(Label::boardings)
			.thenComparingInt(label -> label.path().size())
			.compare(left, right);
	}


	static int journeyAccessSeconds(
		ScanInput input,
		JourneyAccessKind kind,
		int baselineSeconds,
		int distanceMeters
	) {
		if (kind == JourneyAccessKind.TRANSFER && input.requiresVerifiedJourneyDistance()) {
			return ProfileWalkTimeCalculator.journeySeconds(
				distanceMeters, input.walkingSpeedMetersPerHour(), input.mobilityPreset(), false);
		}
		return ProfileWalkTimeCalculator.estimateSeconds(
			baselineSeconds, input.mobilityPreset(), WalkTimeSource.OFFICIAL_BASELINE, false).seconds();
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


	static ScanInput scanInput(JourneyRaptorQuery query) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query");
		if (!(requiredQuery.temporalQuery() instanceof JourneyRaptorQuery.DepartAt departAt)) {
			throw new IllegalArgumentException("Journey RAPTOR point planner does not support temporal profile queries");
		}
		var resolved = ServiceDayResolver.resolve(departAt.readyAt());
		return scanInput(requiredQuery,
			new ServiceDay(resolved.serviceDate(), resolved.secondsFromServiceDayStart()));
	}

	private static ScanInput scanInput(JourneyRaptorQuery requiredQuery, ServiceDay serviceDay) {
		MobilityPreset mobilityPreset = switch (requiredQuery.mobilityProfile()) {
			case STANDARD -> MobilityPreset.STANDARD;
			case SLOW -> MobilityPreset.SLOW;
			case NO_STAIRS -> MobilityPreset.NO_STAIRS;
			case STEP_FREE -> MobilityPreset.STEP_FREE;
		};
		JourneyAccessProfile accessProfile = switch (requiredQuery.mobilityProfile()) {
			case STANDARD -> JourneyAccessProfile.STANDARD;
			case SLOW -> JourneyAccessProfile.SLOW;
			case NO_STAIRS -> JourneyAccessProfile.NO_STAIRS;
			case STEP_FREE -> JourneyAccessProfile.STEP_FREE;
		};
		ConstraintMode constraintMode = requiredQuery.constraintMode()
			== com.easysubway.journey.application.JourneyRequest.ConstraintMode.REQUIRE_STEP_FREE
			? ConstraintMode.STRICT_STEP_FREE
			: accessProfile == JourneyAccessProfile.STEP_FREE
				? ConstraintMode.PREFER_STEP_FREE
				: ConstraintMode.ALLOW_WITH_WARNINGS;
		return new ScanInput(
			requiredQuery.originStationId(),
			requiredQuery.destinationStationId(),
			serviceDay,
			serviceDay.departureSeconds(),
			accessProfile.profileBit(constraintMode),
			mobilityPreset,
			constraintMode,
			requiredQuery.walkingPace().speedMetersPerHour(),
			journeyBoardingSlackSeconds(accessProfile),
			true,
			requiredQuery.timePolicy()
				== com.easysubway.journey.application.JourneyRequest.TimePolicy.REALTIME_REQUIRED,
			requiredQuery.maxTransfers(),
			Math.max(requiredQuery.alternativeCount(), requiredQuery.maxTransfers() + 1),
			requiredQuery.cancellationSignal()
		);
	}

	ReverseTimetableRaptorPlanner.Query reverseArriveByQuery(
		JourneyRaptorQuery query,
		LocalDate serviceDate,
		int earliestReadyAtSeconds,
		int arrivalDeadlineSeconds
	) {
		ScanInput input = scanInput(Objects.requireNonNull(query, "query"),
			new ServiceDay(Objects.requireNonNull(serviceDate, "serviceDate"), earliestReadyAtSeconds));
		return new ReverseTimetableRaptorPlanner.Query(
			input.originStationId(), input.destinationStationId(), serviceDate, earliestReadyAtSeconds,
			arrivalDeadlineSeconds, input.maxTransfers(), input.accessProfileBit(), input.boardingSlackSeconds(),
			input.mobilityPreset(), input.walkingSpeedMetersPerHour(), input.requiresVerifiedJourneyDistance(),
			input.cancellationSignal());
	}

	ReverseTimetableRaptorPlanner.LastConnectionQuery reverseLastConnectionQuery(
		JourneyRaptorQuery query,
		LocalDate serviceDate
	) {
		ScanInput input = scanInput(Objects.requireNonNull(query, "query"),
			new ServiceDay(Objects.requireNonNull(serviceDate, "serviceDate"), 0));
		return new ReverseTimetableRaptorPlanner.LastConnectionQuery(
			input.originStationId(), input.destinationStationId(), serviceDate, input.maxTransfers(),
			input.accessProfileBit(), input.boardingSlackSeconds(), input.mobilityPreset(),
			input.walkingSpeedMetersPerHour(), input.requiresVerifiedJourneyDistance(), input.cancellationSignal());
	}


	private static int journeyBoardingSlackSeconds(JourneyAccessProfile accessProfile) {
		return switch (accessProfile) {
			case STANDARD, NO_STAIRS -> 60;
			case SLOW -> 90;
			case STEP_FREE -> 180;
		};
	}

	private static void throwIfCancelled(ScanInput input) {
		if (input.cancellationSignal().getAsBoolean()) {
			throw new IllegalStateException("Journey RAPTOR scan cancelled");
		}
	}

	CompiledTimetable compile(RouteTimetable timetable) {
		return new CompiledTimetable(timetable);
	}

	boolean matchesActiveJourneyRealtimeDeparture(
		CompiledTimetable timetable,
		JourneyTimetableRealtimeResolver.Departure departure
	) {
		if (timetable == null || departure == null || departure.serviceDate() == null) {
			return false;
		}
		int matches = 0;
		for (ScheduledTrip trip : timetable.activeServiceDay(departure.serviceDate()).trips()) {
			if (!trip.trip().id().equals(departure.tripId())
				|| !Objects.equals(trip.trip().trainNo(), departure.trainNo())
				|| !Objects.equals(trip.trip().servicePattern(), departure.servicePattern())) {
				continue;
			}
			for (int stopIndex = 0; stopIndex < trip.stopTimes().size(); stopIndex += 1) {
				TransitStopTime stop = trip.stopTimes().get(stopIndex);
				if (stop.stopSequence() != departure.stopSequence()
					|| !Objects.equals(stop.stationId(), departure.stationId())
					|| !Objects.equals(stop.lineId(), departure.lineId())
					|| !serviceInstant(new ServiceDay(departure.serviceDate(), 0), trip.arrivalSeconds(stopIndex))
						.equals(departure.scheduledArrivalAt())
					|| !serviceInstant(new ServiceDay(departure.serviceDate(), 0), trip.departureSeconds(stopIndex))
						.equals(departure.scheduledDepartureAt())) {
					continue;
				}
				matches += 1;
			}
		}
		return matches == 1;
	}

	List<DepartureEvent> departureEvents(
		ActiveServiceDay activeServiceDay,
		String originStationId,
		int earliestDepartureSeconds,
		int latestDepartureSeconds,
		RealtimeOverlay realtimeOverlay
	) {
		Objects.requireNonNull(activeServiceDay, "activeServiceDay must not be null");
		Objects.requireNonNull(originStationId, "originStationId must not be null");
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		if (earliestDepartureSeconds > latestDepartureSeconds) {
			throw new IllegalArgumentException("earliestDepartureSeconds must not exceed latestDepartureSeconds");
		}
		return activeServiceDay.boardingsByStation().getOrDefault(originStationId, List.of()).stream()
			.filter(boarding -> boarding.trip().allowsPickup(boarding.stopIndex()))
			.filter(boarding -> !realtimeOverlay.cancelled(boarding.trip()))
			.map(boarding -> new DepartureEvent(
				boarding.trip(),
				boarding.stopIndex(),
				realtimeOverlay.departureSeconds(boarding.trip(), boarding.stopIndex())))
			.filter(event -> event.effectiveDepartureSeconds() >= earliestDepartureSeconds
				&& event.effectiveDepartureSeconds() <= latestDepartureSeconds)
			.sorted(Comparator.comparingInt(DepartureEvent::effectiveDepartureSeconds).reversed()
				.thenComparingInt(event -> event.scheduledTrip().index())
				.thenComparingInt(DepartureEvent::stopIndex))
			.toList();
	}

	List<JourneyDepartureProfilePoint> departureProfile(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits planningLimits
	) {
		return departureProfile(query, timetable, ignored -> realtimeOverlay, planningLimits, null);
	}

	List<JourneyDepartureProfilePoint> departureProfile(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits planningLimits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		return departureProfile(query, timetable, ignored -> realtimeOverlay, planningLimits, observations);
	}

	List<JourneyDepartureProfilePoint> departureProfile(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		RaptorRealtimeRuntimeView realtimeRuntime,
		JourneyProfileResourcePolicy.ProfilePlanningLimits planningLimits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(realtimeRuntime, "realtimeRuntime must not be null");
		return departureProfile(query, timetable, realtimeRuntime::realtimeOverlay, planningLimits, observations);
	}

	private List<JourneyDepartureProfilePoint> departureProfile(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		Function<LocalDate, RealtimeOverlay> overlays,
		JourneyProfileResourcePolicy.ProfilePlanningLimits planningLimits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		JourneyRaptorQuery requiredQuery = Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		Objects.requireNonNull(overlays, "overlays must not be null");
		JourneyProfileResourcePolicy.ProfilePlanningLimits requiredLimits = Objects.requireNonNull(
			planningLimits, "planningLimits");
		if (!(requiredQuery.temporalQuery() instanceof JourneyRaptorQuery.DepartBetween range)) {
			throw new IllegalArgumentException("Journey departure profile requires DEPART_BETWEEN");
		}
		var earliest = ServiceDayResolver.resolve(range.earliestReadyAt());
		var latest = ServiceDayResolver.resolve(range.latestReadyAt());
		ProfileLimitTracker limits = new ProfileLimitTracker(requiredLimits, observations);
		ProfileDatedTripOccurrences datedTrips = profileDatedTripOccurrences(
			requiredQuery, timetable, overlays, range.earliestReadyAt(), range.latestReadyAt(), limits);
		List<JourneyDepartureProfilePoint> profile = new ArrayList<>();
		for (LocalDate serviceDate = latest.serviceDate();; serviceDate = serviceDate.minusDays(1)) {
			int earliestReadyAtSeconds = serviceDate.equals(earliest.serviceDate())
				? earliest.secondsFromServiceDayStart() : serviceDayCutoffSeconds(serviceDate);
			int latestReadyAtSeconds = serviceDate.equals(latest.serviceDate())
				? latest.secondsFromServiceDayStart() : nextServiceDayCutoffSeconds(serviceDate) - 1;
			throwIfCancelled(scanInput(requiredQuery, new ServiceDay(serviceDate, latestReadyAtSeconds)));
			limits.consumeWork();
			profile.addAll(departureProfileSlice(
				requiredQuery,
				timetable,
				datedTrips,
				serviceDate,
				earliestReadyAtSeconds,
				latestReadyAtSeconds,
				limits));
			if (serviceDate.equals(earliest.serviceDate())) break;
		}
		return List.copyOf(profile);
	}

	private static ProfileDatedTripOccurrences profileDatedTripOccurrences(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		Function<LocalDate, RealtimeOverlay> overlays,
		Instant earliestReadyAt,
		Instant latestReadyAt,
		ProfileLimitTracker limits
	) {
		// 지원되는 원본 운행시각 범위가 요청과 겹치는 날짜만 선택한다.
		LocalDate firstNativeServiceDate = earliestReadyAt
			.minusSeconds(LoadRouteTimetablePort.SERVICE_DAY_SECONDS_LIMIT_EXCLUSIVE)
			.atZone(SERVICE_ZONE).toLocalDate().plusDays(1);
		LocalDate lastNativeServiceDate = latestReadyAt.atZone(SERVICE_ZONE).toLocalDate();
		Map<Integer, List<ProfileDatedTripOccurrence>> tripsByPattern = new HashMap<>();
		for (LocalDate nativeServiceDate = firstNativeServiceDate;; nativeServiceDate = nativeServiceDate.plusDays(1)) {
			ScanInput cancellationInput = scanInput(query, new ServiceDay(nativeServiceDate, 0));
			throwIfCancelled(cancellationInput);
			limits.consumeWork();
			ActiveServiceDay activeServiceDay = timetable.activeServiceDay(nativeServiceDate);
			if (!activeServiceDay.trips().isEmpty()) {
				RealtimeOverlay overlay = Objects.requireNonNull(overlays.apply(nativeServiceDate),
					"realtime overlay must not be null");
				for (int pattern = 0; pattern < timetable.routePatternCount(); pattern += 1) {
					throwIfCancelled(cancellationInput);
					limits.consumeWork();
					for (ScheduledTrip trip : activeServiceDay.tripsByPattern(pattern)) {
						throwIfCancelled(cancellationInput);
						limits.consumeWork();
						tripsByPattern.computeIfAbsent(pattern, ignored -> new ArrayList<>())
							.add(new ProfileDatedTripOccurrence(nativeServiceDate, trip, overlay));
					}
				}
			}
			if (nativeServiceDate.equals(lastNativeServiceDate)) break;
		}
		return new ProfileDatedTripOccurrences(tripsByPattern);
	}

	private List<JourneyDepartureProfilePoint> departureProfileSlice(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		ProfileDatedTripOccurrences datedTrips,
		LocalDate serviceDate,
		int earliestReadyAtSeconds,
		int latestReadyAtSeconds,
		ProfileLimitTracker limits
	) {
		if (earliestReadyAtSeconds > latestReadyAtSeconds) {
			return List.of();
		}
		ServiceDay serviceDay = new ServiceDay(serviceDate, latestReadyAtSeconds);
		ScanInput profileInput = scanInput(query, serviceDay);
		throwIfCancelled(profileInput);
		ProfileDatedTripView trips = datedTrips.forReadinessAnchor(serviceDay.date());
		if (trips.isEmpty()) return List.of();
		int origin = timetable.stationIndex(profileInput.originStationId());
		int destination = timetable.stationIndex(profileInput.destinationStationId());
		if (origin < 0 || destination < 0) {
			return List.of();
		}

		int slackSeconds = profileInput.boardingSlackSeconds();
		int accessProfileBit = profileInput.accessProfileBit();
		// 시간대 이후 첫 열차를 기다릴 수 있으므로 마지막 준비시각에서도 탐색을 시작한다.
		List<Integer> breakpoints = java.util.stream.Stream.concat(
			java.util.stream.Stream.of(latestReadyAtSeconds), trips.departureEvents(
			profileInput.originStationId(),
			0,
			Integer.MAX_VALUE
		).stream().map(event -> readyAtBreakpoint(
			profileInput, timetable, origin, accessProfileBit, slackSeconds, event, limits.observations))
			.filter(OptionalIntValue::present)
			.mapToInt(OptionalIntValue::value)
			.filter(readyAt -> readyAt >= earliestReadyAtSeconds
				&& readyAt <= latestReadyAtSeconds)
			.boxed())
			.distinct()
			.sorted(Comparator.reverseOrder())
			.toList();

		limits.reserveBreakpoints(breakpoints.size());
		ProfileMultiLabelForwardScan scan = new ProfileMultiLabelForwardScan(
			profileInput, timetable, trips, limits);
		List<JourneyDepartureProfilePoint> profile = new ArrayList<>(breakpoints.size());
		for (int readyAtSeconds : breakpoints) {
			ScanInput input = scanInput(query, new ServiceDay(serviceDay.date(), readyAtSeconds));
			if (!scan.improveOrigin(origin, readyAtSeconds)) continue;
			scan.propagate();
			List<JourneyItinerary> itineraries = scan.destinationItineraries(
				input, destination, accessProfileBit);
			if (itineraries.isEmpty()) continue;
			profile.add(new JourneyDepartureProfilePoint(
				serviceDay.date(),
				readyAtSeconds,
				itineraries,
				scan.scanMetrics()));
		}
		return List.copyOf(profile);
	}

	private static int serviceDayCutoffSeconds(LocalDate serviceDate) {
		return Math.toIntExact(Duration.between(
			serviceDate.atStartOfDay(SERVICE_ZONE),
			serviceDate.atTime(LocalTime.parse(ServiceDayResolver.CUTOFF_LOCAL_TIME)).atZone(SERVICE_ZONE)
		).toSeconds());
	}

	private static int nextServiceDayCutoffSeconds(LocalDate serviceDate) {
		return Math.toIntExact(Duration.between(
			serviceDate.atStartOfDay(SERVICE_ZONE),
			serviceDate.plusDays(1).atTime(LocalTime.parse(ServiceDayResolver.CUTOFF_LOCAL_TIME)).atZone(SERVICE_ZONE)
		).toSeconds());
	}

	private static OptionalIntValue readyAtBreakpoint(
		ScanInput input,
		CompiledTimetable timetable,
		int origin,
		int accessProfileBit,
		int slackSeconds,
		ProfileDepartureEvent event,
		JourneyProfilePruningObservationAccumulator observations
	) {
		int boardingLine = timetable.lineIndex(event.trip().scheduledTrip().lineId(event.stopIndex()));
		if (boardingLine < 0) {
			return OptionalIntValue.empty();
		}
		int entryTransition = timetable.entryTransition(
			origin, boardingLine, accessProfileBit, false, input.requiresVerifiedJourneyDistance());
		if (entryTransition < 0) {
			if (observations != null) observations.increment("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
			return OptionalIntValue.empty();
		}
		int entrySeconds = journeyAccessSeconds(
			input,
			JourneyAccessKind.ENTRY,
			timetable.transitionDurationSeconds(entryTransition),
			timetable.transitionDistanceMeters(entryTransition));
		return OptionalIntValue.of(event.effectiveDepartureSeconds() - entrySeconds - slackSeconds);
	}

	List<JourneyTimetableRealtimeResolver.Query> realtimeQueries(
		JourneyRaptorQuery query,
		CompiledTimetable timetable
	) {
		ScanInput input = scanInput(query);
		Map<String, List<JourneyTimetableRealtimeResolver.Departure>> departuresByLine = new LinkedHashMap<>();
		for (ScheduledTrip trip : timetable.activeServiceDay(input.serviceDay().date()).trips()) {
			if (trip.trip().trainNo() == null) {
				continue;
			}
			for (int stopIndex = 0; stopIndex < trip.stopTimes().size(); stopIndex += 1) {
				TransitStopTime stop = trip.stopTimes().get(stopIndex);
				if (!input.originStationId().equals(stop.stationId()) || !trip.allowsPickup(stopIndex)) {
					continue;
				}
				departuresByLine.computeIfAbsent(stop.lineId(), ignored -> new ArrayList<>())
					.add(new JourneyTimetableRealtimeResolver.Departure(
						stop.stationId(), stop.lineId(), trip.trip().id(), trip.trip().trainNo(), trip.trip().servicePattern(),
						input.serviceDay().date(), stop.stopSequence(),
						serviceInstant(input.serviceDay(), trip.arrivalSeconds(stopIndex)),
						serviceInstant(input.serviceDay(), trip.departureSeconds(stopIndex))));
				break;
			}
		}
		Instant readyAt = serviceInstant(input.serviceDay(), input.readyAtSeconds());
		return departuresByLine.entrySet().stream()
			.map(entry -> new JourneyTimetableRealtimeResolver.Query(
				input.originStationId(), entry.getKey(), readyAt, entry.getValue()))
			.toList();
	}


	RealtimeOverlay compileRealtimeOverlay(
		CompiledTimetable timetable,
		TimetableRealtimeUpdates realtimeUpdates
	) {
		if (realtimeUpdates == null || !realtimeUpdates.available()) {
			return RealtimeOverlay.empty();
		}
		BitSet blockedTransitions = new BitSet();
		if (realtimeUpdates.blockedPathwayEdgeIds() != null) {
			for (String edgeId : realtimeUpdates.blockedPathwayEdgeIds()) {
				if (edgeId != null && !edgeId.isBlank()) {
					for (int transition : timetable.transitionIdsForEdge(edgeId)) {
						blockedTransitions.set(transition);
					}
				}
			}
		}
		List<IndexedRealtimeUpdate> indexed = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		Set<Integer> affectedPatterns = new HashSet<>();
		List<TimetableRealtimeUpdate> updatesList = realtimeUpdates.updates();
		if (updatesList != null) {
			for (TimetableRealtimeUpdate update : updatesList) {
				int scheduledTripIndex = timetable.uniqueScheduledTripIndex(update.tripId());
				if (scheduledTripIndex < 0 || !seen.add(scheduledTripIndex)
					|| !validRealtimeUpdate(timetable.scheduledTrip(scheduledTripIndex), update)) {
					return RealtimeOverlay.empty();
				}
				indexed.add(new IndexedRealtimeUpdate(scheduledTripIndex, update));
				affectedPatterns.add(timetable.patternOfScheduledTrip(scheduledTripIndex));
			}
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
			affectedPatterns.stream().mapToInt(Integer::intValue).sorted().toArray(),
			blockedTransitions);
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

	public static record OutOfStationFootpath(
		int fromStation,
		int fromLine,
		int toStation,
		int toLine,
		int[] candidateTransitions
	) {
		public OutOfStationFootpath {
			candidateTransitions = candidateTransitions == null ? new int[0] : candidateTransitions.clone();
		}

		@Override
		public int[] candidateTransitions() {
			return candidateTransitions.clone();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof OutOfStationFootpath other)) return false;
			return fromStation == other.fromStation
				&& fromLine == other.fromLine
				&& toStation == other.toStation
				&& toLine == other.toLine
				&& Arrays.equals(candidateTransitions, other.candidateTransitions);
		}

		@Override
		public int hashCode() {
			int result = Objects.hash(fromStation, fromLine, toStation, toLine);
			result = 31 * result + Arrays.hashCode(candidateTransitions);
			return result;
		}

		@Override
		public String toString() {
			return "OutOfStationFootpath[fromStation=" + fromStation
				+ ", fromLine=" + fromLine
				+ ", toStation=" + toStation
				+ ", toLine=" + toLine
				+ ", candidateTransitions=" + Arrays.toString(candidateTransitions) + "]";
		}
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
		private final OutOfStationFootpath[][] footpathsByFromStation;
		private final OutOfStationFootpath[][] footpathsByToStationLine;
		private final LinkedHashMap<LocalDate, ActiveServiceDay> activeServiceDays = new LinkedHashMap<>(16, 0.75f, true);
		private final int[] stationLineOffsets;
		private final int[] stationLines;
		private final int[] stationSlotOffsets;
		private final int totalStationSlots;

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
			int numStations = stationIndex.size();
			int numLines = lineIndex.size();
			List<List<OutOfStationFootpath>> fromList = new ArrayList<>(numStations);
			for (int i = 0; i < numStations; i += 1) {
				fromList.add(new ArrayList<>());
			}
			List<List<OutOfStationFootpath>> toList = new ArrayList<>(numStations * numLines);
			for (int i = 0; i < numStations * numLines; i += 1) {
				toList.add(new ArrayList<>());
			}
			for (OutOfStationFootpath footpath : accessTransitions.outOfStationFootpaths()) {
				fromList.get(footpath.fromStation()).add(footpath);
				toList.get(footpath.toStation() * numLines + footpath.toLine()).add(footpath);
			}
			footpathsByFromStation = new OutOfStationFootpath[numStations][];
			for (int i = 0; i < numStations; i += 1) {
				List<OutOfStationFootpath> list = fromList.get(i);
				footpathsByFromStation[i] = list.isEmpty() ? null : list.toArray(OutOfStationFootpath[]::new);
			}
			footpathsByToStationLine = new OutOfStationFootpath[numStations * numLines][];
			for (int i = 0; i < numStations * numLines; i += 1) {
				List<OutOfStationFootpath> list = toList.get(i);
				if (!list.isEmpty()) {
					list.sort(Comparator.comparingInt((OutOfStationFootpath fp) ->
						accessTransitions.durationSeconds(fp.candidateTransitions()[0]))
						.thenComparingInt(OutOfStationFootpath::fromStation)
						.thenComparingInt(OutOfStationFootpath::fromLine));
				}
				footpathsByToStationLine[i] = list.isEmpty() ? null : list.toArray(OutOfStationFootpath[]::new);
			}

			List<Set<Integer>> linesByStation = new ArrayList<>(numStations);
			for (int i = 0; i < numStations; i += 1) {
				linesByStation.add(new java.util.TreeSet<>());
			}
			for (TransitStopTime st : source.transitStopTimes()) {
				Integer station = stationIndex.get(st.stationId());
				Integer line = lineIndex.get(st.lineId());
				if (station != null && line != null) {
					linesByStation.get(station).add(line);
				}
			}
			for (OutOfStationFootpath fp : accessTransitions.outOfStationFootpaths()) {
				linesByStation.get(fp.fromStation()).add(fp.fromLine());
				linesByStation.get(fp.toStation()).add(fp.toLine());
			}
			stationLineOffsets = new int[numStations + 1];
			stationSlotOffsets = new int[numStations + 1];
			int totalLines = 0;
			for (int i = 0; i < numStations; i += 1) {
				stationLineOffsets[i] = totalLines;
				totalLines += linesByStation.get(i).size();
			}
			stationLineOffsets[numStations] = totalLines;
			stationLines = new int[totalLines];
			int writePos = 0;
			int totalSlots = 0;
			for (int i = 0; i < numStations; i += 1) {
				stationSlotOffsets[i] = totalSlots;
				Set<Integer> lines = linesByStation.get(i);
				for (int line : lines) {
					stationLines[writePos++] = line;
				}
				totalSlots += lines.size() + 1;
			}
			stationSlotOffsets[numStations] = totalSlots;
			totalStationSlots = totalSlots;
		}

		int[] stationLineOffsets() {
			return stationLineOffsets.clone();
		}

		int[] stationLines() {
			return stationLines.clone();
		}

		int[] stationSlotOffsets() {
			return stationSlotOffsets.clone();
		}

		int stationLineCount(int station) {
			return stationLineOffsets[station + 1] - stationLineOffsets[station];
		}

		int stationLine(int station, int localIndex) {
			return stationLines[stationLineOffsets[station] + localIndex];
		}

		int stationLine(int globalOffset) {
			return stationLines[globalOffset];
		}

		int stationLineOffset(int station) {
			return stationLineOffsets[station];
		}

		int stationLineLocalIndex(int station, int line) {
			int start = stationLineOffsets[station];
			int end = stationLineOffsets[station + 1];
			for (int i = start; i < end; i += 1) {
				if (stationLines[i] == line) {
					return i - start;
				}
			}
			return -1;
		}

		int totalStationSlots() {
			return totalStationSlots;
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

		int[] transitionIdsForEdge(String edgeId) {
			return accessTransitions.transitionIdsForEdge(edgeId);
		}
		int entryTransition(
			int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance,
			RealtimeOverlay realtimeOverlay
		) {
			return accessTransitions.entry(station, line, profileBit, ignoreBlocked, requireVerifiedDistance, realtimeOverlay);
		}
		int entryTransition(
			int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance
		) {
			return entryTransition(station, line, profileBit, ignoreBlocked, requireVerifiedDistance, null);
		}
		int entryTransition(int station, int line, int profileBit, boolean ignoreBlocked) {
			return entryTransition(station, line, profileBit, ignoreBlocked, false, null);
		}
		int exitTransition(
			int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance,
			RealtimeOverlay realtimeOverlay
		) {
			return accessTransitions.exit(station, line, profileBit, ignoreBlocked, requireVerifiedDistance, realtimeOverlay);
		}
		int exitTransition(
			int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance
		) {
			return exitTransition(station, line, profileBit, ignoreBlocked, requireVerifiedDistance, null);
		}
		int exitTransition(int station, int line, int profileBit, boolean ignoreBlocked) {
			return exitTransition(station, line, profileBit, ignoreBlocked, false, null);
		}
		int transferTransition(
			int station, int fromLine, int toLine, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance,
			RealtimeOverlay realtimeOverlay
		) {
			return accessTransitions.transfer(
				station, fromLine, toLine, profileBit, ignoreBlocked, requireVerifiedDistance, realtimeOverlay);
		}
		int transferTransition(
			int station, int fromLine, int toLine, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance
		) {
			return transferTransition(station, fromLine, toLine, profileBit, ignoreBlocked, requireVerifiedDistance, null);
		}
		int transferTransition(int station, int fromLine, int toLine, int profileBit, boolean ignoreBlocked) {
			return transferTransition(station, fromLine, toLine, profileBit, ignoreBlocked, false, null);
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
		OutOfStationFootpath[] footpathsFromStation(int station) {
			return footpathsByFromStation[station];
		}
		OutOfStationFootpath[] footpathsToStationLine(int station, int line) {
			return footpathsByToStationLine[station * lineCount() + line];
		}
		boolean isOutOfStationTransition(int transition) {
			return accessTransitions.isOutOfStation(transition);
		}
		int selectTransition(
			int[] candidates,
			int profileBit,
			boolean ignoreBlocked,
			boolean requireVerifiedDistance
		) {
			return accessTransitions.select(candidates, profileBit, ignoreBlocked, requireVerifiedDistance);
		}
		int[] entryTransitions(int station, int line) {
			return accessTransitions.entryCandidates(station, line);
		}
		int[] transferTransitions(int station, int fromLine, int toLine) {
			return accessTransitions.transferCandidates(station, fromLine, toLine);
		}
		int allocatedTransferSlotCount() {
			return accessTransitions.allocatedTransferSlotCount();
		}
		int[] exitTransitions(int station, int line) {
			return accessTransitions.exitCandidates(station, line);
		}
		boolean isTransitionEligible(
			int transition,
			int profileBit,
			boolean ignoreBlocked,
			boolean requireVerifiedDistance,
			boolean requirePositiveDistance
		) {
			return accessTransitions.isEligible(
				transition, profileBit, ignoreBlocked, requireVerifiedDistance, requirePositiveDistance);
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

		int patternStopCount(int pattern) {
			return stopsByPattern(pattern).length;
		}

		int[] patternsByStop(int station) {
			return patternsByStop.getOrDefault(station, NO_PATTERNS);
		}

		ScheduledTrip scheduledTrip(int index) {
			return scheduledTrips.get(index);
		}

		ScheduledTrip scheduledTripAtPattern(ActiveServiceDay serviceDay, int pattern, int tripOffset) {
			return Objects.requireNonNull(serviceDay, "serviceDay").tripsByPattern(pattern).get(tripOffset);
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
		private final int stationCount;
		private final int lineCount;
		private final int[][] entryTransitions;
		private final int[][] exitTransitions;
		private final long[] transferKeys;
		private final int[][] transferTransitions;
		private final int[] durationSeconds;
		private final int[] distanceMeters;
		private final int[] blockedProfiles;
		private final int[] warningProfiles;
		private final byte[] warningCodes;
		private final boolean[] includesStairs;
		private final String[] edgeIds;
		private final Map<String, int[]> edgeTransitions;
		private final String[] verificationStatuses;
		private final boolean[] outOfStation;
		private final List<OutOfStationFootpath> outOfStationFootpaths;
		private final int unsupportedTransferCount;
		private AccessTransitions(
			int stationCount,
			int lineCount,
			int[][] entryTransitions,
			int[][] exitTransitions,
			long[] transferKeys,
			int[][] transferTransitions,
			List<Candidate> candidates,
			boolean[] outOfStation,
			List<OutOfStationFootpath> outOfStationFootpaths,
			int unsupportedTransferCount
		) {
			this.stationCount = stationCount;
			this.lineCount = lineCount;
			this.entryTransitions = entryTransitions;
			this.exitTransitions = exitTransitions;
			this.transferKeys = transferKeys;
			this.transferTransitions = transferTransitions;
			this.outOfStation = outOfStation;
			this.outOfStationFootpaths = outOfStationFootpaths;
			this.unsupportedTransferCount = unsupportedTransferCount;
			durationSeconds = new int[candidates.size()];
			distanceMeters = new int[candidates.size()];
			blockedProfiles = new int[candidates.size()];
			warningProfiles = new int[candidates.size()];
			warningCodes = new byte[candidates.size()];
			includesStairs = new boolean[candidates.size()];
			edgeIds = new String[candidates.size()];
			verificationStatuses = new String[candidates.size()];
			Map<String, List<Integer>> edgeMap = new HashMap<>();
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
				String edgeId = candidate.edgeId();
				if (edgeId != null) {
					edgeMap.computeIfAbsent(edgeId, ignored -> new ArrayList<>()).add(index);
				}
			}
			Map<String, int[]> compiledEdgeTransitions = new HashMap<>();
			for (Map.Entry<String, List<Integer>> entry : edgeMap.entrySet()) {
				compiledEdgeTransitions.put(entry.getKey(), entry.getValue().stream().mapToInt(Integer::intValue).toArray());
			}
			this.edgeTransitions = Map.copyOf(compiledEdgeTransitions);
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
			Map<Long, List<Candidate>> transfers = new HashMap<>();
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
			List<List<Candidate>> outFootpathCandidates = new ArrayList<>();
			List<int[]> outFootpathEndpoints = new ArrayList<>();
			for (TransferRule rule : timetable.routeAccessData().transferRules()) {
				boolean outOfStation = "OUT_OF_STATION".equals(rule.transferType())
					|| !rule.fromStationId().equals(rule.toStationId());
				if (outOfStation) {
					unsupported += 1;
				}
				Integer fromStation = stationIndex.get(rule.fromStationId());
				Integer toStation = stationIndex.get(rule.toStationId());
				Integer fromLine = lineIndex.get(rule.fromLineId());
				Integer toLine = lineIndex.get(rule.toLineId());
				if (fromStation == null || toStation == null || fromLine == null || toLine == null) {
					continue;
				}
				List<Candidate> candidates;
				if (outOfStation) {
					candidates = new ArrayList<>();
					outFootpathCandidates.add(candidates);
					outFootpathEndpoints.add(new int[] {fromStation, fromLine, toStation, toLine});
				} else {
					long key = transferKey(fromStation, fromLine, toLine, lineCount);
					candidates = transfers.computeIfAbsent(key, ignored -> new ArrayList<>());
				}
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
							long key = transferKey(station, line, toLine, lineCount);
							List<Candidate> transferList = transfers.computeIfAbsent(key, ignored -> new ArrayList<>());
							addDefaultIfEmpty(
								transferList,
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

			long[] transferKeys = transfers.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
			int[][] transferIds = new int[transferKeys.length][];
			for (int k = 0; k < transferKeys.length; k += 1) {
				long key = transferKeys[k];
				List<Candidate> candidates = transfers.get(key);
				candidates.sort(CANDIDATE_ORDER);
				int[] ids = new int[candidates.size()];
				for (int index = 0; index < candidates.size(); index += 1) {
					ids[index] = flattened.size();
					flattened.add(candidates.get(index));
				}
				transferIds[k] = ids;
			}
			int inStationCount = flattened.size();

			List<OutOfStationFootpath> outOfStationFootpaths = new ArrayList<>();
			for (int index = 0; index < outFootpathCandidates.size(); index += 1) {
				List<Candidate> candidates = outFootpathCandidates.get(index);
				if (candidates.isEmpty()) {
					continue;
				}
				candidates.sort(CANDIDATE_ORDER);
				int[] ids = new int[candidates.size()];
				for (int c = 0; c < candidates.size(); c += 1) {
					ids[c] = flattened.size();
					flattened.add(candidates.get(c));
				}
				int[] ep = outFootpathEndpoints.get(index);
				outOfStationFootpaths.add(new OutOfStationFootpath(ep[0], ep[1], ep[2], ep[3], ids));
			}
			boolean[] outOfStation = new boolean[flattened.size()];
			for (int i = inStationCount; i < flattened.size(); i += 1) {
				outOfStation[i] = true;
			}
			return new AccessTransitions(stationCount, lineCount, entryIds, exitIds, transferKeys, transferIds, flattened, outOfStation, outOfStationFootpaths, unsupported);
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
		private static long transferKey(int station, int fromLine, int toLine, int lineCount) {
			return (((long) station) * lineCount + fromLine) * lineCount + toLine;
		}
		int[] transitionIdsForEdge(String edgeId) {
			if (edgeId == null || edgeId.isBlank()) {
				return NO_TRANSITIONS;
			}
			int[] ids = edgeTransitions.get(edgeId);
			return ids != null ? ids : NO_TRANSITIONS;
		}
		private int entry(
			int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance, RealtimeOverlay realtimeOverlay
		) {
			return select(entryTransitions[stationLineKey(station, line, lineCount)], profileBit, ignoreBlocked,
				requireVerifiedDistance, false, realtimeOverlay);
		}
		private int exit(
			int station, int line, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance, RealtimeOverlay realtimeOverlay
		) {
			return select(exitTransitions[stationLineKey(station, line, lineCount)], profileBit, ignoreBlocked,
				requireVerifiedDistance, false, realtimeOverlay);
		}
		private int transfer(
			int station, int fromLine, int toLine, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance, RealtimeOverlay realtimeOverlay
		) {
			return select(transferCandidates(station, fromLine, toLine), profileBit,
				ignoreBlocked, requireVerifiedDistance, requireVerifiedDistance, realtimeOverlay);
		}
		private int select(
			int[] candidates,
			int profileBit,
			boolean ignoreBlocked,
			boolean requireVerified,
			boolean requirePositiveDistance,
			RealtimeOverlay realtimeOverlay
		) {
			RealtimeOverlay overlay = realtimeOverlay != null ? realtimeOverlay : RealtimeOverlay.empty();
			if (requireVerified) {
				int selected = -1;
				for (int transition : candidates) {
					if (overlay.isTransitionBlocked(transition)) {
						continue;
					}
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
				if (overlay.isTransitionBlocked(transition)) {
					continue;
				}
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
		private boolean isOutOfStation(int transition) {
			return outOfStation[transition];
		}
		private List<OutOfStationFootpath> outOfStationFootpaths() {
			return outOfStationFootpaths;
		}
		private int select(int[] candidates, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance) {
			return select(candidates, profileBit, ignoreBlocked, requireVerifiedDistance, requireVerifiedDistance, null);
		}
		private int[] entryCandidates(int station, int line) {
			int key = stationLineKey(station, line, lineCount);
			return key >= 0 && key < entryTransitions.length && entryTransitions[key] != null
				? entryTransitions[key] : NO_TRANSITIONS;
		}
		int allocatedTransferSlotCount() {
			return transferKeys.length;
		}
		private int[] transferCandidates(int station, int fromLine, int toLine) {
			if (station < 0 || station >= stationCount || fromLine < 0 || fromLine >= lineCount || toLine < 0 || toLine >= lineCount) {
				return NO_TRANSITIONS;
			}
			long key = transferKey(station, fromLine, toLine, lineCount);
			int index = Arrays.binarySearch(transferKeys, key);
			return index >= 0 ? transferTransitions[index] : NO_TRANSITIONS;
		}
		private int[] exitCandidates(int station, int line) {
			int key = stationLineKey(station, line, lineCount);
			return key >= 0 && key < exitTransitions.length && exitTransitions[key] != null
				? exitTransitions[key] : NO_TRANSITIONS;
		}
		private boolean isEligible(
			int transition, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance, boolean requirePositiveDistance
		) {
			if (transition < 0 || transition >= blockedProfiles.length) {
				return false;
			}
			if (!ignoreBlocked && (blockedProfiles[transition] & profileBit) != 0) {
				return false;
			}
			if (requireVerifiedDistance) {
				if (requirePositiveDistance && distanceMeters[transition] <= 0) {
					return false;
				}
				if (!"VERIFIED".equals(verificationStatuses[transition])) {
					return false;
				}
				if ((warningCodes[transition] & (WARNING_LOW_CONFIDENCE | WARNING_STALE)) != 0) {
					return false;
				}
			}
			return true;
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

		List<ScheduledTrip> tripsByPattern(int pattern) {
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

	static final class ScanWorkspace {

		private int lineStateCount;
		private int[] stationLineOffsets = new int[0];
		private int[] stationLines = new int[0];
		private int[] stationSlotOffsets = new int[0];
		private int totalStationSlots;
		private int epoch = 0;
		int[] arrivalSeconds = new int[0];
		private int[] parentTrip = new int[0];
		private int[] parentBoardStop = new int[0];
		private int[] parentAlightStop = new int[0];
		private int[] parentAccessTransition = new int[0];
		private int[] parentLabelSlot = new int[0];
		byte[] warningBits = new byte[0];
		private int[] markedStops = new int[0];
		int[] nextMarkedStops = new int[0];
		private boolean[] marked = new boolean[0];
		boolean[] nextMarked = new boolean[0];
		private int markedStopCount;
		int nextMarkedStopCount;
		private int[] markedPatterns = new int[0];
		private int[] firstMarkedPosition = new int[0];
		private int markedPatternCount;
		private int expandedRoutes;
		private int expandedTrips;
		private int expandedTransfers;
		final int[] bestTargetArrivalSeconds = new int[WARNING_STATE_COUNT];
		private int targetStation = -1;

		final ScheduledTrip[] bagTrips = new ScheduledTrip[WARNING_STATE_COUNT];
		final int[] bagBoardPositions = new int[WARNING_STATE_COUNT];
		final int[] bagEarliestDepartureSeconds = new int[WARNING_STATE_COUNT];
		final int[] bagAccessTransitions = new int[WARNING_STATE_COUNT];
		final int[] bagReadySlots = new int[WARNING_STATE_COUNT];
		final byte[] bagWarningBits = new byte[WARNING_STATE_COUNT];

		final boolean[] readyActive = new boolean[WARNING_STATE_COUNT];
		final int[] readyEarliestDepartureSeconds = new int[WARNING_STATE_COUNT];
		final int[] readyAccessTransitions = new int[WARNING_STATE_COUNT];
		final int[] readySlots = new int[WARNING_STATE_COUNT];
		final byte[] readyWarningBits = new byte[WARNING_STATE_COUNT];

		final boolean[] rtActive = new boolean[WARNING_STATE_COUNT];
		final int[] rtBoardingPositions = new int[WARNING_STATE_COUNT];
		final int[] rtEarliestDepartureSeconds = new int[WARNING_STATE_COUNT];
		final int[] rtAccessTransitions = new int[WARNING_STATE_COUNT];
		final int[] rtReadySlots = new int[WARNING_STATE_COUNT];
		final byte[] rtWarningBits = new byte[WARNING_STATE_COUNT];

		void prepare(CompiledTimetable timetable) {
			prepareInternal(
				timetable.stationCount(),
				timetable.lineCount(),
				timetable.routePatternCount(),
				timetable.stationLineOffsets,
				timetable.stationLines,
				timetable.stationSlotOffsets,
				timetable.totalStationSlots
			);
		}

		void prepare(int requiredStationCount, int lineCount, int patternCount) {
			int[] offsets = new int[requiredStationCount + 1];
			int[] slotOffsets = new int[requiredStationCount + 1];
			int[] lines = new int[requiredStationCount * lineCount];
			for (int s = 0; s < requiredStationCount; s += 1) {
				offsets[s] = s * lineCount;
				slotOffsets[s] = s * (lineCount + 1);
				for (int l = 0; l < lineCount; l += 1) {
					lines[s * lineCount + l] = l;
				}
			}
			offsets[requiredStationCount] = requiredStationCount * lineCount;
			slotOffsets[requiredStationCount] = requiredStationCount * (lineCount + 1);
			prepareInternal(
				requiredStationCount,
				lineCount,
				patternCount,
				offsets,
				lines,
				slotOffsets,
				requiredStationCount * (lineCount + 1)
			);
		}

		private void prepareInternal(
			int requiredStationCount,
			int lineCount,
			int patternCount,
			int[] offsets,
			int[] lines,
			int[] slotOffsets,
			int totalSlots
		) {
			lineStateCount = Math.addExact(lineCount, 1);
			stationLineOffsets = offsets;
			stationLines = lines;
			stationSlotOffsets = slotOffsets;
			totalStationSlots = totalSlots;
			int labelSlots = Math.addExact(
				Math.multiplyExact(Math.multiplyExact(totalSlots, LABEL_SLOT_COUNT), WARNING_STATE_COUNT),
				WARNING_STATE_COUNT
			);
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
			epoch += 1;
			targetStation = -1;
			Arrays.fill(bestTargetArrivalSeconds, 0, WARNING_STATE_COUNT, UNREACHED);
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
			clearBag();
			clearReady();
			clearRealtimeBag();
			markedStopCount = 0;
			nextMarkedStopCount = 0;
			markedPatternCount = 0;
			expandedRoutes = 0;
			expandedTrips = 0;
			expandedTransfers = 0;
		}

		void clearBag() {
			Arrays.fill(bagTrips, null);
			Arrays.fill(bagBoardPositions, -1);
			Arrays.fill(bagEarliestDepartureSeconds, UNREACHED);
			Arrays.fill(bagAccessTransitions, -1);
			Arrays.fill(bagReadySlots, -1);
			Arrays.fill(bagWarningBits, (byte) 0);
		}

		void clearReady() {
			Arrays.fill(readyActive, false);
			Arrays.fill(readyEarliestDepartureSeconds, UNREACHED);
			Arrays.fill(readyAccessTransitions, -1);
			Arrays.fill(readySlots, -1);
			Arrays.fill(readyWarningBits, (byte) 0);
		}

		void clearRealtimeBag() {
			Arrays.fill(rtActive, false);
			Arrays.fill(rtBoardingPositions, -1);
			Arrays.fill(rtEarliestDepartureSeconds, UNREACHED);
			Arrays.fill(rtAccessTransitions, -1);
			Arrays.fill(rtReadySlots, -1);
			Arrays.fill(rtWarningBits, (byte) 0);
		}

		private void pruneSlot(int slot) {
			arrivalSeconds[slot] = UNREACHED;
			parentTrip[slot] = -1;
			parentBoardStop[slot] = -1;
			parentAlightStop[slot] = -1;
			parentAccessTransition[slot] = -1;
			parentLabelSlot[slot] = -1;
			warningBits[slot] = 0;
		}

		void enforceStationFrontierCapacity(int boardings, int station, int incomingLine, int capacity) {
			int activeCount = 0;
			for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
				if (arrivalSeconds[slot(boardings, station, incomingLine, w)] != UNREACHED) {
					activeCount += 1;
				}
			}
			while (activeCount > capacity) {
				int worstWarning = -1;
				int worstArrival = -1;
				int worstWarningCount = -1;
				for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
					int s = slot(boardings, station, incomingLine, w);
					int arrival = arrivalSeconds[s];
					if (arrival == UNREACHED) {
						continue;
					}
					int wc = warningCount(warningBits[s]);
					if (worstWarning < 0
						|| arrival > worstArrival
						|| (arrival == worstArrival && (wc > worstWarningCount || (wc == worstWarningCount && w > worstWarning)))) {
						worstWarning = w;
						worstArrival = arrival;
						worstWarningCount = wc;
					}
				}
				pruneSlot(slot(boardings, station, incomingLine, worstWarning));
				activeCount -= 1;
			}
		}

		void setTargetStation(int destination) {
			targetStation = destination;
		}

		boolean isDominatedByTarget(int station, int candidateArrivalSeconds, int candidateWarningState) {
			for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
				if ((warningState & candidateWarningState) == warningState) {
					int best = bestTargetArrivalSeconds[warningState];
					if (station == targetStation ? best < candidateArrivalSeconds : best <= candidateArrivalSeconds) {
						return true;
					}
				}
			}
			return false;
		}

		boolean isTargetDominatingDeparture(int station, int earliestDepartureSeconds) {
			boolean anyTargetReached = false;
			for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
				int best = bestTargetArrivalSeconds[warningState];
				if (best != UNREACHED) {
					anyTargetReached = true;
					if (station == targetStation ? earliestDepartureSeconds <= best : earliestDepartureSeconds < best) {
						return false;
					}
				}
			}
			return anyTargetReached;
		}

		void markNext(int station) {
			if (!nextMarked[station]) {
				nextMarked[station] = true;
				nextMarkedStops[nextMarkedStopCount++] = station;
			}
		}

		int epoch() {
			return epoch;
		}

		int totalSlots() {
			return totalStationSlots * LABEL_SLOT_COUNT * WARNING_STATE_COUNT;
		}

		private int dummyUnreachedSlot(int warningState) {
			return totalStationSlots * LABEL_SLOT_COUNT * WARNING_STATE_COUNT + warningState;
		}

		int slot(int boardings, int station, int incomingLine, int warningState) {
			int localIndex;
			if (incomingLine == noIncomingLine()) {
				localIndex = stationLineOffsets[station + 1] - stationLineOffsets[station];
			} else {
				localIndex = -1;
				int start = stationLineOffsets[station];
				int end = stationLineOffsets[station + 1];
				for (int i = start; i < end; i += 1) {
					if (stationLines[i] == incomingLine) {
						localIndex = i - start;
						break;
					}
				}
				if (localIndex < 0) {
					return dummyUnreachedSlot(warningState);
				}
			}
			int stationSlot = stationSlotOffsets[station] + localIndex;
			return (stationSlot * LABEL_SLOT_COUNT + boardings) * WARNING_STATE_COUNT + warningState;
		}
		int noIncomingLine() {
			return lineStateCount - 1;
		}

		private void mark(int station) {
			if (!marked[station]) {
				marked[station] = true;
				markedStops[markedStopCount++] = station;
			}
		}

		private boolean improveOrigin(int origin, int readyAtSeconds) {
			int slot = slot(0, origin, noIncomingLine(), 0);
			if (arrivalSeconds[slot] <= readyAtSeconds) {
				return false;
			}
			arrivalSeconds[slot] = readyAtSeconds;
			mark(origin);
			return true;
		}

		void relax(
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
			if (isDominatedByTarget(station, candidateArrivalSeconds, candidateWarningState)) {
				return;
			}
			if (station == targetStation) {
				if (candidateArrivalSeconds < bestTargetArrivalSeconds[candidateWarningState]) {
					bestTargetArrivalSeconds[candidateWarningState] = candidateArrivalSeconds;
				}
				for (int w = 0; w < WARNING_STATE_COUNT; w += 1) {
					if ((candidateWarningState & w) == candidateWarningState
						&& candidateArrivalSeconds < bestTargetArrivalSeconds[w]) {
						bestTargetArrivalSeconds[w] = candidateArrivalSeconds;
					}
				}
			}
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
			for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
				if (warningState != candidateWarningState
					&& (candidateWarningState & warningState) == candidateWarningState
					&& candidateArrivalSeconds <= arrivalSeconds[slot(boardings, station, incomingLine, warningState)]) {
					pruneSlot(slot(boardings, station, incomingLine, warningState));
				}
			}
			arrivalSeconds[candidateSlot] = candidateArrivalSeconds;
			parentTrip[candidateSlot] = trip;
			parentBoardStop[candidateSlot] = boardStop;
			parentAlightStop[candidateSlot] = alightStop;
			parentAccessTransition[candidateSlot] = accessTransition;
			parentLabelSlot[candidateSlot] = previousLabelSlot;
			warningBits[candidateSlot] = accumulatedWarnings;
			enforceStationFrontierCapacity(boardings, station, incomingLine, PARETO_LIMIT);
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


	private record ServiceDay(LocalDate date, int departureSeconds) {
	}

	record ScanInput(
		String originStationId,
		String destinationStationId,
		ServiceDay serviceDay,
		int readyAtSeconds,
		int accessProfileBit,
		MobilityPreset mobilityPreset,
		ConstraintMode constraintMode,
		int walkingSpeedMetersPerHour,
		int boardingSlackSeconds,
		boolean requiresVerifiedJourneyDistance,
		boolean realtimeRequired,
		int maxTransfers,
		int candidateLimit,
		BooleanSupplier cancellationSignal
	) {
		ScanInput {
			Objects.requireNonNull(originStationId, "originStationId");
			Objects.requireNonNull(destinationStationId, "destinationStationId");
			Objects.requireNonNull(serviceDay, "serviceDay");
			Objects.requireNonNull(mobilityPreset, "mobilityPreset");
			Objects.requireNonNull(constraintMode, "constraintMode");
			Objects.requireNonNull(cancellationSignal, "cancellationSignal");
		}

		private boolean prefersStepFree() {
			return constraintMode == ConstraintMode.PREFER_STEP_FREE;
		}
	}

	private enum JourneyAccessProfile {
		STANDARD(5),
		SLOW(0),
		NO_STAIRS(5),
		STEP_FREE(2);

		private final int index;

		JourneyAccessProfile(int index) {
			this.index = index;
		}

		private int profileBit(ConstraintMode constraintMode) {
			return 1 << (index * ConstraintMode.values().length + constraintMode.ordinal());
		}
	}

	private record ScanResult(ServiceDay serviceDay, List<Label> labels, ScanMetrics scanMetrics) {
	}

	record JourneyDepartureProfilePoint(
		LocalDate serviceDate,
		int readyAtSeconds,
		List<JourneyItinerary> itineraries,
		ScanMetrics scanMetrics
	) {
		JourneyDepartureProfilePoint {
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			itineraries = List.copyOf(itineraries);
			scanMetrics = Objects.requireNonNull(scanMetrics, "scanMetrics");
		}
	}

	/**
	 * Profile-only bounded work accounting. Point and Route V2 scans intentionally do not share
	 * this state because their scalar workspace has a different correctness contract.
	 */
	static final class ProfilePlanningLimitException extends RuntimeException {
		private final ProfilePlanningLimit limit;
		private final long observed;
		private final long max;

		private ProfilePlanningLimitException(ProfilePlanningLimit limit, long observed, long max) {
			super(Objects.requireNonNull(limit, "limit").name());
			this.limit = limit;
			this.observed = observed;
			this.max = max;
		}

		ProfilePlanningLimit limit() {
			return limit;
		}

		long observed() { return observed; }
		long max() { return max; }
	}

	enum ProfilePlanningLimit {
		MAX_ESTIMATED_WORK,
		MAX_LABELS_PER_STATE,
		MAX_DESTINATION_PROFILE_LABELS,
		MAX_PROFILE_BREAKPOINTS
	}

	private static final class ProfileLimitTracker {
		private final JourneyProfileResourcePolicy.ProfilePlanningLimits limits;
		private final JourneyProfilePruningObservationAccumulator observations;
		private long work;
		private int breakpoints;

		private ProfileLimitTracker(JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
			JourneyProfilePruningObservationAccumulator observations) {
			this.limits = Objects.requireNonNull(limits, "limits");
			this.observations = observations;
		}

		private void reserveBreakpoints(int additional) {
			breakpoints = Math.addExact(breakpoints, additional);
			if (observations != null) observations.reserveProfileBreakpoints(additional);
			if (breakpoints > limits.maxProfileBreakpoints()) {
				throw new ProfilePlanningLimitException(ProfilePlanningLimit.MAX_PROFILE_BREAKPOINTS,
					breakpoints, limits.maxProfileBreakpoints());
			}
		}

		private void consumeWork() {
			work = Math.addExact(work, 1L);
			if (observations != null) observations.consumeWork();
			if (work > limits.maxEstimatedWork()) {
				throw new ProfilePlanningLimitException(ProfilePlanningLimit.MAX_ESTIMATED_WORK,
					work, limits.maxEstimatedWork());
			}
		}

		private int maxLabelsPerState() {
			return limits.maxLabelsPerState();
		}

		private int maxDestinationProfileLabels() {
			return limits.maxDestinationProfileLabels();
		}

		private void count(String ruleId) {
			if (observations != null) observations.increment(ruleId);
		}

		private void observeStateLabels(int labels) {
			if (observations != null) observations.observeStateLabels(labels);
		}

		private void observeDestinationLabels(int labels) {
			if (observations != null) observations.observeDestinationLabels(labels);
		}
	}

	/** 원본 운행일·실시간 관측을 보존하고 준비시각 기준 좌표로만 탐색한다. */
	private record ProfileDatedTripOccurrences(
		Map<Integer, List<ProfileDatedTripOccurrence>> tripsByPattern
	) {
		private ProfileDatedTripOccurrences {
			Map<Integer, List<ProfileDatedTripOccurrence>> copied = new HashMap<>();
			tripsByPattern.forEach((pattern, trips) -> copied.put(pattern, List.copyOf(trips)));
			tripsByPattern = Map.copyOf(copied);
		}

		private ProfileDatedTripView forReadinessAnchor(LocalDate anchorDate) {
			Map<Integer, List<ProfileDatedTrip>> datedTrips = new HashMap<>();
			tripsByPattern.forEach((pattern, occurrences) -> {
				List<ProfileDatedTrip> relativeTrips = occurrences.stream()
					.map(occurrence -> new ProfileDatedTrip(
						occurrence.nativeServiceDate(), occurrence.scheduledTrip(), occurrence.realtimeOverlay(),
						Math.toIntExact(Duration.between(
							anchorDate.atStartOfDay(SERVICE_ZONE),
							occurrence.nativeServiceDate().atStartOfDay(SERVICE_ZONE)).toSeconds())))
					.toList();
				datedTrips.put(pattern, relativeTrips);
			});
			return new ProfileDatedTripView(datedTrips);
		}
	}

	private record ProfileDatedTripOccurrence(
		LocalDate nativeServiceDate,
		ScheduledTrip scheduledTrip,
		RealtimeOverlay realtimeOverlay
	) {
	}

	private record ProfileDatedTrip(
		LocalDate nativeServiceDate,
		ScheduledTrip scheduledTrip,
		RealtimeOverlay realtimeOverlay,
		int readinessOffsetSeconds
	) {
		private int arrivalSeconds(int stopIndex) {
			return Math.addExact(readinessOffsetSeconds,
				realtimeOverlay.arrivalSeconds(scheduledTrip, stopIndex));
		}

		private int departureSeconds(int stopIndex) {
			return Math.addExact(readinessOffsetSeconds,
				realtimeOverlay.departureSeconds(scheduledTrip, stopIndex));
		}

		private boolean allowsPickup(int stopIndex) {
			return scheduledTrip.allowsPickup(stopIndex);
		}

		private boolean allowsDropOff(int stopIndex) {
			return scheduledTrip.allowsDropOff(stopIndex);
		}

		private boolean cancelled() {
			return realtimeOverlay.cancelled(scheduledTrip);
		}

		private List<TransitStopTime> stopTimes() {
			return scheduledTrip.stopTimes();
		}
	}

	private record ProfileDatedTripView(Map<Integer, List<ProfileDatedTrip>> tripsByPattern) {
		private ProfileDatedTripView {
			Map<Integer, List<ProfileDatedTrip>> copied = new HashMap<>();
			tripsByPattern.forEach((pattern, trips) -> copied.put(pattern, List.copyOf(trips)));
			tripsByPattern = Map.copyOf(copied);
		}

		private boolean isEmpty() {
			return tripsByPattern.isEmpty();
		}

		private List<ProfileDatedTrip> tripsByPattern(int pattern) {
			return tripsByPattern.getOrDefault(pattern, List.of());
		}

		private List<ProfileDepartureEvent> departureEvents(
			String originStationId,
			int earliestDepartureSeconds,
			int latestDepartureSeconds
		) {
			List<ProfileDepartureEvent> events = new ArrayList<>();
			for (List<ProfileDatedTrip> patternTrips : tripsByPattern.values()) {
				for (ProfileDatedTrip trip : patternTrips) {
					if (trip.cancelled()) continue;
					for (int stopIndex = 0; stopIndex < trip.stopTimes().size(); stopIndex += 1) {
						if (!originStationId.equals(trip.stopTimes().get(stopIndex).stationId())
							|| !trip.allowsPickup(stopIndex)) continue;
						int departure = trip.departureSeconds(stopIndex);
						if (departure >= earliestDepartureSeconds && departure <= latestDepartureSeconds) {
							events.add(new ProfileDepartureEvent(trip, stopIndex, departure));
						}
					}
				}
			}
			events.sort(Comparator.comparingInt(ProfileDepartureEvent::effectiveDepartureSeconds).reversed()
				.thenComparing(event -> event.trip().nativeServiceDate())
				.thenComparingInt(event -> event.trip().scheduledTrip().index())
				.thenComparingInt(ProfileDepartureEvent::stopIndex));
			return List.copyOf(events);
		}
	}

	private record ProfileDepartureEvent(
		ProfileDatedTrip trip,
		int stopIndex,
		int effectiveDepartureSeconds
	) {
	}

	/**
	 * Incremental, profile-only multi-label forward scan. A later breakpoint remains in the label
	 * state while earlier breakpoints add only newly reachable labels, so this is not a repeated
	 * point-query loop. The scalar ScanWorkspace remains the point/Route V2 implementation.
	 */
	private static final class ProfileMultiLabelForwardScan {
		private final ScanInput input;
		private final CompiledTimetable timetable;
		private final ProfileDatedTripView trips;
		private final ProfileLimitTracker limits;
		private final Map<ProfileStateKey, List<ProfileLabel>> labelsByState = new HashMap<>();
		private final ArrayDeque<ProfileLabel> pending = new ArrayDeque<>();
		private int expandedRoutes;
		private int expandedTrips;
		private int expandedTransfers;

		private ProfileMultiLabelForwardScan(
			ScanInput input,
			CompiledTimetable timetable,
			ProfileDatedTripView trips,
			ProfileLimitTracker limits
		) {
			this.input = Objects.requireNonNull(input, "input");
			this.timetable = Objects.requireNonNull(timetable, "timetable");
			this.trips = Objects.requireNonNull(trips, "trips");
			this.limits = Objects.requireNonNull(limits, "limits");
		}

		private boolean improveOrigin(int origin, int readyAtSeconds) {
			return admit(new ProfileLabel(
				readyAtSeconds, readyAtSeconds, 0, origin, -1, (byte) 0,
				0L, 0L, 0L, new JourneyProfileRaptorPort.NoTransfer(), null));
		}

		private void propagate() {
			while (!pending.isEmpty()) {
				throwIfCancelled(input);
				ProfileLabel label = pending.removeFirst();
				if (!isCurrent(label)) continue;
				if (label.boardings() > input.maxTransfers()) continue;
				for (int pattern : timetable.patternsByStop(label.station())) {
					limits.consumeWork();
					expandedRoutes += 1;
					int position = indexOf(timetable.stopsByPattern(pattern), label.station());
					if (position < 0) continue;
					List<ProfileDatedTrip> patternTrips = trips.tripsByPattern(pattern);
					if (patternTrips.isEmpty()) continue;
					int boardingLine = timetable.lineIndex(patternTrips.getFirst().scheduledTrip().lineId(position));
					if (boardingLine < 0) continue;
					int transition = label.boardings() == 0
						? timetable.entryTransition(label.station(), boardingLine, input.accessProfileBit(), false,
							input.requiresVerifiedJourneyDistance())
						: timetable.transferTransition(label.station(), label.incomingLine(), boardingLine,
							input.accessProfileBit(), false, input.requiresVerifiedJourneyDistance());
					if (transition < 0) {
						limits.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
						continue;
					}
					if (label.boardings() > 0) expandedTransfers += 1;
					JourneyAccessKind kind = label.boardings() == 0 ? JourneyAccessKind.ENTRY : JourneyAccessKind.TRANSFER;
					int accessSeconds = journeyAccessSeconds(input, kind,
						timetable.transitionDurationSeconds(transition), timetable.transitionDistanceMeters(transition));
					int earliestDeparture = Math.addExact(Math.addExact(label.arrivalSeconds(), accessSeconds),
						input.boardingSlackSeconds());
					for (ProfileDatedTrip trip : patternTrips) {
						limits.consumeWork();
						if (!trip.allowsPickup(position) || trip.cancelled()
							|| trip.departureSeconds(position) < earliestDeparture) continue;
						expandedTrips += 1;
						long transferSlack = (long) trip.departureSeconds(position)
							- label.arrivalSeconds() - accessSeconds - input.boardingSlackSeconds();
						if (transferSlack < 0) continue;
						JourneyProfileRaptorPort.ConnectionSlack connectionSlack = label.boardings() == 0
							? new JourneyProfileRaptorPort.NoTransfer()
							: minimumSlack(label.connectionSlack(), transferSlack);
						byte warnings = (byte) (label.warningBits()
							| timetable.transitionWarningCodes(transition, input.accessProfileBit(), false));
						for (int alight = position + 1; alight < trip.stopTimes().size(); alight += 1) {
							limits.consumeWork();
							if (!trip.allowsDropOff(alight)) continue;
							admit(new ProfileLabel(
								label.startSeconds(), trip.arrivalSeconds(alight), label.boardings() + 1,
								timetable.stopsByPattern(pattern)[alight], boardingLine, warnings,
								Math.addExact(label.verifiedAccessSeconds(), accessSeconds),
								Math.addExact(label.verifiedAccessDistanceMeters(), timetable.transitionDistanceMeters(transition)),
								Math.addExact(label.stairBurden(), timetable.transitionIncludesStairs(transition) ? 1L : 0L),
								connectionSlack,
								new ProfileRideTrace(label, trip, position, alight, transition)));
						}
					}
				}
			}
		}

		private boolean isCurrent(ProfileLabel label) {
			ProfileStateKey state = new ProfileStateKey(label.boardings(), label.station(), label.incomingLine());
			return labelsByState.getOrDefault(state, List.of()).stream().anyMatch(existing -> existing == label);
		}

		private List<JourneyItinerary> destinationItineraries(
			ScanInput pointInput,
			int destination,
			int accessProfileBit
		) {
			List<ProfileDestinationLabel> candidates = new ArrayList<>();
			for (Map.Entry<ProfileStateKey, List<ProfileLabel>> entry : labelsByState.entrySet()) {
				ProfileStateKey state = entry.getKey();
				if (state.station() != destination || state.boardings() == 0) continue;
				int exit = timetable.exitTransition(destination, state.incomingLine(), accessProfileBit, false,
					pointInput.requiresVerifiedJourneyDistance());
				if (exit < 0) {
					limits.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
					continue;
				}
				int exitSeconds = journeyAccessSeconds(pointInput, JourneyAccessKind.EXIT,
					timetable.transitionDurationSeconds(exit), timetable.transitionDistanceMeters(exit));
				byte exitWarnings = timetable.transitionWarningCodes(exit, accessProfileBit, false);
				for (ProfileLabel label : entry.getValue()) {
					limits.consumeWork();
					candidates.add(new ProfileDestinationLabel(label, exit,
						Math.addExact(label.arrivalSeconds(), exitSeconds),
						Math.addExact(label.verifiedAccessSeconds(), exitSeconds),
						Math.addExact(label.verifiedAccessDistanceMeters(), timetable.transitionDistanceMeters(exit)),
						Math.addExact(label.stairBurden(), timetable.transitionIncludesStairs(exit) ? 1L : 0L),
						(byte) (label.warningBits() | exitWarnings)));
				}
			}
			List<ProfileDestinationLabel> frontier = destinationFrontier(candidates);
			limits.observeDestinationLabels(frontier.size());
			if (frontier.size() > limits.maxDestinationProfileLabels()) {
				limits.count("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
				throw new ProfilePlanningLimitException(ProfilePlanningLimit.MAX_DESTINATION_PROFILE_LABELS,
					frontier.size(), limits.maxDestinationProfileLabels());
			}
			return frontier.stream()
				.map(candidate -> toJourneyItinerary(pointInput, timetable,
					candidate.toScalarLabel(pointInput.readyAtSeconds())))
				.sorted(Comparator.comparing(JourneyItinerary::plannedArrivalTime)
					.thenComparing(JourneyItinerary::plannedDepartureTime))
				.toList();
		}

		private ScanMetrics scanMetrics() {
			return new ScanMetrics(expandedRoutes, expandedTrips, expandedTransfers);
		}

		private boolean admit(ProfileLabel candidate) {
			limits.consumeWork();
			ProfileStateKey state = new ProfileStateKey(
				candidate.boardings(), candidate.station(), candidate.incomingLine());
			List<ProfileLabel> labels = labelsByState.computeIfAbsent(state, ignored -> new ArrayList<>());
			for (ProfileLabel existing : labels) {
				if (dominates(existing, candidate)) {
					limits.count("FORWARD_STATE_DOMINANCE_V1");
					return false;
				}
				if (sameVector(existing, candidate) && compareTrace(existing, candidate) <= 0) {
					limits.count("FORWARD_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1");
					return false;
				}
			}
			labels.removeIf(existing -> {
				if (dominates(candidate, existing)) {
					limits.count("FORWARD_STATE_DOMINANCE_V1");
					return true;
				}
				if (sameVector(existing, candidate) && compareTrace(candidate, existing) < 0) {
					limits.count("FORWARD_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1");
					return true;
				}
				return false;
			});
			labels.add(candidate);
			labels.sort(ProfileMultiLabelForwardScan::compareTrace);
			limits.observeStateLabels(labels.size());
			if (labels.size() > limits.maxLabelsPerState()) {
				limits.count("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
				throw new ProfilePlanningLimitException(ProfilePlanningLimit.MAX_LABELS_PER_STATE,
					labels.size(), limits.maxLabelsPerState());
			}
			pending.addLast(candidate);
			return true;
		}

		private static JourneyProfileRaptorPort.ConnectionSlack minimumSlack(
			JourneyProfileRaptorPort.ConnectionSlack existing,
			long candidate
		) {
			if (!(existing instanceof JourneyProfileRaptorPort.MinimumTransferSeconds minimum)) {
				return new JourneyProfileRaptorPort.MinimumTransferSeconds(candidate);
			}
			return new JourneyProfileRaptorPort.MinimumTransferSeconds(Math.min(minimum.seconds(), candidate));
		}

		private List<ProfileDestinationLabel> destinationFrontier(List<ProfileDestinationLabel> labels) {
			List<ProfileDestinationLabel> frontier = new ArrayList<>();
			for (ProfileDestinationLabel candidate : labels) {
				boolean dominated = labels.stream().anyMatch(other -> other != candidate
					&& destinationDominates(other, candidate));
				if (!dominated) frontier.add(candidate);
				else limits.count("FORWARD_DESTINATION_DOMINANCE_V1");
			}
			frontier.sort(Comparator.comparing(ProfileDestinationLabel::canonicalTrace));
			return List.copyOf(frontier);
		}

		private static boolean dominates(ProfileLabel left, ProfileLabel right) {
			return left.startSeconds() >= right.startSeconds()
				&& left.arrivalSeconds() <= right.arrivalSeconds()
				&& left.verifiedAccessSeconds() <= right.verifiedAccessSeconds()
				&& left.verifiedAccessDistanceMeters() <= right.verifiedAccessDistanceMeters()
				&& left.stairBurden() <= right.stairBurden()
				&& JourneyProfileRaptorPort.ConnectionSlack.compareSafety(left.connectionSlack(), right.connectionSlack()) >= 0
				&& (left.warningBits() & right.warningBits()) == left.warningBits()
				&& (left.startSeconds() > right.startSeconds()
					|| left.arrivalSeconds() < right.arrivalSeconds()
					|| left.verifiedAccessSeconds() < right.verifiedAccessSeconds()
					|| left.verifiedAccessDistanceMeters() < right.verifiedAccessDistanceMeters()
					|| left.stairBurden() < right.stairBurden()
					|| JourneyProfileRaptorPort.ConnectionSlack.compareSafety(left.connectionSlack(), right.connectionSlack()) > 0
					|| left.warningBits() != right.warningBits());
		}

		private static boolean destinationDominates(ProfileDestinationLabel left, ProfileDestinationLabel right) {
			ProfileLabel leftLabel = left.label();
			ProfileLabel rightLabel = right.label();
			// 한 profile point의 준비 시각은 같다. 이전 iteration의 시작 시각은 state 재사용에만 쓴다.
			return left.arrivalSeconds() <= right.arrivalSeconds()
				&& leftLabel.boardings() <= rightLabel.boardings()
				&& left.accessSeconds() <= right.accessSeconds()
				&& left.accessDistanceMeters() <= right.accessDistanceMeters()
				&& left.stairBurden() <= right.stairBurden()
				&& JourneyProfileRaptorPort.ConnectionSlack.compareSafety(leftLabel.connectionSlack(), rightLabel.connectionSlack()) >= 0
				&& (left.warningBits() & right.warningBits()) == left.warningBits()
				&& (left.arrivalSeconds() < right.arrivalSeconds()
					|| leftLabel.boardings() < rightLabel.boardings()
					|| left.accessSeconds() < right.accessSeconds()
					|| left.accessDistanceMeters() < right.accessDistanceMeters()
					|| left.stairBurden() < right.stairBurden()
					|| JourneyProfileRaptorPort.ConnectionSlack.compareSafety(leftLabel.connectionSlack(), rightLabel.connectionSlack()) > 0
					|| left.warningBits() != right.warningBits());
		}

		private static boolean sameVector(ProfileLabel left, ProfileLabel right) {
			return left.startSeconds() == right.startSeconds()
				&& left.arrivalSeconds() == right.arrivalSeconds()
				&& left.verifiedAccessSeconds() == right.verifiedAccessSeconds()
				&& left.verifiedAccessDistanceMeters() == right.verifiedAccessDistanceMeters()
				&& left.stairBurden() == right.stairBurden()
				&& left.warningBits() == right.warningBits()
				&& JourneyProfileRaptorPort.ConnectionSlack.compareSafety(left.connectionSlack(), right.connectionSlack()) == 0;
		}

		private static int compareTrace(ProfileLabel left, ProfileLabel right) {
			return traceKey(left.trace()).compareTo(traceKey(right.trace()));
		}

		private static String traceKey(ProfileRideTrace trace) {
			if (trace == null) return "";
			return traceKey(trace.parent().trace()) + '/' + trace.trip().nativeServiceDate() + ':'
				+ trace.trip().scheduledTrip().index() + ':' + trace.boardStop()
				+ ':' + trace.alightStop() + ':' + trace.accessTransition();
		}
	}

	private record ProfileStateKey(int boardings, int station, int incomingLine) {
	}

	private record ProfileLabel(
		int startSeconds,
		int arrivalSeconds,
		int boardings,
		int station,
		int incomingLine,
		byte warningBits,
		long verifiedAccessSeconds,
		long verifiedAccessDistanceMeters,
		long stairBurden,
		JourneyProfileRaptorPort.ConnectionSlack connectionSlack,
		ProfileRideTrace trace
	) {
	}

	private record ProfileRideTrace(
		ProfileLabel parent,
		ProfileDatedTrip trip,
		int boardStop,
		int alightStop,
		int accessTransition
	) {
	}

	private record ProfileDestinationLabel(
		ProfileLabel label,
		int exitTransition,
		int arrivalSeconds,
		long accessSeconds,
		long accessDistanceMeters,
		long stairBurden,
		byte warningBits
	) {
		private String canonicalTrace() {
			return ProfileMultiLabelForwardScan.traceKey(label.trace());
		}

		private Label toScalarLabel(int readyAtSeconds) {
			List<RideLeg> reversePath = new ArrayList<>();
			int[] transitions = new int[label.boardings()];
			ProfileRideTrace current = label.trace();
			for (int index = label.boardings() - 1; index >= 0; index -= 1) {
				if (current == null) throw new IllegalStateException("profile label trace is incomplete");
				reversePath.add(new RideLeg(
					current.trip().scheduledTrip(), current.boardStop(), current.alightStop(),
					current.trip().realtimeOverlay(), current.trip().nativeServiceDate()));
				transitions[index] = current.accessTransition();
				current = current.parent().trace();
			}
			java.util.Collections.reverse(reversePath);
			return new Label("", arrivalSeconds, readyAtSeconds, label.boardings(), List.copyOf(reversePath),
				transitions, exitTransition, warningBits);
		}
	}

	private record OptionalIntValue(boolean present, int value) {
		private static OptionalIntValue empty() {
			return new OptionalIntValue(false, 0);
		}

		private static OptionalIntValue of(int value) {
			return new OptionalIntValue(true, value);
		}
	}

	record ReadyBoarding(
		int readySlot,
		int accessTransition,
		int earliestDepartureSeconds,
		byte warningBits
	) {
	}

	record JourneyPlan(List<JourneyItinerary> itineraries, ScanMetrics scanMetrics,
		JourneyRequestMeasurement.RouteObservation measurementObservation) {
		JourneyPlan {
			itineraries = List.copyOf(itineraries);
			scanMetrics = Objects.requireNonNull(scanMetrics, "scanMetrics");
		}
	}

	record JourneyItinerary(
		LocalDate serviceDate,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime,
		JourneyProfileRaptorPort.ItineraryMetrics metrics,
		List<JourneyLegProjection> legs
	) {
		JourneyItinerary {
			metrics = Objects.requireNonNull(metrics, "metrics");
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
		String verificationStatus,
		String transferType,
		Boolean farePenaltyApplies,
		Integer additionalFareWon,
		Integer transferLimitMinutes
	) implements JourneyLegProjection {
		JourneyAccessProjection(
			JourneyAccessKind kind,
			String fromStationId,
			String toStationId,
			int durationSeconds,
			int distanceMeters,
			boolean includesStairs,
			boolean verified,
			String verificationStatus
		) {
			this(kind, fromStationId, toStationId, durationSeconds, distanceMeters, includesStairs, verified, verificationStatus, null, null, null, null);
		}
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
			null, false, new int[0], new int[0], new int[0], new boolean[0], new RealtimeEvidence[0], new int[0], new BitSet(0));
		private final String version;
		private final boolean available;
		private final int[] tripIndexes;
		private final int[] arrivalDeltas;
		private final int[] departureDeltas;
		private final boolean[] cancelled;
		private final RealtimeEvidence[] evidence;
		private final int[] affectedPatterns;
		private final BitSet blockedTransitions;

		private RealtimeOverlay(
			String version,
			boolean available,
			int[] tripIndexes,
			int[] arrivalDeltas,
			int[] departureDeltas,
			boolean[] cancelled,
			RealtimeEvidence[] evidence,
			int[] affectedPatterns,
			BitSet blockedTransitions
		) {
			this.version = version;
			this.available = available;
			this.tripIndexes = tripIndexes;
			this.arrivalDeltas = arrivalDeltas;
			this.departureDeltas = departureDeltas;
			this.cancelled = cancelled;
			this.evidence = evidence;
			this.affectedPatterns = affectedPatterns;
			this.blockedTransitions = (BitSet) Objects.requireNonNull(blockedTransitions, "blockedTransitions").clone();
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
			return tripIndexes.length == 0 && blockedTransitions.isEmpty();
		}

		boolean isTransitionBlocked(int transition) {
			return transition >= 0 && blockedTransitions.get(transition);
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

	record Label(
		String stationId,
		int timeSeconds,
		int startSeconds,
		int boardings,
		List<RideLeg> path,
		int[] accessTransitions,
		int exitTransition,
		byte warningBits,
		int penaltySeconds
	) {
		Label {
			accessTransitions = accessTransitions == null ? new int[0] : accessTransitions.clone();
		}

		Label(
			String stationId,
			int timeSeconds,
			int startSeconds,
			int boardings,
			List<RideLeg> path,
			int[] accessTransitions,
			int exitTransition,
			byte warningBits
		) {
			this(stationId, timeSeconds, startSeconds, boardings, path, accessTransitions, exitTransition, warningBits, 0);
		}

		int virtualCostSeconds() {
			return timeSeconds + penaltySeconds;
		}

		@Override
		public int[] accessTransitions() {
			return accessTransitions.clone();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof Label other)) return false;
			return timeSeconds == other.timeSeconds
				&& startSeconds == other.startSeconds
				&& boardings == other.boardings
				&& exitTransition == other.exitTransition
				&& warningBits == other.warningBits
				&& penaltySeconds == other.penaltySeconds
				&& Objects.equals(stationId, other.stationId)
				&& Objects.equals(path, other.path)
				&& Arrays.equals(accessTransitions, other.accessTransitions);
		}

		@Override
		public int hashCode() {
			int result = Objects.hash(stationId, timeSeconds, startSeconds, boardings, path, exitTransition, warningBits, penaltySeconds);
			result = 31 * result + Arrays.hashCode(accessTransitions);
			return result;
		}

		@Override
		public String toString() {
			return "Label[stationId=" + stationId
				+ ", timeSeconds=" + timeSeconds
				+ ", startSeconds=" + startSeconds
				+ ", boardings=" + boardings
				+ ", path=" + path
				+ ", accessTransitions=" + Arrays.toString(accessTransitions)
				+ ", exitTransition=" + exitTransition
				+ ", warningBits=" + warningBits
				+ ", penaltySeconds=" + penaltySeconds + "]";
		}
	}

	static int getTransferLimitSeconds(int alightSeconds, int boardSeconds) {
		int alightOfDay = Math.floorMod(alightSeconds, 86400);
		int boardOfDay = Math.floorMod(boardSeconds, 86400);
		boolean isNight = (alightOfDay >= 21 * 3600 || alightOfDay < 7 * 3600)
			|| (boardOfDay >= 21 * 3600 || boardOfDay < 7 * 3600);
		return isNight ? 3600 : 1800;
	}

	static int calculateJourneyPenalty(int elapsedSeconds, int limitSeconds) {
		if (elapsedSeconds > limitSeconds) {
			return 600;
		}
		if (limitSeconds == 1800 && elapsedSeconds > 18 * 60) {
			return 300;
		}
		return 0;
	}

	static record ScheduledTrip(
		int index,
		TransitTrip trip,
		TransitRoute route,
		List<TransitStopTime> stopTimes,
		PrimitiveTripTimes times
	) {
		private ScheduledTrip withIndex(int denseIndex) {
			return new ScheduledTrip(denseIndex, trip, route, stopTimes, times);
		}

		int arrivalSeconds(int stopIndex) {
			return times.arrivalSeconds(stopIndex);
		}

		int departureSeconds(int stopIndex) {
			return times.departureSeconds(stopIndex);
		}

		boolean allowsPickup(int stopIndex) {
			return times.allowsPickup(stopIndex);
		}

		boolean allowsDropOff(int stopIndex) {
			return times.allowsDropOff(stopIndex);
		}
		String lineId(int stopIndex) {
			return stopTimes.get(stopIndex).lineId();
		}
	}

	private record BoardingStop(ScheduledTrip trip, int stopIndex, TransitStopTime stopTime) {
	}

	static record DepartureEvent(ScheduledTrip scheduledTrip, int stopIndex, int effectiveDepartureSeconds) {
		DepartureEvent {
			Objects.requireNonNull(scheduledTrip, "scheduledTrip must not be null");
			if (stopIndex < 0 || stopIndex >= scheduledTrip.stopTimes().size()) {
				throw new IllegalArgumentException("stopIndex must address scheduledTrip");
			}
		}
	}


	private record RideLeg(
		ScheduledTrip scheduledTrip,
		int fromIndex,
		int toIndex,
		RealtimeOverlay realtimeOverlay,
		LocalDate nativeServiceDate
	) {
		private RideLeg(
			ScheduledTrip scheduledTrip,
			int fromIndex,
			int toIndex,
			RealtimeOverlay realtimeOverlay
		) {
			this(scheduledTrip, fromIndex, toIndex, realtimeOverlay, null);
		}

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

		Instant plannedDepartureTime(ServiceDay readinessAnchor) {
			return serviceInstant(serviceDay(readinessAnchor), scheduledTrip.departureSeconds(fromIndex));
		}

		Instant plannedArrivalTime(ServiceDay readinessAnchor) {
			return serviceInstant(serviceDay(readinessAnchor), scheduledTrip.arrivalSeconds(toIndex));
		}

		Instant realtimeDepartureTime(ServiceDay readinessAnchor) {
			return serviceInstant(serviceDay(readinessAnchor), departureSeconds());
		}

		Instant realtimeArrivalTime(ServiceDay readinessAnchor) {
			return serviceInstant(serviceDay(readinessAnchor), arrivalSeconds());
		}

		private ServiceDay serviceDay(ServiceDay readinessAnchor) {
			return nativeServiceDate == null ? readinessAnchor : new ServiceDay(nativeServiceDate, 0);
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

/** Request-local observations only; dominance counts include both rejected and evicted labels. */
final class JourneyProfilePruningObservationAccumulator {
	private final String requestId;
	private final JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithmIdentity;
	private final Map<String, Long> counts = new LinkedHashMap<>();
	private long workConsumed;
	private long peakStateLabels;
	private long peakDestinationLabels;
	private long reservedProfileBreakpoints;

	JourneyProfilePruningObservationAccumulator(
		String requestId,
		JourneyRaptorPruningInventoryV1.AlgorithmSemanticIdentity algorithmIdentity
	) {
		this.requestId = Objects.requireNonNull(requestId, "requestId");
		this.algorithmIdentity = Objects.requireNonNull(algorithmIdentity, "algorithmIdentity");
		JourneyRaptorPruningInventoryV1.activeRuleIds(algorithmIdentity).forEach(rule -> counts.put(rule, 0L));
	}

	void increment(String ruleId) {
		if (!counts.containsKey(ruleId)) throw new IllegalArgumentException("inactive pruning rule: " + ruleId);
		counts.compute(ruleId, (ignored, count) -> Math.addExact(count, 1L));
	}

	void consumeWork() {
		workConsumed = Math.addExact(workConsumed, 1L);
	}

	void observeStateLabels(int labels) {
		peakStateLabels = Math.max(peakStateLabels, labels);
	}

	void observeDestinationLabels(int labels) {
		peakDestinationLabels = Math.max(peakDestinationLabels, labels);
	}

	void reserveProfileBreakpoints(int additional) {
		reservedProfileBreakpoints = Math.addExact(reservedProfileBreakpoints, additional);
	}

	JourneyRaptorPruningInventoryV1.CountSnapshot snapshot() {
		return new JourneyRaptorPruningInventoryV1.CountSnapshot(requestId, algorithmIdentity, counts);
	}

	JourneyProfileRaptorPort.PlanningMetrics planningMetrics() {
		return new JourneyProfileRaptorPort.PlanningMetrics(
			workConsumed, peakStateLabels, peakDestinationLabels, reservedProfileBreakpoints);
	}
}
