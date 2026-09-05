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
import java.util.function.Function;

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
		return lastConnection(query, timetable, activeServiceDay, realtimeOverlay, limits, null);
	}

	LastConnectionResult lastConnection(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		Objects.requireNonNull(activeServiceDay, "activeServiceDay must not be null");
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		if (query.cancelled().getAsBoolean()) {
			return LastConnectionResult.cancelled();
		}
		ReverseLimitTracker limitTracker = new ReverseLimitTracker(limits, observations);
		DatedTripCollection collection = activeTrips(
			timetable, query.serviceDate(), query.serviceDate(), ignored -> activeServiceDay,
			limitTracker, query.cancelled());
		if (collection.cancelled()) return LastConnectionResult.cancelled();
		List<DatedScheduledTrip> activeTrips = collection.trips();
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
			timetable, activeTrips, realtimeOverlay, limitTracker);
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
		return arriveBy(query, timetable, activeServiceDay, realtimeOverlay, limits, null);
	}

	Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		Objects.requireNonNull(activeServiceDay, "activeServiceDay must not be null");
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		ReverseLimitTracker limitTracker = new ReverseLimitTracker(limits, observations);
		DatedTripCollection collection = activeTrips(
			timetable, query.serviceDate(), query.serviceDate(), ignored -> activeServiceDay,
			limitTracker, query.cancelled());
		if (collection.cancelled()) return Result.cancelled();
		return arriveBy(query, timetable, collection.trips(), realtimeOverlay, limitTracker);
	}

	Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		LocalDate firstServiceDate,
		LocalDate lastServiceDate,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits
	) {
		return arriveBy(query, timetable, firstServiceDate, lastServiceDate, realtimeOverlay, limits, null);
	}

	Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		LocalDate firstServiceDate,
		LocalDate lastServiceDate,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		firstServiceDate = Objects.requireNonNull(firstServiceDate, "firstServiceDate must not be null");
		lastServiceDate = Objects.requireNonNull(lastServiceDate, "lastServiceDate must not be null");
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		if (lastServiceDate.isBefore(firstServiceDate)) {
			throw new IllegalArgumentException("dated reverse search requires an ordered service-date range");
		}
		if (query.cancelled().getAsBoolean()) return Result.cancelled();
		ReverseLimitTracker limitTracker = new ReverseLimitTracker(limits, observations);
		DatedTripCollection collection = activeTrips(
			timetable, firstServiceDate, lastServiceDate, timetable::activeServiceDay,
			limitTracker, query.cancelled());
		if (collection.cancelled()) return Result.cancelled();
		return arriveBy(query, timetable, collection.trips(), realtimeOverlay, limitTracker);
	}

	private Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<DatedScheduledTrip> activeTrips,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		ReverseLimitTracker limitTracker
	) {
		if (query.cancelled().getAsBoolean()) {
			return Result.cancelled();
		}
		if (activeTrips.isEmpty()) {
			return Result.of(Outcome.NO_ACTIVE_SERVICE);
		}
		int origin = timetable.stationIndex(query.originStationId());
		int destination = timetable.stationIndex(query.destinationStationId());
		if (origin < 0 || destination < 0) {
			return Result.of(Outcome.NO_OD_CONNECTION);
		}

		boolean permittedDestinationStopExists = false;
		boolean verifiedExitExists = false;
		boolean exitCanMeetDeadline = false;
		List<Candidate> candidates = new ArrayList<>();
		for (DatedScheduledTrip trip : activeTrips) {
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
				permittedDestinationStopExists = true;
				int line = timetable.lineIndex(trip.lineId(alightIndex));
				int exit = line < 0 ? -1 : timetable.exitTransition(
					destination, line, query.accessProfileBit(), false, query.requiresVerifiedJourneyDistance());
				if (!verifiedTransition(timetable, exit)) {
					limitTracker.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
					continue;
				}
				verifiedExitExists = true;
				int destinationArrival = Math.addExact(
					arrivalSeconds(query, trip, alightIndex, realtimeOverlay), accessSeconds(query, timetable, exit, Access.EXIT));
				if (destinationArrival > query.arrivalDeadlineSeconds()) {
					continue;
				}
				exitCanMeetDeadline = true;
				if (realtimeOverlay.cancelled(trip.scheduledTrip())) {
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
		List<Candidate> frontier = destinationFrontier(candidates, limitTracker);
		limitTracker.observeDestinationLabels(frontier.size());
		if (!frontier.isEmpty()) {
			if (frontier.size() > limitTracker.maxDestinationProfileLabels()) {
				limitTracker.count("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
				throw new ReversePlanningLimitException(PlanningLimit.MAX_DESTINATION_PROFILE_LABELS,
					frontier.size(), limitTracker.maxDestinationProfileLabels());
			}
			return Result.found(frontier, query, timetable, realtimeOverlay);
		}
		if (!permittedDestinationStopExists) {
			return Result.of(Outcome.NO_OD_CONNECTION);
		}
		if (!verifiedExitExists) {
			return Result.of(Outcome.NO_VERIFIED_EXIT);
		}
		return Result.of(exitCanMeetDeadline ? Outcome.NO_OD_CONNECTION : Outcome.DEADLINE_MISS);
	}

	private List<Candidate> traceToOrigin(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<DatedScheduledTrip> activeTrips,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		DatedScheduledTrip downstreamTrip,
		int downstreamBoardIndex,
		int downstreamAlightIndex,
		int arrivalAtDestinationSeconds,
		int transfersUsed,
		Set<TraceState> visiting,
		ReverseLimitTracker limits
	) {
		if (query.cancelled().getAsBoolean() || realtimeOverlay.cancelled(downstreamTrip.scheduledTrip())) {
			return List.of();
		}
		TraceState state = new TraceState(downstreamTrip, downstreamBoardIndex, downstreamAlightIndex, transfersUsed);
		if (!visiting.add(state)) {
			return List.of();
		}
		try {
			String boardStation = downstreamTrip.stopTimes().get(downstreamBoardIndex).stationId();
			int downstreamLine = timetable.lineIndex(downstreamTrip.lineId(downstreamBoardIndex));
			int downstreamDeparture = departureSeconds(query, downstreamTrip, downstreamBoardIndex, realtimeOverlay);
			if (query.originStationId().equals(boardStation)) {
				int origin = timetable.stationIndex(boardStation);
				int entry = origin < 0 || downstreamLine < 0 ? -1 : timetable.entryTransition(
					origin, downstreamLine, query.accessProfileBit(), false, query.requiresVerifiedJourneyDistance());
				if (!verifiedTransition(timetable, entry)) {
					limits.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
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
			for (DatedScheduledTrip upstreamTrip : activeTrips) {
				limits.consumeWork();
				if (query.cancelled().getAsBoolean() || realtimeOverlay.cancelled(upstreamTrip.scheduledTrip())) {
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
						limits.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
						continue;
					}
					int latestArrival = downstreamDeparture - accessSeconds(query, timetable, transfer, Access.TRANSFER)
						- query.boardingSlackSeconds();
					if (arrivalSeconds(query, upstreamTrip, upstreamAlightIndex, realtimeOverlay) > latestArrival) {
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
								- arrivalSeconds(query, upstreamTrip, upstreamAlightIndex, realtimeOverlay)
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
		List<DatedScheduledTrip> activeTrips,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		ReverseLimitTracker limits
	) {
		Integer latest = null;
		int destination = timetable.stationIndex(query.destinationStationId());
		for (DatedScheduledTrip trip : activeTrips) {
			limits.consumeWork();
			if (query.cancelled().getAsBoolean()) {
				return null;
			}
			if (realtimeOverlay.cancelled(trip.scheduledTrip())) {
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
				int arrivalAtDestination = Math.addExact(arrivalSeconds(query.serviceDate(), trip, alightIndex,
					realtimeOverlay), accessSeconds(query, timetable, exit, Access.EXIT));
				latest = latest == null || arrivalAtDestination > latest ? arrivalAtDestination : latest;
			}
		}
		return latest;
	}

	private static boolean hasVerifiedExit(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<DatedScheduledTrip> activeTrips,
		ReverseLimitTracker limits
	) {
		int destination = timetable.stationIndex(query.destinationStationId());
		for (DatedScheduledTrip trip : activeTrips) {
			limits.consumeWork();
			for (int alightIndex = 1; alightIndex < trip.stopTimes().size(); alightIndex += 1) {
				limits.consumeWork();
				// 출구 증거의 존재와 열차 하차 허용은 별개이며, 경로 허용은 terminalDeadline이 검증한다.
				if (!query.destinationStationId().equals(trip.stopTimes().get(alightIndex).stationId())) {
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

	private static DatedTripCollection activeTrips(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		LocalDate firstServiceDate,
		LocalDate lastServiceDate,
		Function<LocalDate, RouteTimetableRaptorPlanner.ActiveServiceDay> activeDays,
		ReverseLimitTracker limits,
		BooleanSupplier cancelled
	) {
		List<DatedScheduledTrip> trips = new ArrayList<>();
		for (LocalDate serviceDate = firstServiceDate; !serviceDate.isAfter(lastServiceDate);
			serviceDate = serviceDate.plusDays(1)) {
			if (cancelled.getAsBoolean()) return new DatedTripCollection(List.of(), true);
			limits.consumeWork();
			RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay = activeDays.apply(serviceDate);
			Set<Integer> seen = new HashSet<>();
			for (int pattern = 0; pattern < timetable.routePatternCount(); pattern += 1) {
				if (cancelled.getAsBoolean()) return new DatedTripCollection(List.of(), true);
				limits.consumeWork();
				for (RouteTimetableRaptorPlanner.ScheduledTrip trip : activeServiceDay.tripsByPattern(pattern)) {
					if (cancelled.getAsBoolean()) return new DatedTripCollection(List.of(), true);
					limits.consumeWork();
					if (seen.add(trip.index())) trips.add(new DatedScheduledTrip(serviceDate, trip));
				}
			}
		}
		trips.sort(Comparator.comparingInt((DatedScheduledTrip trip) -> trip.departureSeconds(0))
			.reversed().thenComparing(DatedScheduledTrip::serviceDate).thenComparingInt(DatedScheduledTrip::index));
		return new DatedTripCollection(List.copyOf(trips), false);
	}

	private record DatedTripCollection(List<DatedScheduledTrip> trips, boolean cancelled) { }

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


	private static List<Candidate> destinationFrontier(List<Candidate> candidates, ReverseLimitTracker limits) {
		List<Candidate> frontier = new ArrayList<>();
		for (Candidate candidate : candidates) {
			boolean dominated = candidates.stream().anyMatch(other -> other != candidate && dominates(other, candidate));
			if (dominated) {
				limits.count("REVERSE_DESTINATION_DOMINANCE_V1");
			} else if (frontier.stream().anyMatch(existing -> sameVector(existing, candidate)
				&& compareTrace(existing, candidate) <= 0)) {
				limits.count("REVERSE_DESTINATION_EQUAL_VECTOR_CANONICAL_TRACE_V1");
			} else {
				frontier.removeIf(existing -> {
					if (sameVector(existing, candidate) && compareTrace(candidate, existing) < 0) {
						limits.count("REVERSE_DESTINATION_EQUAL_VECTOR_CANONICAL_TRACE_V1");
						return true;
					}
					return false;
				});
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
			case TraceRide ride -> "r:" + ride.trip().serviceDate() + ':' + ride.trip().index() + ':'
				+ ride.boardIndex() + ':' + ride.alightIndex();
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
		int plannedReadyAt = serviceDateOffsetSeconds(query.serviceDate(), firstRide.trip().serviceDate())
			+ firstRide.trip().departureSeconds(firstRide.boardIndex())
			- accessSeconds(query, timetable, entry.transition(), entry.access()) - query.boardingSlackSeconds();
		int plannedArrivalAtDestination = serviceDateOffsetSeconds(query.serviceDate(), lastRide.trip().serviceDate())
			+ lastRide.trip().arrivalSeconds(lastRide.alightIndex())
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
		boolean hasRealtimeEvidence = realtimeOverlay.evidence(ride.trip().scheduledTrip()) != null;
		return new RouteTimetableRaptorPlanner.JourneyRideProjection(
			ride.trip().lineId(ride.boardIndex()),
			ride.trip().scheduledTrip().trip().id(),
			ride.trip().stopTimes().getLast().stationId(),
			ride.trip().stopTimes().get(ride.boardIndex()).stationId(),
			ride.trip().stopTimes().get(ride.alightIndex()).stationId(),
			serviceInstant(ride.trip().serviceDate(), ride.trip().departureSeconds(ride.boardIndex())),
			serviceInstant(ride.trip().serviceDate(), ride.trip().arrivalSeconds(ride.alightIndex())),
			!hasRealtimeEvidence ? null : serviceInstant(ride.trip().serviceDate(),
				realtimeOverlay.departureSeconds(ride.trip().scheduledTrip(), ride.boardIndex())),
			!hasRealtimeEvidence ? null : serviceInstant(ride.trip().serviceDate(),
				realtimeOverlay.arrivalSeconds(ride.trip().scheduledTrip(), ride.alightIndex()))
		);
	}

	private static Instant serviceInstant(LocalDate serviceDate, int serviceSeconds) {
		return serviceDate.atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(serviceSeconds).toInstant();
	}

	private static int departureSeconds(
		Query query,
		DatedScheduledTrip trip,
		int stopIndex,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		return departureSeconds(query.serviceDate(), trip, stopIndex, realtimeOverlay);
	}

	private static int departureSeconds(
		LocalDate anchorServiceDate,
		DatedScheduledTrip trip,
		int stopIndex,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		return Math.addExact(serviceDateOffsetSeconds(anchorServiceDate, trip.serviceDate()),
			realtimeOverlay.departureSeconds(trip.scheduledTrip(), stopIndex));
	}

	private static int arrivalSeconds(
		Query query,
		DatedScheduledTrip trip,
		int stopIndex,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		return arrivalSeconds(query.serviceDate(), trip, stopIndex, realtimeOverlay);
	}

	private static int arrivalSeconds(
		LocalDate anchorServiceDate,
		DatedScheduledTrip trip,
		int stopIndex,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		return Math.addExact(serviceDateOffsetSeconds(anchorServiceDate, trip.serviceDate()),
			realtimeOverlay.arrivalSeconds(trip.scheduledTrip(), stopIndex));
	}

	private static int serviceDateOffsetSeconds(LocalDate anchorServiceDate, LocalDate serviceDate) {
		return Math.toIntExact(java.time.Duration.between(
			anchorServiceDate.atStartOfDay(ServiceDayResolver.ZONE), serviceDate.atStartOfDay(ServiceDayResolver.ZONE)).toSeconds());
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

	private record DatedScheduledTrip(LocalDate serviceDate, RouteTimetableRaptorPlanner.ScheduledTrip scheduledTrip) {
		private DatedScheduledTrip {
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			scheduledTrip = Objects.requireNonNull(scheduledTrip, "scheduledTrip");
		}

		private int index() { return scheduledTrip.index(); }
		private List<com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime> stopTimes() {
			return scheduledTrip.stopTimes();
		}
		private int departureSeconds(int stopIndex) { return scheduledTrip.departureSeconds(stopIndex); }
		private int arrivalSeconds(int stopIndex) { return scheduledTrip.arrivalSeconds(stopIndex); }
		private boolean allowsPickup(int stopIndex) { return scheduledTrip.allowsPickup(stopIndex); }
		private boolean allowsDropOff(int stopIndex) { return scheduledTrip.allowsDropOff(stopIndex); }
		private String lineId(int stopIndex) { return scheduledTrip.lineId(stopIndex); }
	}

	private record TraceRide(DatedScheduledTrip trip, int boardIndex, int alightIndex)
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

		private final JourneyProfilePruningObservationAccumulator observations;

		private ReverseLimitTracker(JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
			JourneyProfilePruningObservationAccumulator observations) {
			this.limits = Objects.requireNonNull(limits, "limits");
			this.observations = observations;
		}

		private void consumeWork() {
			work = Math.addExact(work, 1L);
			if (observations != null) observations.consumeWork();
			if (work > limits.maxEstimatedWork()) {
				throw new ReversePlanningLimitException(PlanningLimit.MAX_ESTIMATED_WORK, work, limits.maxEstimatedWork());
			}
		}

		private int maxDestinationProfileLabels() {
			return limits.maxDestinationProfileLabels();
		}

		private void count(String ruleId) {
			if (observations != null) observations.increment(ruleId);
		}

		private void observeDestinationLabels(int labels) {
			if (observations != null) observations.observeDestinationLabels(labels);
		}

		private void observeStateLabels(int labels) {
			if (observations != null) observations.observeStateLabels(labels);
		}

		private List<Candidate> admit(Candidate candidate) {
			return admitAll(List.of(candidate));
		}

		private List<Candidate> admitAll(List<Candidate> candidates) {
			List<Candidate> frontier = new ArrayList<>();
			for (Candidate candidate : candidates) {
				consumeWork();
				if (frontier.stream().anyMatch(existing -> dominates(existing, candidate))) {
					count("REVERSE_STATE_DOMINANCE_V1");
					continue;
				}
				if (frontier.stream().anyMatch(existing -> sameVector(existing, candidate)
					&& compareTrace(existing, candidate) <= 0)) {
					count("REVERSE_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1");
					continue;
				}
				frontier.removeIf(existing -> {
					if (dominates(candidate, existing)) {
						count("REVERSE_STATE_DOMINANCE_V1");
						return true;
					}
					if (sameVector(existing, candidate) && compareTrace(candidate, existing) < 0) {
						count("REVERSE_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1");
						return true;
					}
					return false;
				});
				frontier.add(candidate);
				frontier.sort(ReverseTimetableRaptorPlanner::compareTrace);
				observeStateLabels(frontier.size());
				if (frontier.size() > limits.maxLabelsPerState()) {
					count("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
					throw new ReversePlanningLimitException(PlanningLimit.MAX_LABELS_PER_STATE,
						frontier.size(), limits.maxLabelsPerState());
				}
			}
			return List.copyOf(frontier);
		}
	}

	private record TraceState(DatedScheduledTrip trip, int boardIndex, int alightIndex, int transfersUsed) {
	}
}
