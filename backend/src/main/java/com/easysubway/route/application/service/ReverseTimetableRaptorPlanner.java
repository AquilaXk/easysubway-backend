package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Reverse, service-day-local primitive for arrive-by and O/D last-connection queries.
 *
 * <p>The primitive deliberately consumes the already selected active service day and realtime snapshot.
 * It never creates an access edge: ENTRY, TRANSFER, and EXIT are looked up in their original forward
 * direction and must be verified before they can participate in a reverse search.</p>
 */
final class ReverseTimetableRaptorPlanner {

	LastConnectionResult lastConnection(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		Objects.requireNonNull(activeServiceDay, "activeServiceDay must not be null");
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		if (query.cancelled().getAsBoolean()) {
			return LastConnectionResult.cancelled();
		}
		ReverseLimitTracker limitTracker = new ReverseLimitTracker(limits);
		List<RouteTimetableRaptorPlanner.ScheduledTrip> activeTrips = activeTrips(timetable, activeServiceDay);
		if (activeTrips.isEmpty()) {
			return new LastConnectionResult(Result.of(Outcome.NO_ACTIVE_SERVICE), null);
		}

		Integer terminalDeadline = terminalDeadline(query, timetable, activeTrips, realtimeOverlay, limitTracker);
		if (query.cancelled().getAsBoolean()) {
			return LastConnectionResult.cancelled();
		}
		if (terminalDeadline == null) {
			return new LastConnectionResult(hasVerifiedExit(query, timetable, activeTrips, limitTracker)
				? Result.of(Outcome.NO_OD_CONNECTION)
				: Result.of(Outcome.NO_VERIFIED_EXIT), null);
		}
		Result result = arriveBy(new Query(
			query.originStationId(), query.destinationStationId(), query.serviceDate(), 0, terminalDeadline,
			query.maxTransfers(), query.accessProfileBit(), query.boardingSlackSeconds(), query.mobilityPreset(),
			query.walkingSpeedMetersPerHour(), query.requiresVerifiedJourneyDistance(), query.cancelled()),
			timetable, activeServiceDay, realtimeOverlay, limitTracker);
		if (result.outcome() == Outcome.CANCELLED) {
			return LastConnectionResult.cancelled();
		}
		return new LastConnectionResult(result.outcome() == Outcome.DEADLINE_MISS
			? Result.of(Outcome.NO_OD_CONNECTION) : result, terminalDeadline);
	}

	Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		Objects.requireNonNull(activeServiceDay, "activeServiceDay must not be null");
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		return arriveBy(query, timetable, activeServiceDay, realtimeOverlay, new ReverseLimitTracker(limits));
	}

	private Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		ReverseLimitTracker limitTracker
	) {
		if (query.cancelled().getAsBoolean()) {
			return Result.cancelled();
		}
		List<RouteTimetableRaptorPlanner.ScheduledTrip> activeTrips = activeTrips(timetable, activeServiceDay);
		if (activeTrips.isEmpty()) {
			return Result.of(Outcome.NO_ACTIVE_SERVICE);
		}
		int origin = timetable.stationIndex(query.originStationId());
		int destination = timetable.stationIndex(query.destinationStationId());
		if (origin < 0 || destination < 0) {
			return Result.of(Outcome.NO_OD_CONNECTION);
		}

		boolean verifiedExitExists = false;
		boolean exitCanMeetDeadline = false;
		List<Candidate> candidates = new ArrayList<>();
		for (RouteTimetableRaptorPlanner.ScheduledTrip trip : activeTrips) {
			limitTracker.consumeWork();
			if (query.cancelled().getAsBoolean()) {
				return Result.cancelled();
			}
			for (int alightIndex = 1; alightIndex < trip.stopTimes().size(); alightIndex += 1) {
				limitTracker.consumeWork();
				if (!query.destinationStationId().equals(trip.stopTimes().get(alightIndex).stationId())
					|| !trip.allowsDropOff(alightIndex)) {
					continue;
				}
				int line = timetable.lineIndex(trip.lineId(alightIndex));
				int exit = line < 0 ? -1 : timetable.exitTransition(
					destination, line, query.accessProfileBit(), false, query.requiresVerifiedJourneyDistance());
				if (!verifiedTransition(timetable, exit)) {
					continue;
				}
				verifiedExitExists = true;
				int destinationArrival = Math.addExact(
					realtimeOverlay.arrivalSeconds(trip, alightIndex), accessSeconds(query, timetable, exit, Access.EXIT));
				if (destinationArrival > query.arrivalDeadlineSeconds()) {
					continue;
				}
				exitCanMeetDeadline = true;
				if (realtimeOverlay.cancelled(trip)) {
					continue;
				}
				for (int boardIndex = 0; boardIndex < alightIndex; boardIndex += 1) {
					limitTracker.consumeWork();
					if (!trip.allowsPickup(boardIndex)) {
						continue;
					}
					List<Candidate> traced = traceToOrigin(
						query, timetable, activeTrips, realtimeOverlay, trip, boardIndex, alightIndex,
						destinationArrival, 0, new HashSet<>(), limitTracker);
					for (Candidate candidate : traced) {
						candidates.add(candidate.appendAccess(new TraceAccess(
							Access.EXIT, exit, query.destinationStationId(), query.destinationStationId()),
							accessSeconds(query, timetable, exit, Access.EXIT), timetable.transitionDistanceMeters(exit),
							timetable.transitionIncludesStairs(exit)));
					}
				}
			}
		}
		List<Candidate> frontier = destinationFrontier(candidates);
		if (!frontier.isEmpty()) {
			if (frontier.size() > limitTracker.maxDestinationProfileLabels()) {
				throw new ReversePlanningLimitException(PlanningLimit.MAX_DESTINATION_PROFILE_LABELS,
					frontier.size(), limitTracker.maxDestinationProfileLabels());
			}
			return Result.found(frontier, query, timetable, realtimeOverlay);
		}
		if (!verifiedExitExists) {
			return Result.of(Outcome.NO_VERIFIED_EXIT);
		}
		return Result.of(exitCanMeetDeadline ? Outcome.NO_OD_CONNECTION : Outcome.DEADLINE_MISS);
	}

	private List<Candidate> traceToOrigin(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<RouteTimetableRaptorPlanner.ScheduledTrip> activeTrips,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		RouteTimetableRaptorPlanner.ScheduledTrip downstreamTrip,
		int downstreamBoardIndex,
		int downstreamAlightIndex,
		int arrivalAtDestinationSeconds,
		int transfersUsed,
		Set<TraceState> visiting,
		ReverseLimitTracker limits
	) {
		if (query.cancelled().getAsBoolean() || realtimeOverlay.cancelled(downstreamTrip)) {
			return List.of();
		}
		TraceState state = new TraceState(downstreamTrip.index(), downstreamBoardIndex, downstreamAlightIndex, transfersUsed);
		if (!visiting.add(state)) {
			return List.of();
		}
		try {
			String boardStation = downstreamTrip.stopTimes().get(downstreamBoardIndex).stationId();
			int downstreamLine = timetable.lineIndex(downstreamTrip.lineId(downstreamBoardIndex));
			int downstreamDeparture = realtimeOverlay.departureSeconds(downstreamTrip, downstreamBoardIndex);
			if (query.originStationId().equals(boardStation)) {
				int origin = timetable.stationIndex(boardStation);
				int entry = origin < 0 || downstreamLine < 0 ? -1 : timetable.entryTransition(
					origin, downstreamLine, query.accessProfileBit(), false, query.requiresVerifiedJourneyDistance());
				if (!verifiedTransition(timetable, entry)) {
					return List.of();
				}
				int readyAt = downstreamDeparture - accessSeconds(query, timetable, entry, Access.ENTRY)
					- query.boardingSlackSeconds();
				if (readyAt < query.earliestReadyAtSeconds()) return List.of();
				Candidate candidate = new Candidate(readyAt, arrivalAtDestinationSeconds, transfersUsed,
					accessSeconds(query, timetable, entry, Access.ENTRY), timetable.transitionDistanceMeters(entry),
					timetable.transitionIncludesStairs(entry) ? 1L : 0L,
					new JourneyProfileRaptorPort.NoTransfer(), List.of(
						new TraceAccess(Access.ENTRY, entry, query.originStationId(), boardStation),
						new TraceRide(downstreamTrip, downstreamBoardIndex, downstreamAlightIndex)));
				return limits.admit(candidate);
			}
			if (transfersUsed >= query.maxTransfers()) {
				return List.of();
			}

			List<Candidate> candidates = new ArrayList<>();
			for (RouteTimetableRaptorPlanner.ScheduledTrip upstreamTrip : activeTrips) {
				limits.consumeWork();
				if (query.cancelled().getAsBoolean() || realtimeOverlay.cancelled(upstreamTrip)) {
					continue;
				}
				for (int upstreamAlightIndex = 1; upstreamAlightIndex < upstreamTrip.stopTimes().size(); upstreamAlightIndex += 1) {
					limits.consumeWork();
					if (!boardStation.equals(upstreamTrip.stopTimes().get(upstreamAlightIndex).stationId())
						|| !upstreamTrip.allowsDropOff(upstreamAlightIndex)) {
						continue;
					}
					int station = timetable.stationIndex(boardStation);
					int upstreamLine = timetable.lineIndex(upstreamTrip.lineId(upstreamAlightIndex));
					int transfer = station < 0 || upstreamLine < 0 || downstreamLine < 0 ? -1
						: timetable.transferTransition(station, upstreamLine, downstreamLine, query.accessProfileBit(), false,
							query.requiresVerifiedJourneyDistance());
					if (!verifiedTransition(timetable, transfer)) {
						continue;
					}
					int latestArrival = downstreamDeparture - accessSeconds(query, timetable, transfer, Access.TRANSFER)
						- query.boardingSlackSeconds();
					if (realtimeOverlay.arrivalSeconds(upstreamTrip, upstreamAlightIndex) > latestArrival) {
						continue;
					}
					for (int upstreamBoardIndex = 0; upstreamBoardIndex < upstreamAlightIndex; upstreamBoardIndex += 1) {
						limits.consumeWork();
						if (!upstreamTrip.allowsPickup(upstreamBoardIndex)) {
							continue;
						}
						List<Candidate> upstream = traceToOrigin(
							query, timetable, activeTrips, realtimeOverlay, upstreamTrip, upstreamBoardIndex, upstreamAlightIndex,
							arrivalAtDestinationSeconds, transfersUsed + 1, visiting, limits);
						for (Candidate candidate : upstream) {
							long transferSlack = (long) downstreamDeparture
								- realtimeOverlay.arrivalSeconds(upstreamTrip, upstreamAlightIndex)
								- accessSeconds(query, timetable, transfer, Access.TRANSFER)
								- query.boardingSlackSeconds();
							candidates.add(candidate.appendTransferAndRide(
								new TraceAccess(Access.TRANSFER, transfer, boardStation, boardStation),
								accessSeconds(query, timetable, transfer, Access.TRANSFER),
								timetable.transitionDistanceMeters(transfer), timetable.transitionIncludesStairs(transfer),
								transferSlack, new TraceRide(downstreamTrip, downstreamBoardIndex, downstreamAlightIndex)));
						}
					}
				}
			}
			return limits.admitAll(candidates);
		} finally {
			visiting.remove(state);
		}
	}

	private static Integer terminalDeadline(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<RouteTimetableRaptorPlanner.ScheduledTrip> activeTrips,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		ReverseLimitTracker limits
	) {
		Integer latest = null;
		int destination = timetable.stationIndex(query.destinationStationId());
		for (RouteTimetableRaptorPlanner.ScheduledTrip trip : activeTrips) {
			limits.consumeWork();
			if (query.cancelled().getAsBoolean()) {
				return null;
			}
			if (realtimeOverlay.cancelled(trip)) {
				continue;
			}
			for (int alightIndex = 1; alightIndex < trip.stopTimes().size(); alightIndex += 1) {
				limits.consumeWork();
				if (!query.destinationStationId().equals(trip.stopTimes().get(alightIndex).stationId())
					|| !trip.allowsDropOff(alightIndex)) {
					continue;
				}
				int line = timetable.lineIndex(trip.lineId(alightIndex));
				int exit = destination < 0 || line < 0 ? -1 : timetable.exitTransition(
					destination, line, query.accessProfileBit(), false, query.requiresVerifiedJourneyDistance());
				if (!verifiedTransition(timetable, exit)) {
					continue;
				}
				int arrivalAtDestination = Math.addExact(
					realtimeOverlay.arrivalSeconds(trip, alightIndex), accessSeconds(query, timetable, exit, Access.EXIT));
				latest = latest == null || arrivalAtDestination > latest ? arrivalAtDestination : latest;
			}
		}
		return latest;
	}

	private static boolean hasVerifiedExit(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<RouteTimetableRaptorPlanner.ScheduledTrip> activeTrips,
		ReverseLimitTracker limits
	) {
		int destination = timetable.stationIndex(query.destinationStationId());
		for (RouteTimetableRaptorPlanner.ScheduledTrip trip : activeTrips) {
			limits.consumeWork();
			for (int alightIndex = 1; alightIndex < trip.stopTimes().size(); alightIndex += 1) {
				limits.consumeWork();
				if (!query.destinationStationId().equals(trip.stopTimes().get(alightIndex).stationId())
					|| !trip.allowsDropOff(alightIndex)) {
					continue;
				}
				int line = timetable.lineIndex(trip.lineId(alightIndex));
				int exit = destination < 0 || line < 0 ? -1 : timetable.exitTransition(
					destination, line, query.accessProfileBit(), false, query.requiresVerifiedJourneyDistance());
				if (verifiedTransition(timetable, exit)) {
					return true;
				}
			}
		}
		return false;
	}

	private static List<RouteTimetableRaptorPlanner.ScheduledTrip> activeTrips(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay
	) {
		List<RouteTimetableRaptorPlanner.ScheduledTrip> trips = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		for (int pattern = 0; pattern < timetable.routePatternCount(); pattern += 1) {
			for (RouteTimetableRaptorPlanner.ScheduledTrip trip : activeServiceDay.tripsByPattern(pattern)) {
				if (seen.add(trip.index())) {
					trips.add(trip);
				}
			}
		}
		trips.sort(Comparator.comparingInt((RouteTimetableRaptorPlanner.ScheduledTrip trip) -> trip.departureSeconds(0))
			.reversed().thenComparingInt(RouteTimetableRaptorPlanner.ScheduledTrip::index));
		return List.copyOf(trips);
	}

	private static boolean verifiedTransition(RouteTimetableRaptorPlanner.CompiledTimetable timetable, int transition) {
		return transition >= 0 && timetable.transitionVerified(transition);
	}

	private static int accessSeconds(
		Query query, RouteTimetableRaptorPlanner.CompiledTimetable timetable, int transition, Access access
	) {
		return accessSeconds(query.accessProfileBit(), query.mobilityPreset(), query.walkingSpeedMetersPerHour(),
			query.requiresVerifiedJourneyDistance(), timetable, transition, access);
	}

	private static int accessSeconds(
		LastConnectionQuery query, RouteTimetableRaptorPlanner.CompiledTimetable timetable, int transition, Access access
	) {
		return accessSeconds(query.accessProfileBit(), query.mobilityPreset(), query.walkingSpeedMetersPerHour(),
			query.requiresVerifiedJourneyDistance(), timetable, transition, access);
	}

	private static int accessSeconds(
		int accessProfileBit,
		MobilityPreset mobilityPreset,
		int walkingSpeedMetersPerHour,
		boolean requiresVerifiedJourneyDistance,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		int transition,
		Access access
	) {
		int baseline = timetable.transitionDurationSeconds(transition);
		if (access == Access.TRANSFER && requiresVerifiedJourneyDistance) {
			return ProfileWalkTimeCalculator.journeySeconds(timetable.transitionDistanceMeters(transition),
				walkingSpeedMetersPerHour, mobilityPreset, false);
		}
		return ProfileWalkTimeCalculator.estimateSeconds(
			baseline, mobilityPreset, WalkTimeSource.OFFICIAL_BASELINE, false).seconds();
	}


	private static List<Candidate> destinationFrontier(List<Candidate> candidates) {
		List<Candidate> frontier = new ArrayList<>();
		for (Candidate candidate : candidates) {
			boolean dominated = candidates.stream().anyMatch(other -> other != candidate && dominates(other, candidate));
			if (!dominated && frontier.stream().noneMatch(existing -> sameVector(existing, candidate)
				&& compareTrace(existing, candidate) <= 0)) {
				frontier.removeIf(existing -> sameVector(existing, candidate) && compareTrace(candidate, existing) < 0);
				frontier.add(candidate);
			}
		}
		frontier.sort(ReverseTimetableRaptorPlanner::compareTrace);
		return List.copyOf(frontier);
	}

	private static boolean dominates(Candidate left, Candidate right) {
		return left.readyAtSeconds() >= right.readyAtSeconds()
			&& left.arrivalAtDestinationSeconds() <= right.arrivalAtDestinationSeconds()
			&& left.transfersUsed() <= right.transfersUsed()
			&& left.verifiedAccessSeconds() <= right.verifiedAccessSeconds()
			&& left.verifiedAccessDistanceMeters() <= right.verifiedAccessDistanceMeters()
			&& left.stairBurden() <= right.stairBurden()
			&& JourneyProfileRaptorPort.ConnectionSlack.compareSafety(left.connectionSlack(), right.connectionSlack()) >= 0
			&& (left.readyAtSeconds() > right.readyAtSeconds()
				|| left.arrivalAtDestinationSeconds() < right.arrivalAtDestinationSeconds()
				|| left.transfersUsed() < right.transfersUsed()
				|| left.verifiedAccessSeconds() < right.verifiedAccessSeconds()
				|| left.verifiedAccessDistanceMeters() < right.verifiedAccessDistanceMeters()
				|| left.stairBurden() < right.stairBurden()
				|| JourneyProfileRaptorPort.ConnectionSlack.compareSafety(left.connectionSlack(), right.connectionSlack()) > 0);
	}

	private static boolean sameVector(Candidate left, Candidate right) {
		return left.readyAtSeconds() == right.readyAtSeconds()
			&& left.arrivalAtDestinationSeconds() == right.arrivalAtDestinationSeconds()
			&& left.transfersUsed() == right.transfersUsed()
			&& left.verifiedAccessSeconds() == right.verifiedAccessSeconds()
			&& left.verifiedAccessDistanceMeters() == right.verifiedAccessDistanceMeters()
			&& left.stairBurden() == right.stairBurden()
			&& JourneyProfileRaptorPort.ConnectionSlack.compareSafety(left.connectionSlack(), right.connectionSlack()) == 0;
	}

	private static int compareTrace(Candidate left, Candidate right) {
		return traceKey(left).compareTo(traceKey(right));
	}

	private static String traceKey(Candidate candidate) {
		return candidate.legs().stream().map(leg -> switch (leg) {
			case TraceAccess access -> "a:" + access.transition();
			case TraceRide ride -> "r:" + ride.trip().index() + ':' + ride.boardIndex() + ':' + ride.alightIndex();
		}).reduce("", (left, right) -> left + '/' + right);
	}

	private static RouteTimetableRaptorPlanner.JourneyItinerary toItinerary(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		Candidate candidate
	) {
		List<RouteTimetableRaptorPlanner.JourneyLegProjection> legs = candidate.legs().stream()
			.map(leg -> projectLeg(query, timetable, realtimeOverlay, leg))
			.toList();
		TraceAccess entry = (TraceAccess) candidate.legs().getFirst();
		TraceRide firstRide = candidate.legs().stream().filter(TraceRide.class::isInstance)
			.map(TraceRide.class::cast).findFirst().orElseThrow();
		TraceRide lastRide = candidate.legs().stream().filter(TraceRide.class::isInstance)
			.map(TraceRide.class::cast).reduce((ignored, current) -> current).orElseThrow();
		TraceAccess exit = (TraceAccess) candidate.legs().getLast();
		int plannedReadyAt = firstRide.trip().departureSeconds(firstRide.boardIndex())
			- accessSeconds(query, timetable, entry.transition(), entry.access()) - query.boardingSlackSeconds();
		int plannedArrivalAtDestination = lastRide.trip().arrivalSeconds(lastRide.alightIndex())
			+ accessSeconds(query, timetable, exit.transition(), Access.EXIT);
		return new RouteTimetableRaptorPlanner.JourneyItinerary(
			query.serviceDate(),
			serviceInstant(query.serviceDate(), plannedReadyAt),
			serviceInstant(query.serviceDate(), plannedArrivalAtDestination),
			realtimeOverlay.available() ? serviceInstant(query.serviceDate(), candidate.readyAtSeconds()) : null,
			realtimeOverlay.available() ? serviceInstant(query.serviceDate(), candidate.arrivalAtDestinationSeconds()) : null,
			RouteTimetableRaptorPlanner.itineraryMetrics(legs, query.boardingSlackSeconds()),
			legs
		);
	}

	private static RouteTimetableRaptorPlanner.JourneyLegProjection projectLeg(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		TraceLeg leg
	) {
		if (leg instanceof TraceAccess access) {
			return new RouteTimetableRaptorPlanner.JourneyAccessProjection(
				switch (access.access()) {
					case ENTRY -> RouteTimetableRaptorPlanner.JourneyAccessKind.ENTRY;
					case TRANSFER -> RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER;
					case EXIT -> RouteTimetableRaptorPlanner.JourneyAccessKind.EXIT;
				},
				access.fromStationId(), access.toStationId(),
				accessSeconds(query, timetable, access.transition(), access.access()),
				timetable.transitionDistanceMeters(access.transition()),
				timetable.transitionIncludesStairs(access.transition()),
				timetable.transitionVerified(access.transition()),
				timetable.transitionVerificationStatus(access.transition())
			);
		}
		TraceRide ride = (TraceRide) leg;
		boolean hasRealtimeEvidence = realtimeOverlay.evidence(ride.trip()) != null;
		return new RouteTimetableRaptorPlanner.JourneyRideProjection(
			ride.trip().lineId(ride.boardIndex()),
			ride.trip().trip().id(),
			ride.trip().stopTimes().getLast().stationId(),
			ride.trip().stopTimes().get(ride.boardIndex()).stationId(),
			ride.trip().stopTimes().get(ride.alightIndex()).stationId(),
			serviceInstant(query.serviceDate(), ride.trip().departureSeconds(ride.boardIndex())),
			serviceInstant(query.serviceDate(), ride.trip().arrivalSeconds(ride.alightIndex())),
			!hasRealtimeEvidence ? null : serviceInstant(query.serviceDate(),
				realtimeOverlay.departureSeconds(ride.trip(), ride.boardIndex())),
			!hasRealtimeEvidence ? null : serviceInstant(query.serviceDate(),
				realtimeOverlay.arrivalSeconds(ride.trip(), ride.alightIndex()))
		);
	}

	private static Instant serviceInstant(LocalDate serviceDate, int serviceSeconds) {
		return serviceDate.atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(serviceSeconds).toInstant();
	}

	enum Outcome {
		FOUND,
		NO_ACTIVE_SERVICE,
		NO_VERIFIED_EXIT,
		DEADLINE_MISS,
		NO_OD_CONNECTION,
		CANCELLED
	}

	record Query(
		String originStationId,
		String destinationStationId,
		LocalDate serviceDate,
		int earliestReadyAtSeconds,
		int arrivalDeadlineSeconds,
		int maxTransfers,
		int accessProfileBit,
		int boardingSlackSeconds,
		MobilityPreset mobilityPreset,
		int walkingSpeedMetersPerHour,
		boolean requiresVerifiedJourneyDistance,
		BooleanSupplier cancelled
	) {
		Query {
			if (originStationId == null || originStationId.isBlank() || destinationStationId == null || destinationStationId.isBlank()
				|| originStationId.equals(destinationStationId)) {
				throw new IllegalArgumentException("origin and destination must be distinct nonblank station ids");
			}
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate must not be null");
			if (earliestReadyAtSeconds < 0 || arrivalDeadlineSeconds < earliestReadyAtSeconds || maxTransfers < 0
				|| accessProfileBit <= 0 || boardingSlackSeconds < 0 || walkingSpeedMetersPerHour <= 0) {
				throw new IllegalArgumentException("reverse query values must be explicit and valid");
			}
			mobilityPreset = Objects.requireNonNull(mobilityPreset, "mobilityPreset must not be null");
			cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
		}
	}

	record LastConnectionQuery(
		String originStationId,
		String destinationStationId,
		LocalDate serviceDate,
		int maxTransfers,
		int accessProfileBit,
		int boardingSlackSeconds,
		MobilityPreset mobilityPreset,
		int walkingSpeedMetersPerHour,
		boolean requiresVerifiedJourneyDistance,
		BooleanSupplier cancelled
	) {
		LastConnectionQuery {
			if (originStationId == null || originStationId.isBlank() || destinationStationId == null || destinationStationId.isBlank()
				|| originStationId.equals(destinationStationId)) {
				throw new IllegalArgumentException("origin and destination must be distinct nonblank station ids");
			}
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate must not be null");
			if (maxTransfers < 0 || accessProfileBit <= 0 || boardingSlackSeconds < 0 || walkingSpeedMetersPerHour <= 0) {
				throw new IllegalArgumentException("last-connection query values must be explicit and valid");
			}
			mobilityPreset = Objects.requireNonNull(mobilityPreset, "mobilityPreset must not be null");
			cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
		}
	}

	record LastConnectionResult(Result result, Integer terminalArrivalAtDestinationSeconds) {
		LastConnectionResult {
			result = Objects.requireNonNull(result, "result");
			if (terminalArrivalAtDestinationSeconds != null && terminalArrivalAtDestinationSeconds < 0) {
				throw new IllegalArgumentException("terminal arrival must not be negative");
			}
			if (result.outcome() == Outcome.FOUND && (terminalArrivalAtDestinationSeconds == null
				|| terminalArrivalAtDestinationSeconds < result.arrivalAtDestinationSeconds())) {
				throw new IllegalArgumentException("found last connection requires its terminal horizon");
			}
			if (result.outcome() == Outcome.CANCELLED && terminalArrivalAtDestinationSeconds != null) {
				throw new IllegalArgumentException("cancelled last connection must not retain a terminal horizon");
			}
		}

		static LastConnectionResult cancelled() {
			return new LastConnectionResult(Result.cancelled(), null);
		}
	}

	record Result(
		Outcome outcome,
		Integer latestReadyAtSeconds,
		Integer arrivalAtDestinationSeconds,
		Integer transfersUsed,
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary,
		List<RouteTimetableRaptorPlanner.JourneyItinerary> itineraries
	) {
		Result {
			Objects.requireNonNull(outcome, "outcome must not be null");
			if (outcome == Outcome.FOUND) {
				Objects.requireNonNull(latestReadyAtSeconds, "found result needs latestReadyAtSeconds");
				Objects.requireNonNull(arrivalAtDestinationSeconds, "found result needs arrivalAtDestinationSeconds");
				Objects.requireNonNull(transfersUsed, "found result needs transfersUsed");
				Objects.requireNonNull(itinerary, "found result needs itinerary");
				itineraries = List.copyOf(Objects.requireNonNull(itineraries, "found result needs itineraries"));
				if (itineraries.isEmpty() || !itineraries.contains(itinerary)) {
					throw new IllegalArgumentException("found result must retain its immutable itinerary frontier");
				}
			} else if (latestReadyAtSeconds != null || arrivalAtDestinationSeconds != null || transfersUsed != null
				|| itinerary != null || itineraries != null && !itineraries.isEmpty()) {
				throw new IllegalArgumentException("non-found result must not contain a journey");
			} else {
				itineraries = List.of();
			}
		}

		static Result of(Outcome outcome) {
			return new Result(outcome, null, null, null, null, List.of());
		}

		static Result cancelled() {
			return of(Outcome.CANCELLED);
		}

		static Result found(
			List<Candidate> candidates,
			Query query,
			RouteTimetableRaptorPlanner.CompiledTimetable timetable,
			RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
		) {
			List<RouteTimetableRaptorPlanner.JourneyItinerary> itineraries = candidates.stream()
				.map(candidate -> toItinerary(query, timetable, realtimeOverlay, candidate)).toList();
			Candidate latest = candidates.stream().min(Comparator.comparingInt(Candidate::readyAtSeconds).reversed()
				.thenComparing(ReverseTimetableRaptorPlanner::compareTrace)).orElseThrow();
			int itineraryIndex = candidates.indexOf(latest);
			return new Result(Outcome.FOUND, latest.readyAtSeconds(), latest.arrivalAtDestinationSeconds(),
				latest.transfersUsed(), itineraries.get(itineraryIndex), itineraries);
		}
	}

	private enum Access {
		ENTRY,
		TRANSFER,
		EXIT
	}

	private sealed interface TraceLeg permits TraceAccess, TraceRide {
	}

	private record TraceAccess(Access access, int transition, String fromStationId, String toStationId)
		implements TraceLeg {
	}

	private record TraceRide(RouteTimetableRaptorPlanner.ScheduledTrip trip, int boardIndex, int alightIndex)
		implements TraceLeg {
	}

	private record Candidate(
		int readyAtSeconds,
		int arrivalAtDestinationSeconds,
		int transfersUsed,
		long verifiedAccessSeconds,
		long verifiedAccessDistanceMeters,
		long stairBurden,
		JourneyProfileRaptorPort.ConnectionSlack connectionSlack,
		List<TraceLeg> legs
	) {
		private Candidate {
			if (verifiedAccessSeconds < 0 || verifiedAccessDistanceMeters < 0 || stairBurden < 0) {
				throw new IllegalArgumentException("candidate access facts must not be negative");
			}
			connectionSlack = Objects.requireNonNull(connectionSlack, "connectionSlack");
			legs = List.copyOf(legs);
		}

		private Candidate appendAccess(TraceAccess access, int seconds, int distanceMeters, boolean includesStairs) {
			List<TraceLeg> appended = new ArrayList<>(legs);
			appended.add(access);
			return new Candidate(readyAtSeconds, arrivalAtDestinationSeconds, transfersUsed,
				Math.addExact(verifiedAccessSeconds, seconds), Math.addExact(verifiedAccessDistanceMeters, distanceMeters),
				Math.addExact(stairBurden, includesStairs ? 1L : 0L), connectionSlack, appended);
		}

		private Candidate appendTransferAndRide(
			TraceAccess access,
			int seconds,
			int distanceMeters,
			boolean includesStairs,
			long transferSlack,
			TraceRide ride
		) {
			List<TraceLeg> appended = new ArrayList<>(legs);
			appended.add(access);
			appended.add(ride);
			JourneyProfileRaptorPort.ConnectionSlack slack = connectionSlack instanceof JourneyProfileRaptorPort.NoTransfer
				? new JourneyProfileRaptorPort.MinimumTransferSeconds(transferSlack)
				: new JourneyProfileRaptorPort.MinimumTransferSeconds(Math.min(
					((JourneyProfileRaptorPort.MinimumTransferSeconds) connectionSlack).seconds(), transferSlack));
			return new Candidate(readyAtSeconds, arrivalAtDestinationSeconds, transfersUsed,
				Math.addExact(verifiedAccessSeconds, seconds), Math.addExact(verifiedAccessDistanceMeters, distanceMeters),
				Math.addExact(stairBurden, includesStairs ? 1L : 0L), slack, appended);
		}
	}

	enum PlanningLimit {
		MAX_ESTIMATED_WORK,
		MAX_LABELS_PER_STATE,
		MAX_DESTINATION_PROFILE_LABELS
	}

	static final class ReversePlanningLimitException extends RuntimeException {
		private final PlanningLimit limit;
		private final long observed;
		private final long max;

		private ReversePlanningLimitException(PlanningLimit limit, long observed, long max) {
			super(Objects.requireNonNull(limit, "limit").name());
			this.limit = limit;
			this.observed = observed;
			this.max = max;
		}

		PlanningLimit limit() { return limit; }
		long observed() { return observed; }
		long max() { return max; }
	}

	private static final class ReverseLimitTracker {
		private final JourneyProfileResourcePolicy.ProfilePlanningLimits limits;
		private long work;

		private ReverseLimitTracker(JourneyProfileResourcePolicy.ProfilePlanningLimits limits) {
			this.limits = Objects.requireNonNull(limits, "limits");
		}

		private void consumeWork() {
			work = Math.addExact(work, 1L);
			if (work > limits.maxEstimatedWork()) {
				throw new ReversePlanningLimitException(PlanningLimit.MAX_ESTIMATED_WORK, work, limits.maxEstimatedWork());
			}
		}

		private int maxDestinationProfileLabels() {
			return limits.maxDestinationProfileLabels();
		}

		private List<Candidate> admit(Candidate candidate) {
			return admitAll(List.of(candidate));
		}

		private List<Candidate> admitAll(List<Candidate> candidates) {
			List<Candidate> frontier = new ArrayList<>();
			for (Candidate candidate : candidates) {
				consumeWork();
				if (frontier.stream().anyMatch(existing -> dominates(existing, candidate)
					|| sameVector(existing, candidate) && compareTrace(existing, candidate) <= 0)) continue;
				frontier.removeIf(existing -> dominates(candidate, existing)
					|| sameVector(existing, candidate) && compareTrace(candidate, existing) < 0);
				frontier.add(candidate);
				frontier.sort(ReverseTimetableRaptorPlanner::compareTrace);
				if (frontier.size() > limits.maxLabelsPerState()) {
					throw new ReversePlanningLimitException(PlanningLimit.MAX_LABELS_PER_STATE,
						frontier.size(), limits.maxLabelsPerState());
				}
			}
			return List.copyOf(frontier);
		}
	}

	private record TraceState(int tripIndex, int boardIndex, int alightIndex, int transfersUsed) {
	}
}
