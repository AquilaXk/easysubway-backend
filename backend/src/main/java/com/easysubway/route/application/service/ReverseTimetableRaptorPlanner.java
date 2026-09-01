package com.easysubway.route.application.service;

import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
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
					best = better(best, traceToOrigin(
						query, timetable, activeTrips, realtimeOverlay, trip, boardIndex, alightIndex,
						destinationArrival, 0, new HashSet<>()));
				}
			}
		}
		if (best != null) {
			return new Result(Outcome.FOUND, best.readyAtSeconds(), best.arrivalAtDestinationSeconds(), best.transfersUsed());
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
					? null : new Candidate(readyAt, arrivalAtDestinationSeconds, transfersUsed);
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
						best = better(best, traceToOrigin(
							query, timetable, activeTrips, realtimeOverlay, upstreamTrip, upstreamBoardIndex, upstreamAlightIndex,
							arrivalAtDestinationSeconds, transfersUsed + 1, visiting));
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
			if (earliestReadyAtSeconds < 0 || arrivalDeadlineSeconds < earliestReadyAtSeconds || maxTransfers < 0
				|| accessProfileBit <= 0 || boardingSlackSeconds < 0 || walkingSpeedMetersPerHour <= 0) {
				throw new IllegalArgumentException("reverse query values must be explicit and valid");
			}
			mobilityPreset = Objects.requireNonNull(mobilityPreset, "mobilityPreset must not be null");
			cancelled = Objects.requireNonNull(cancelled, "cancelled must not be null");
		}
	}

	record Result(Outcome outcome, Integer latestReadyAtSeconds, Integer arrivalAtDestinationSeconds, Integer transfersUsed) {
		Result {
			Objects.requireNonNull(outcome, "outcome must not be null");
			if (outcome == Outcome.FOUND) {
				Objects.requireNonNull(latestReadyAtSeconds, "found result needs latestReadyAtSeconds");
				Objects.requireNonNull(arrivalAtDestinationSeconds, "found result needs arrivalAtDestinationSeconds");
				Objects.requireNonNull(transfersUsed, "found result needs transfersUsed");
			} else if (latestReadyAtSeconds != null || arrivalAtDestinationSeconds != null || transfersUsed != null) {
				throw new IllegalArgumentException("non-found result must not contain a journey");
			}
		}

		static Result of(Outcome outcome) {
			return new Result(outcome, null, null, null);
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

	private record Candidate(int readyAtSeconds, int arrivalAtDestinationSeconds, int transfersUsed) {
	}

	private record TraceState(int tripIndex, int boardIndex, int alightIndex, int transfersUsed) {
	}
}
