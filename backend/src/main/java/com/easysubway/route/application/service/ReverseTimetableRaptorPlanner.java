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
		RaptorRealtimeRuntimeView realtimeRuntime,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(realtimeRuntime, "realtimeRuntime must not be null");
		return lastConnection(query, timetable, activeServiceDay, realtimeRuntime::realtimeOverlay, limits, observations);
	}

	LastConnectionResult lastConnection(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		return lastConnection(query, timetable, activeServiceDay, ignored -> realtimeOverlay, limits, observations);
	}

	private LastConnectionResult lastConnection(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		Function<LocalDate, RouteTimetableRaptorPlanner.RealtimeOverlay> overlays,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		Objects.requireNonNull(activeServiceDay, "activeServiceDay must not be null");
		Objects.requireNonNull(overlays, "overlays must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		PreparedLastConnection preparation = prepareLastConnection(
			query, timetable, activeServiceDay, overlays, limits, observations);
		if (preparation.outcome() != Outcome.FOUND) {
			return new LastConnectionResult(Result.of(preparation.outcome()), null);
		}
		Integer terminalDeadline = preparation.terminalArrivalAtDestinationSeconds();
		Result result = arriveBy(new Query(
			query.originStationId(), query.destinationStationId(), query.serviceDate(), 0, terminalDeadline,
			query.maxTransfers(), query.accessProfileBit(), query.boardingSlackSeconds(), query.mobilityPreset(),
			query.walkingSpeedMetersPerHour(), query.requiresVerifiedJourneyDistance(), query.cancelled()),
			timetable, preparation.activeTrips(), preparation.limitTracker());
		if (result.outcome() == Outcome.CANCELLED) {
			return LastConnectionResult.cancelled();
		}
		return new LastConnectionResult(result.outcome() == Outcome.DEADLINE_MISS
			? Result.of(Outcome.NO_OD_CONNECTION) : result, terminalDeadline);
	}

	LastConnectionPreparation prepareLastConnection(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		PreparedLastConnection preparation = prepareLastConnection(
			query, timetable, activeServiceDay, ignored -> realtimeOverlay, limits, observations);
		return new LastConnectionPreparation(preparation.outcome(), preparation.terminalArrivalAtDestinationSeconds());
	}

	private PreparedLastConnection prepareLastConnection(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		Function<LocalDate, RouteTimetableRaptorPlanner.RealtimeOverlay> overlays,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		Objects.requireNonNull(activeServiceDay, "activeServiceDay must not be null");
		Objects.requireNonNull(overlays, "overlays must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		ReverseLimitTracker limitTracker = new ReverseLimitTracker(limits, observations);
		if (query.cancelled().getAsBoolean()) {
			return PreparedLastConnection.terminal(Outcome.CANCELLED, limitTracker);
		}
		DatedTripCollection collection = activeTrips(
			timetable, query.serviceDate(), query.serviceDate(), ignored -> activeServiceDay,
			overlays, limitTracker, query.cancelled());
		if (collection.cancelled()) return PreparedLastConnection.terminal(Outcome.CANCELLED, limitTracker);
		List<DatedScheduledTrip> activeTrips = collection.trips();
		if (activeTrips.isEmpty()) return PreparedLastConnection.terminal(Outcome.NO_ACTIVE_SERVICE, limitTracker);

		Integer terminalDeadline = terminalDeadline(query, activeTrips, limitTracker);
		if (query.cancelled().getAsBoolean()) return PreparedLastConnection.terminal(Outcome.CANCELLED, limitTracker);
		if (terminalDeadline != null) {
			return new PreparedLastConnection(Outcome.FOUND, terminalDeadline, activeTrips, limitTracker);
		}
		return PreparedLastConnection.terminal(Outcome.NO_OD_CONNECTION, limitTracker);
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
			ignored -> realtimeOverlay, limitTracker, query.cancelled());
		if (collection.cancelled()) return Result.cancelled();
		return arriveBy(query, timetable, collection.trips(), limitTracker);
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
		Objects.requireNonNull(realtimeOverlay, "realtimeOverlay must not be null");
		return arriveBy(query, timetable, firstServiceDate, lastServiceDate,
			ignored -> realtimeOverlay, limits, observations);
	}

	Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		LocalDate firstServiceDate,
		LocalDate lastServiceDate,
		Function<LocalDate, RouteTimetableRaptorPlanner.RealtimeOverlay> overlays,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(query, "query must not be null");
		Objects.requireNonNull(timetable, "timetable must not be null");
		firstServiceDate = Objects.requireNonNull(firstServiceDate, "firstServiceDate must not be null");
		lastServiceDate = Objects.requireNonNull(lastServiceDate, "lastServiceDate must not be null");
		Objects.requireNonNull(overlays, "overlays must not be null");
		Objects.requireNonNull(limits, "limits must not be null");
		if (lastServiceDate.isBefore(firstServiceDate)) {
			throw new IllegalArgumentException("dated reverse search requires an ordered service-date range");
		}
		if (query.cancelled().getAsBoolean()) return Result.cancelled();
		ReverseLimitTracker limitTracker = new ReverseLimitTracker(limits, observations);
		DatedTripCollection collection = activeTrips(
			timetable, firstServiceDate, lastServiceDate, timetable::activeServiceDay,
			overlays, limitTracker, query.cancelled());
		if (collection.cancelled()) return Result.cancelled();
		return arriveBy(query, timetable, collection.trips(), limitTracker);
	}

	Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		LocalDate firstServiceDate,
		LocalDate lastServiceDate,
		RaptorRealtimeRuntimeView realtimeRuntime,
		JourneyProfileResourcePolicy.ProfilePlanningLimits limits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(realtimeRuntime, "realtimeRuntime must not be null");
		return arriveBy(query, timetable, firstServiceDate, lastServiceDate,
			realtimeRuntime::realtimeOverlay, limits, observations);
	}

	private Result arriveBy(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<DatedScheduledTrip> activeTrips,
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
		boolean arrivalCanMeetDeadline = false;
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
				// #454: 도착역 승강장에 내리는 시각이 도착이다. 하차 간선·시간을 쓰지 않는다.
				int destinationArrival = arrivalSeconds(query, trip, alightIndex);
				if (destinationArrival > query.arrivalDeadlineSeconds()) {
					continue;
				}
				arrivalCanMeetDeadline = true;
				if (trip.realtimeOverlay().cancelled(trip.scheduledTrip())) {
					continue;
				}
				for (int boardIndex = 0; boardIndex < alightIndex; boardIndex += 1) {
					limitTracker.consumeWork();
					if (!trip.allowsPickup(boardIndex)) {
						continue;
					}
					candidates.addAll(traceToOrigin(
						query, timetable, activeTrips, trip, boardIndex, alightIndex,
						destinationArrival, 0, new HashSet<>(), limitTracker));
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
			return Result.found(frontier, query, timetable);
		}
		if (!permittedDestinationStopExists) {
			return Result.of(Outcome.NO_OD_CONNECTION);
		}
		return Result.of(arrivalCanMeetDeadline ? Outcome.NO_OD_CONNECTION : Outcome.DEADLINE_MISS);
	}

	private List<Candidate> traceToOrigin(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<DatedScheduledTrip> activeTrips,
		DatedScheduledTrip downstreamTrip,
		int downstreamBoardIndex,
		int downstreamAlightIndex,
		int arrivalAtDestinationSeconds,
		int transfersUsed,
		Set<TraceState> visiting,
		ReverseLimitTracker limits
	) {
		if (query.cancelled().getAsBoolean() || downstreamTrip.realtimeOverlay().cancelled(downstreamTrip.scheduledTrip())) {
			return List.of();
		}
		TraceState state = new TraceState(downstreamTrip, downstreamBoardIndex, downstreamAlightIndex, transfersUsed);
		if (!visiting.add(state)) {
			return List.of();
		}
		try {
			String boardStation = downstreamTrip.stopTimes().get(downstreamBoardIndex).stationId();
			int downstreamLine = timetable.lineIndex(downstreamTrip.lineId(downstreamBoardIndex));
			int downstreamDeparture = departureSeconds(query, downstreamTrip, downstreamBoardIndex);
			if (query.originStationId().equals(boardStation)) {
				// #454: 출발역 승강장에서 바로 탄다. 준비 시각 = 출발 - 승차 여유(진입 간선·시간 없음).
				int readyAt = downstreamDeparture - query.boardingSlackSeconds();
				if (readyAt < query.earliestReadyAtSeconds()) return List.of();
				Candidate candidate = new Candidate(readyAt, arrivalAtDestinationSeconds, transfersUsed,
					0, 0, 0L, new JourneyProfileRaptorPort.NoTransfer(), List.of(
						new TraceRide(downstreamTrip, downstreamBoardIndex, downstreamAlightIndex)));
				return limits.admit(candidate);
			}
			if (transfersUsed >= query.maxTransfers()) {
				return List.of();
			}

			List<Candidate> candidates = new ArrayList<>();
			int station = timetable.stationIndex(boardStation);
			RouteTimetableRaptorPlanner.OutOfStationFootpath[] footpaths = timetable.footpathsToStationLine(station, downstreamLine);
			boolean hasFootpaths = footpaths != null;
			Set<String> candidateStationIds = null;
			if (hasFootpaths) {
				candidateStationIds = new HashSet<>(footpaths.length + 1);
				candidateStationIds.add(boardStation);
				for (RouteTimetableRaptorPlanner.OutOfStationFootpath fp : footpaths) {
					candidateStationIds.add(timetable.stationId(fp.fromStation()));
				}
			}
			for (DatedScheduledTrip upstreamTrip : activeTrips) {
				limits.consumeWork();
				if (query.cancelled().getAsBoolean() || upstreamTrip.realtimeOverlay().cancelled(upstreamTrip.scheduledTrip())) {
					continue;
				}
				for (int upstreamAlightIndex = 1; upstreamAlightIndex < upstreamTrip.stopTimes().size(); upstreamAlightIndex += 1) {
					limits.consumeWork();
					if (!upstreamTrip.allowsDropOff(upstreamAlightIndex)) {
						continue;
					}
					String upstreamStationId = upstreamTrip.stopTimes().get(upstreamAlightIndex).stationId();
					if (hasFootpaths ? !candidateStationIds.contains(upstreamStationId) : !boardStation.equals(upstreamStationId)) {
						continue;
					}
					int upstreamStation = boardStation.equals(upstreamStationId) ? station : timetable.stationIndex(upstreamStationId);
					int upstreamLine = timetable.lineIndex(upstreamTrip.lineId(upstreamAlightIndex));
					RouteTimetableRaptorPlanner.RealtimeOverlay transferOverlay = RouteTimetableRaptorPlanner.RealtimeOverlay.combine(
						upstreamTrip.realtimeOverlay(), downstreamTrip.realtimeOverlay());
					TransferEvaluation eval = evaluateTransfer(
						timetable, query, station, boardStation, downstreamLine,
						upstreamStation, upstreamStationId, upstreamLine, footpaths, transferOverlay);
					if (eval.match() == null) {
						if (eval.hasOpportunity()) {
							limits.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
						}
						continue;
					}
					TransferMatch match = eval.match();
					int transfer = match.transition();
					String fromStationId = match.fromStationId();
					int latestArrival = downstreamDeparture - transferSeconds(query, timetable, transfer)
						- query.boardingSlackSeconds();
					if (arrivalSeconds(query, upstreamTrip, upstreamAlightIndex) > latestArrival) {
						continue;
					}
					for (int upstreamBoardIndex = 0; upstreamBoardIndex < upstreamAlightIndex; upstreamBoardIndex += 1) {
						limits.consumeWork();
						if (!upstreamTrip.allowsPickup(upstreamBoardIndex)) {
							continue;
						}
						List<Candidate> upstream = traceToOrigin(
							query, timetable, activeTrips, upstreamTrip, upstreamBoardIndex, upstreamAlightIndex,
							arrivalAtDestinationSeconds, transfersUsed + 1, visiting, limits);
						for (Candidate candidate : upstream) {
							long transferSlack = (long) downstreamDeparture
								- arrivalSeconds(query, upstreamTrip, upstreamAlightIndex)
								- transferSeconds(query, timetable, transfer)
								- query.boardingSlackSeconds();
							candidates.add(candidate.appendTransferAndRide(
								new TraceAccess(transfer, fromStationId, boardStation),
								transferSeconds(query, timetable, transfer),
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

	record TransferMatch(int transition, String fromStationId) {
	}

	record TransferEvaluation(TransferMatch match, boolean hasOpportunity) {
		static final TransferEvaluation NONE = new TransferEvaluation(null, false);
		static final TransferEvaluation INELIGIBLE = new TransferEvaluation(null, true);

		static TransferEvaluation of(TransferMatch match) {
			return new TransferEvaluation(match, true);
		}
	}

	static int selectTransferTransition(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		int[] candidates,
		Query query,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		if (candidates.length == 0) {
			return -1;
		}
		int transfer = timetable.selectTransition(candidates, query.accessProfileBit(), false, true, realtimeOverlay);
		if (transfer < 0 && !query.requiresVerifiedJourneyDistance()) {
			transfer = timetable.selectTransition(candidates, query.accessProfileBit(), false, false, realtimeOverlay);
		}
		return transfer;
	}

	static int selectTransferTransition(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		int[] candidates,
		Query query
	) {
		return selectTransferTransition(timetable, candidates, query, RouteTimetableRaptorPlanner.RealtimeOverlay.empty());
	}

	private static TransferMatch findBestFootpath(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		Query query,
		int upstreamStation,
		String upstreamStationId,
		int upstreamLine,
		RouteTimetableRaptorPlanner.OutOfStationFootpath[] footpaths,
		boolean preferStepFree,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		if (footpaths == null) {
			return null;
		}
		TransferMatch bestMatch = null;
		for (RouteTimetableRaptorPlanner.OutOfStationFootpath fp : footpaths) {
			if (fp.fromStation() == upstreamStation && fp.fromLine() == upstreamLine) {
				int fpTransfer = selectTransferTransition(timetable, fp.candidateTransitions(), query, realtimeOverlay);
				if (verifiedTransition(timetable, fpTransfer)) {
					boolean fpHasStairs = timetable.transitionIncludesStairs(fpTransfer);
					if (bestMatch == null) {
						bestMatch = new TransferMatch(fpTransfer, upstreamStationId);
						if (!preferStepFree || !fpHasStairs) {
							break;
						}
					} else if (!fpHasStairs) {
						bestMatch = new TransferMatch(fpTransfer, upstreamStationId);
						break;
					}
				}
			}
		}
		return bestMatch;
	}

	static TransferEvaluation evaluateTransfer(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		Query query,
		int station,
		String boardStation,
		int downstreamLine,
		int upstreamStation,
		String upstreamStationId,
		int upstreamLine,
		RouteTimetableRaptorPlanner.OutOfStationFootpath[] footpaths,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		if (station < 0 || downstreamLine < 0 || upstreamStation < 0 || upstreamLine < 0) {
			return TransferEvaluation.NONE;
		}
		boolean preferStepFree = RouteTimetableRaptorPlanner.prefersStepFree(query.accessProfileBit());
		if (upstreamStation == station) {
			int inTransfer = selectTransferTransition(
				timetable, timetable.transferTransitions(station, upstreamLine, downstreamLine), query, realtimeOverlay);
			boolean inVerified = verifiedTransition(timetable, inTransfer);

			TransferMatch bestFootpath = findBestFootpath(
				timetable, query, upstreamStation, upstreamStationId, upstreamLine, footpaths, preferStepFree, realtimeOverlay);

			if (preferStepFree) {
				if (inVerified && !timetable.transitionIncludesStairs(inTransfer)) {
					return TransferEvaluation.of(new TransferMatch(inTransfer, boardStation));
				}
				if (bestFootpath != null && !timetable.transitionIncludesStairs(bestFootpath.transition())) {
					return TransferEvaluation.of(bestFootpath);
				}
			}

			if (inVerified) {
				return TransferEvaluation.of(new TransferMatch(inTransfer, boardStation));
			}
			if (bestFootpath != null) {
				return TransferEvaluation.of(bestFootpath);
			}
			return TransferEvaluation.INELIGIBLE;
		}

		if (footpaths != null) {
			boolean hasOpportunity = false;
			for (RouteTimetableRaptorPlanner.OutOfStationFootpath fp : footpaths) {
				if (fp.fromStation() == upstreamStation && fp.fromLine() == upstreamLine) {
					hasOpportunity = true;
					break;
				}
			}
			if (hasOpportunity) {
				TransferMatch bestMatch = findBestFootpath(
					timetable, query, upstreamStation, upstreamStationId, upstreamLine, footpaths, preferStepFree, realtimeOverlay);
				if (bestMatch != null) {
					return TransferEvaluation.of(bestMatch);
				}
				return TransferEvaluation.INELIGIBLE;
			}
		}

		return TransferEvaluation.NONE;
	}

	static TransferEvaluation evaluateTransfer(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		Query query,
		int station,
		String boardStation,
		int downstreamLine,
		int upstreamStation,
		String upstreamStationId,
		int upstreamLine,
		RouteTimetableRaptorPlanner.OutOfStationFootpath[] footpaths
	) {
		return evaluateTransfer(
			timetable, query, station, boardStation, downstreamLine,
			upstreamStation, upstreamStationId, upstreamLine, footpaths,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty());
	}

	private static Integer terminalDeadline(
		LastConnectionQuery query,
		List<DatedScheduledTrip> activeTrips,
		ReverseLimitTracker limits
	) {
		Integer latest = null;
		for (DatedScheduledTrip trip : activeTrips) {
			limits.consumeWork();
			if (query.cancelled().getAsBoolean()) {
				return null;
			}
			if (trip.realtimeOverlay().cancelled(trip.scheduledTrip())) {
				continue;
			}
			for (int alightIndex = 1; alightIndex < trip.stopTimes().size(); alightIndex += 1) {
				limits.consumeWork();
				if (!query.destinationStationId().equals(trip.stopTimes().get(alightIndex).stationId())
					|| !trip.allowsDropOff(alightIndex)) {
					continue;
				}
				// #454: 도착역 승강장에 내리는 시각이 도착이다(하차 시간 없음).
				int arrivalAtDestination = arrivalSeconds(query.serviceDate(), trip, alightIndex);
				if (latest == null || arrivalAtDestination > latest) {
					latest = arrivalAtDestination;
				}
			}
		}
		return latest;
	}

	private static DatedTripCollection activeTrips(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		LocalDate firstServiceDate,
		LocalDate lastServiceDate,
		Function<LocalDate, RouteTimetableRaptorPlanner.ActiveServiceDay> activeDays,
		Function<LocalDate, RouteTimetableRaptorPlanner.RealtimeOverlay> overlays,
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
					if (seen.add(trip.index())) trips.add(new DatedScheduledTrip(
						serviceDate, trip, Objects.requireNonNull(overlays.apply(serviceDate), "realtime overlay must not be null")));
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

	private static int transferSeconds(
		Query query, RouteTimetableRaptorPlanner.CompiledTimetable timetable, int transition
	) {
		int baseline = timetable.transitionDurationSeconds(transition);
		if (query.requiresVerifiedJourneyDistance()) {
			return RouteTimetableRaptorPlanner.verifiedTransferSeconds(baseline,
				timetable.transitionDistanceMeters(transition), query.walkingSpeedMetersPerHour(), query.mobilityPreset());
		}
		return ProfileWalkTimeCalculator.estimateSeconds(
			baseline, query.mobilityPreset(), WalkTimeSource.OFFICIAL_BASELINE, false).seconds();
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
		Candidate candidate
	) {
		List<RouteTimetableRaptorPlanner.JourneyLegProjection> legs = projectLegs(query, timetable, candidate.legs());
		// #454: 여정은 첫 승차로 시작해 마지막 승차로 끝난다(진입·하차 구간 없음).
		TraceRide firstRide = (TraceRide) candidate.legs().getFirst();
		TraceRide lastRide = (TraceRide) candidate.legs().getLast();
		int plannedReadyAt = serviceDateOffsetSeconds(query.serviceDate(), firstRide.trip().serviceDate())
			+ firstRide.trip().departureSeconds(firstRide.boardIndex())
			- query.boardingSlackSeconds();
		int plannedArrivalAtDestination = serviceDateOffsetSeconds(query.serviceDate(), lastRide.trip().serviceDate())
			+ lastRide.trip().arrivalSeconds(lastRide.alightIndex());
		return new RouteTimetableRaptorPlanner.JourneyItinerary(
			query.serviceDate(),
			serviceInstant(query.serviceDate(), plannedReadyAt),
			serviceInstant(query.serviceDate(), plannedArrivalAtDestination),
			firstRide.trip().realtimeOverlay().available()
				? serviceInstant(query.serviceDate(), candidate.readyAtSeconds()) : null,
			lastRide.trip().realtimeOverlay().available()
				? serviceInstant(query.serviceDate(), candidate.arrivalAtDestinationSeconds()) : null,
			RouteTimetableRaptorPlanner.itineraryMetrics(legs, query.boardingSlackSeconds()),
			legs
		);
	}

	private static List<RouteTimetableRaptorPlanner.JourneyLegProjection> projectLegs(
		Query query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		List<TraceLeg> legs
	) {
		List<RouteTimetableRaptorPlanner.JourneyLegProjection> projected = new ArrayList<>(legs.size());
		for (int index = 0; index < legs.size(); index += 1) {
			TraceLeg leg = legs.get(index);
			if (leg instanceof TraceAccess access) {
				String transferType = null;
				Boolean farePenaltyApplies = null;
				Integer transferLimitMinutes = null;
				if (timetable.isOutOfStationTransition(access.transition())) {
					transferType = "OUT_OF_STATION";
					TraceRide previous = (TraceRide) legs.get(index - 1);
					TraceRide next = (TraceRide) legs.get(index + 1);
					int alightSeconds = arrivalSeconds(query, previous.trip(), previous.alightIndex());
					int boardSeconds = departureSeconds(query, next.trip(), next.boardIndex());
					int elapsed = boardSeconds - alightSeconds;
					int limit = RouteTimetableRaptorPlanner.getTransferLimitSeconds(alightSeconds, boardSeconds);
					boolean timeout = elapsed > limit;
					farePenaltyApplies = timeout;
					transferLimitMinutes = limit / 60;
				}
				projected.add(new RouteTimetableRaptorPlanner.JourneyAccessProjection(
					RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER,
					access.fromStationId(), access.toStationId(),
					transferSeconds(query, timetable, access.transition()),
					timetable.transitionDistanceMeters(access.transition()),
					timetable.transitionIncludesStairs(access.transition()),
					timetable.transitionVerified(access.transition()),
					timetable.transitionVerificationStatus(access.transition()),
					transferType,
					farePenaltyApplies,
					transferLimitMinutes
				));
			} else {
				TraceRide ride = (TraceRide) leg;
				RouteTimetableRaptorPlanner.RealtimeOverlay rideOverlay = ride.trip().realtimeOverlay();
				boolean hasRealtimeEvidence = rideOverlay.evidence(ride.trip().scheduledTrip()) != null;
				boolean nextIsTransfer = index + 1 < legs.size();
				boolean stepFree = (query.mobilityPreset() == com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset.STEP_FREE);
				List<RouteTimetableRaptorPlanner.AlightingCarDoor> alightingCarDoors = timetable.selectAlightingCarDoors(
					ride.trip().stopTimes().get(ride.alightIndex()).stationId(),
					ride.trip().scheduledTrip().route().lineId(),
					ride.trip().scheduledTrip().trip().directionId(),
					nextIsTransfer,
					stepFree
				);
				List<RouteTimetableRaptorPlanner.JourneyStopProjection> stops = new ArrayList<>();
				for (int i = ride.boardIndex(); i <= ride.alightIndex(); i++) {
					if (i == ride.boardIndex() || i == ride.alightIndex()
							|| ride.trip().allowsPickup(i) || ride.trip().allowsDropOff(i)) {
						String stationId = ride.trip().stopTimes().get(i).stationId();
						Instant plannedArr = i == ride.boardIndex() ? null
							: serviceInstant(ride.trip().serviceDate(), ride.trip().arrivalSeconds(i));
						Instant plannedDep = i == ride.alightIndex() ? null
							: serviceInstant(ride.trip().serviceDate(), ride.trip().departureSeconds(i));
						Instant realtimeArr = !hasRealtimeEvidence || i == ride.boardIndex() ? null
							: serviceInstant(ride.trip().serviceDate(), rideOverlay.arrivalSeconds(ride.trip().scheduledTrip(), i));
						Instant realtimeDep = !hasRealtimeEvidence || i == ride.alightIndex() ? null
							: serviceInstant(ride.trip().serviceDate(), rideOverlay.departureSeconds(ride.trip().scheduledTrip(), i));
						stops.add(new RouteTimetableRaptorPlanner.JourneyStopProjection(
							stationId, plannedArr, plannedDep, realtimeArr, realtimeDep
						));
					}
				}
				projected.add(new RouteTimetableRaptorPlanner.JourneyRideProjection(
					ride.trip().scheduledTrip().route().lineId(),
					ride.trip().scheduledTrip().trip().id(),
					ride.trip().stopTimes().getLast().stationId(),
					ride.trip().stopTimes().get(ride.boardIndex()).stationId(),
					ride.trip().stopTimes().get(ride.alightIndex()).stationId(),
					ride.trip().scheduledTrip().trip().servicePattern(),
					serviceInstant(ride.trip().serviceDate(), ride.trip().departureSeconds(ride.boardIndex())),
					serviceInstant(ride.trip().serviceDate(), ride.trip().arrivalSeconds(ride.alightIndex())),
					!hasRealtimeEvidence ? null : serviceInstant(ride.trip().serviceDate(),
						rideOverlay.departureSeconds(ride.trip().scheduledTrip(), ride.boardIndex())),
					!hasRealtimeEvidence ? null : serviceInstant(ride.trip().serviceDate(),
						rideOverlay.arrivalSeconds(ride.trip().scheduledTrip(), ride.alightIndex())),
					List.copyOf(stops),
					alightingCarDoors,
					timetable.platformGaps(
						ride.trip().stopTimes().get(ride.boardIndex()).stationId(),
						ride.trip().scheduledTrip().route().lineId(),
						ride.trip().scheduledTrip().trip().directionId()),
					timetable.platformGaps(
						ride.trip().stopTimes().get(ride.alightIndex()).stationId(),
						ride.trip().scheduledTrip().route().lineId(),
						ride.trip().scheduledTrip().trip().directionId())
				));
			}
		}
		return List.copyOf(projected);
	}

	private static Instant serviceInstant(LocalDate serviceDate, int serviceSeconds) {
		return serviceDate.atStartOfDay(ServiceDayResolver.ZONE).plusSeconds(serviceSeconds).toInstant();
	}

	private static int departureSeconds(
		Query query,
		DatedScheduledTrip trip,
		int stopIndex
	) {
		return departureSeconds(query.serviceDate(), trip, stopIndex);
	}

	private static int departureSeconds(
		LocalDate anchorServiceDate,
		DatedScheduledTrip trip,
		int stopIndex
	) {
		return Math.addExact(serviceDateOffsetSeconds(anchorServiceDate, trip.serviceDate()),
			trip.realtimeOverlay().departureSeconds(trip.scheduledTrip(), stopIndex));
	}

	private static int arrivalSeconds(
		Query query,
		DatedScheduledTrip trip,
		int stopIndex
	) {
		return arrivalSeconds(query.serviceDate(), trip, stopIndex);
	}

	private static int arrivalSeconds(
		LocalDate anchorServiceDate,
		DatedScheduledTrip trip,
		int stopIndex
	) {
		return Math.addExact(serviceDateOffsetSeconds(anchorServiceDate, trip.serviceDate()),
			trip.realtimeOverlay().arrivalSeconds(trip.scheduledTrip(), stopIndex));
	}

	private static int serviceDateOffsetSeconds(LocalDate anchorServiceDate, LocalDate serviceDate) {
		return Math.toIntExact(java.time.Duration.between(
			anchorServiceDate.atStartOfDay(ServiceDayResolver.ZONE), serviceDate.atStartOfDay(ServiceDayResolver.ZONE)).toSeconds());
	}

	enum Outcome {
		FOUND,
		NO_ACTIVE_SERVICE,
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

	record LastConnectionPreparation(Outcome outcome, Integer terminalArrivalAtDestinationSeconds) {
		LastConnectionPreparation {
			outcome = Objects.requireNonNull(outcome, "outcome");
			if (outcome == Outcome.FOUND && terminalArrivalAtDestinationSeconds == null
				|| outcome != Outcome.FOUND && terminalArrivalAtDestinationSeconds != null) {
				throw new IllegalArgumentException("terminal arrival must match preparation outcome");
			}
		}
	}

	private record PreparedLastConnection(
		Outcome outcome,
		Integer terminalArrivalAtDestinationSeconds,
		List<DatedScheduledTrip> activeTrips,
		ReverseLimitTracker limitTracker
	) {
		private PreparedLastConnection {
			outcome = Objects.requireNonNull(outcome, "outcome");
			limitTracker = Objects.requireNonNull(limitTracker, "limitTracker");
			if (outcome == Outcome.FOUND) {
				Objects.requireNonNull(terminalArrivalAtDestinationSeconds, "found preparation needs terminal arrival");
				activeTrips = List.copyOf(Objects.requireNonNull(activeTrips, "found preparation needs active trips"));
			} else if (terminalArrivalAtDestinationSeconds != null || activeTrips != null && !activeTrips.isEmpty()) {
				throw new IllegalArgumentException("terminal failure must not retain active trips or terminal arrival");
			} else {
				activeTrips = List.of();
			}
		}

		static PreparedLastConnection terminal(Outcome outcome, ReverseLimitTracker limitTracker) {
			return new PreparedLastConnection(outcome, null, List.of(), limitTracker);
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
			RouteTimetableRaptorPlanner.CompiledTimetable timetable
		) {
			List<RouteTimetableRaptorPlanner.JourneyItinerary> itineraries = candidates.stream()
				.map(candidate -> toItinerary(query, timetable, candidate)).toList();
			Candidate latest = candidates.stream().min(Comparator.comparingInt(Candidate::readyAtSeconds).reversed()
				.thenComparing(ReverseTimetableRaptorPlanner::compareTrace)).orElseThrow();
			int itineraryIndex = candidates.indexOf(latest);
			return new Result(Outcome.FOUND, latest.readyAtSeconds(), latest.arrivalAtDestinationSeconds(),
				latest.transfersUsed(), itineraries.get(itineraryIndex), itineraries);
		}
	}

	private sealed interface TraceLeg permits TraceAccess, TraceRide {
	}

	/** #454: 승차 사이의 환승 이동. 출발·도착은 승강장이라 진입·하차 이동은 없다. */
	private record TraceAccess(int transition, String fromStationId, String toStationId)
		implements TraceLeg {
	}

	private record DatedScheduledTrip(
		LocalDate serviceDate,
		RouteTimetableRaptorPlanner.ScheduledTrip scheduledTrip,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
		private DatedScheduledTrip {
			serviceDate = Objects.requireNonNull(serviceDate, "serviceDate");
			scheduledTrip = Objects.requireNonNull(scheduledTrip, "scheduledTrip");
			realtimeOverlay = Objects.requireNonNull(realtimeOverlay, "realtimeOverlay");
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
