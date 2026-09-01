package com.easysubway.route.application.service;

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

	Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		Objects.requireNonNull(activeServiceDay, "activeServiceDay must not be null");
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
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
		Candidate best = null;
		for (RouteTimetableRaptorPlanner.ScheduledTrip trip : activeTrips) {
			if (query.cancelled().getAsBoolean()) {
				return Result.cancelled();
			}
			for (int alightIndex = 1; alightIndex < trip.stopTimes().size(); alightIndex += 1) {
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
					if (!trip.allowsPickup(boardIndex)) {
						continue;
					}
					Candidate traced = traceToOrigin(
						query, timetable, activeTrips, realtimeOverlay, trip, boardIndex, alightIndex,
						destinationArrival, 0, new HashSet<>());
					if (traced != null) {
						best = better(best, traced.append(new TraceAccess(
							Access.EXIT, exit, query.destinationStationId(), query.destinationStationId())));
					}
				}
			}
		}
		if (best != null) {
			return new Result(Outcome.FOUND, best.readyAtSeconds(), best.arrivalAtDestinationSeconds(), best.transfersUsed(),
				toItinerary(query, timetable, realtimeOverlay, best));
		}
		if (!verifiedExitExists) {
			return Result.of(Outcome.NO_VERIFIED_EXIT);
		}
		return Result.of(exitCanMeetDeadline ? Outcome.NO_OD_CONNECTION : Outcome.DEADLINE_MISS);
	}

	private Candidate traceToOrigin(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<RouteTimetableRaptorPlanner.ScheduledTrip> activeTrips,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		RouteTimetableRaptorPlanner.ScheduledTrip downstreamTrip,
		int downstreamBoardIndex,
		int downstreamAlightIndex,
		int arrivalAtDestinationSeconds,
		int transfersUsed,
		Set<TraceState> visiting
	) {
		if (query.cancelled().getAsBoolean() || realtimeOverlay.cancelled(downstreamTrip)) {
			return null;
		}
		TraceState state = new TraceState(downstreamTrip.index(), downstreamBoardIndex, downstreamAlightIndex, transfersUsed);
		if (!visiting.add(state)) {
			return null;
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
					return null;
				}
				int readyAt = downstreamDeparture - accessSeconds(query, timetable, entry, Access.ENTRY)
					- query.boardingSlackSeconds();
				return readyAt < query.earliestReadyAtSeconds()
					? null : new Candidate(readyAt, arrivalAtDestinationSeconds, transfersUsed, List.of(
						new TraceAccess(Access.ENTRY, entry, query.originStationId(), boardStation),
						new TraceRide(downstreamTrip, downstreamBoardIndex, downstreamAlightIndex)));
			}
			if (transfersUsed >= query.maxTransfers()) {
				return null;
			}

			Candidate best = null;
			for (RouteTimetableRaptorPlanner.ScheduledTrip upstreamTrip : activeTrips) {
				if (query.cancelled().getAsBoolean() || realtimeOverlay.cancelled(upstreamTrip)) {
					continue;
				}
				for (int upstreamAlightIndex = 1; upstreamAlightIndex < upstreamTrip.stopTimes().size(); upstreamAlightIndex += 1) {
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
						if (!upstreamTrip.allowsPickup(upstreamBoardIndex)) {
							continue;
						}
						Candidate upstream = traceToOrigin(
							query, timetable, activeTrips, realtimeOverlay, upstreamTrip, upstreamBoardIndex, upstreamAlightIndex,
							arrivalAtDestinationSeconds, transfersUsed + 1, visiting);
						if (upstream != null) {
							best = better(best, upstream
								.append(new TraceAccess(Access.TRANSFER, transfer, boardStation, boardStation))
								.append(new TraceRide(downstreamTrip, downstreamBoardIndex, downstreamAlightIndex)));
						}
					}
				}
			}
			return best;
		} finally {
			visiting.remove(state);
		}
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
		int baseline = timetable.transitionDurationSeconds(transition);
		if (access == Access.TRANSFER && query.requiresVerifiedJourneyDistance()) {
			return ProfileWalkTimeCalculator.journeySeconds(timetable.transitionDistanceMeters(transition),
				query.walkingSpeedMetersPerHour(), query.mobilityPreset(), false);
		}
		return ProfileWalkTimeCalculator.estimateSeconds(
			baseline, query.mobilityPreset(), WalkTimeSource.OFFICIAL_BASELINE, false).seconds();
	}

	private static Candidate better(Candidate current, Candidate candidate) {
		if (candidate == null || current != null && current.readyAtSeconds() >= candidate.readyAtSeconds()) {
			return current;
		}
		return candidate;
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

	record Result(
		Outcome outcome,
		Integer latestReadyAtSeconds,
		Integer arrivalAtDestinationSeconds,
		Integer transfersUsed,
		RouteTimetableRaptorPlanner.JourneyItinerary itinerary
	) {
		Result {
			Objects.requireNonNull(outcome, "outcome must not be null");
			if (outcome == Outcome.FOUND) {
				Objects.requireNonNull(latestReadyAtSeconds, "found result needs latestReadyAtSeconds");
				Objects.requireNonNull(arrivalAtDestinationSeconds, "found result needs arrivalAtDestinationSeconds");
				Objects.requireNonNull(transfersUsed, "found result needs transfersUsed");
				Objects.requireNonNull(itinerary, "found result needs itinerary");
			} else if (latestReadyAtSeconds != null || arrivalAtDestinationSeconds != null || transfersUsed != null
				|| itinerary != null) {
				throw new IllegalArgumentException("non-found result must not contain a journey");
			}
		}

		static Result of(Outcome outcome) {
			return new Result(outcome, null, null, null, null);
		}

		static Result cancelled() {
			return of(Outcome.CANCELLED);
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
		List<TraceLeg> legs
	) {
		private Candidate {
			legs = List.copyOf(legs);
		}

		private Candidate append(TraceLeg leg) {
			List<TraceLeg> appended = new ArrayList<>(legs);
			appended.add(leg);
			return new Candidate(readyAtSeconds, arrivalAtDestinationSeconds, transfersUsed, appended);
		}
	}

	private record TraceState(int tripIndex, int boardIndex, int alightIndex, int transfersUsed) {
	}
}
