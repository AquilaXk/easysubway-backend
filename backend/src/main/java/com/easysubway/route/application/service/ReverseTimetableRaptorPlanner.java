package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.ServiceDayResolver;
import com.easysubway.route.domain.ProfileWalkTimeCalculator;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.MobilityPreset;
import com.easysubway.route.domain.ProfileWalkTimeCalculator.WalkTimeSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
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
			timetable, preparation.trips(), preparation.limitTracker());
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
		ReverseTrips trips = ReverseTrips.of(query.serviceDate(), query.serviceDate(), query.serviceDate(),
			ignored -> activeServiceDay, overlays, limitTracker, query.cancelled());
		if (trips == null) return PreparedLastConnection.terminal(Outcome.CANCELLED, limitTracker);
		if (trips.noActiveService()) return PreparedLastConnection.terminal(Outcome.NO_ACTIVE_SERVICE, limitTracker);

		Integer terminalDeadline = terminalDeadline(query, timetable, trips, limitTracker);
		if (query.cancelled().getAsBoolean()) return PreparedLastConnection.terminal(Outcome.CANCELLED, limitTracker);
		if (terminalDeadline != null) {
			return new PreparedLastConnection(Outcome.FOUND, terminalDeadline, trips, limitTracker);
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
		ReverseTrips trips = ReverseTrips.of(query.serviceDate(), query.serviceDate(), query.serviceDate(),
			ignored -> activeServiceDay, ignored -> realtimeOverlay, limitTracker, query.cancelled());
		if (trips == null) return Result.cancelled();
		return arriveBy(query, timetable, trips, limitTracker);
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
		ReverseTrips trips = ReverseTrips.of(query.serviceDate(), firstServiceDate, lastServiceDate,
			timetable::activeServiceDay, overlays, limitTracker, query.cancelled());
		if (trips == null) return Result.cancelled();
		return arriveBy(query, timetable, trips, limitTracker);
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
		ReverseTrips trips,
		ReverseLimitTracker limitTracker
	) {
		if (query.cancelled().getAsBoolean()) {
			return Result.cancelled();
		}
		if (trips.noActiveService()) {
			return Result.of(Outcome.NO_ACTIVE_SERVICE);
		}
		int origin = timetable.stationIndex(query.originStationId());
		int destination = timetable.stationIndex(query.destinationStationId());
		if (origin < 0 || destination < 0) {
			return Result.of(Outcome.NO_OD_CONNECTION);
		}
		return new WindowedReverseSearch(query, timetable, trips, limitTracker, origin, destination).run();
	}

	/**
	 * #461: 출발역에서 (역, 승차 노선)으로 타기 전까지 필요한 최소 승차 수. 시각을 보지 않는 완화이고 환승 적격성은
	 * 역방향 탐색과 같은 {@link #evaluateTransfer}로 판정한다(실시간 차단은 선택지를 줄이기만 하므로 빼도 완화다).
	 * 결과는 {@code station * lineCount + line} 색인이고 {@code maxRides}를 넘으면 도달 불가 값이다.
	 */
	static int[] ridesBeforeBoarding(
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		Query query,
		int origin,
		int maxRides
	) {
		int lines = timetable.lineCount();
		int stations = timetable.stationCount();
		int[] before = new int[stations * lines];
		java.util.Arrays.fill(before, RouteTimetableRaptorPlanner.UNREACHABLE_BOARDINGS);
		if (origin < 0) return before;
		for (int pattern : timetable.patternsByStop(origin)) {
			int[] stops = timetable.stopsByPattern(pattern);
			RouteTimetableRaptorPlanner.ScheduledTrip representative = timetable.patternRepresentative(pattern);
			for (int position = 0; position < stops.length; position += 1) {
				int line = timetable.lineIndex(representative.lineId(position));
				if (stops[position] == origin && representative.allowsPickup(position) && line >= 0) {
					before[origin * lines + line] = 0;
				}
			}
		}
		RouteTimetableRaptorPlanner.RealtimeOverlay noBlocking = RouteTimetableRaptorPlanner.RealtimeOverlay.empty();
		for (int rides = 1; rides <= maxRides; rides += 1) {
			boolean[] alighted = new boolean[stations * lines];
			for (int pattern = 0; pattern < timetable.routePatternCount(); pattern += 1) {
				int[] stops = timetable.stopsByPattern(pattern);
				RouteTimetableRaptorPlanner.ScheduledTrip representative = timetable.patternRepresentative(pattern);
				boolean boarded = false;
				for (int position = 0; position < stops.length; position += 1) {
					int line = timetable.lineIndex(representative.lineId(position));
					if (line < 0) continue;
					if (boarded && representative.allowsDropOff(position)) alighted[stops[position] * lines + line] = true;
					if (representative.allowsPickup(position) && before[stops[position] * lines + line] <= rides - 1) {
						boarded = true;
					}
				}
			}
			int[] next = before.clone();
			for (int upstreamStation = 0; upstreamStation < stations; upstreamStation += 1) {
				String upstreamStationId = timetable.stationId(upstreamStation);
				RouteTimetableRaptorPlanner.OutOfStationFootpath[] outgoing = timetable.footpathsFromStation(upstreamStation);
				for (int upstreamLine = 0; upstreamLine < lines; upstreamLine += 1) {
					if (!alighted[upstreamStation * lines + upstreamLine]) continue;
					for (int local = 0; local < timetable.stationLineCount(upstreamStation); local += 1) {
						int line = timetable.stationLine(upstreamStation, local);
						int index = upstreamStation * lines + line;
						if (next[index] <= rides) continue;
						if (evaluateTransfer(timetable, query, upstreamStation, upstreamStationId, line, upstreamStation,
							upstreamStationId, upstreamLine, timetable.footpathsToStationLine(upstreamStation, line),
							noBlocking).match() != null) {
							next[index] = rides;
						}
					}
					if (outgoing == null) continue;
					for (RouteTimetableRaptorPlanner.OutOfStationFootpath footpath : outgoing) {
						if (footpath.fromLine() != upstreamLine) continue;
						int index = footpath.toStation() * lines + footpath.toLine();
						if (next[index] <= rides) continue;
						if (evaluateTransfer(timetable, query, footpath.toStation(), timetable.stationId(footpath.toStation()),
							footpath.toLine(), upstreamStation, upstreamStationId, upstreamLine,
							timetable.footpathsToStationLine(footpath.toStation(), footpath.toLine()), noBlocking).match() != null) {
							next[index] = rides;
						}
					}
				}
			}
			before = next;
		}
		return before;
	}

	/**
	 * #461: 도착역에서 출발역 쪽으로 넓히는 다기준 라벨 탐색.
	 *
	 * <p>상태는 (뒤쪽 환승 수, 승차역, 승차 노선)이고 라벨은 그 승차부터 도착역까지의 뒷부분 여정이다. 같은 상태에서
	 * 출발이 늦지 않고 도착·환승 보행·거리·계단이 나쁘지 않으며 환승 여유가 작지 않은 라벨은 앞쪽으로 어떻게 이어도
	 * 같거나 나은 여정을 만든다(앞쪽 환승 동선 선택은 상태만으로 정해진다). 그래서 상태마다 지배되지 않는 라벨만 남긴다.</p>
	 *
	 * <p>라벨은 가능한 준비 시각의 상한({@code 승차 출발 - 출발역에서 승차역까지 최소 주행 - 승차 여유})이 큰 것부터
	 * 꺼낸다. 대안 창 시작은 {@code max(가장 이른 준비 시각, 지금까지 찾은 가장 늦은 준비 시각 - 창 길이)}이고 늘기만
	 * 한다. 꺼낸 상한이 창 시작보다 작으면 남은 라벨도 모두 창 밖이므로 탐색을 끝낸다.</p>
	 */
	private static final class WindowedReverseSearch {
		private static final long NO_TRANSFER_SLACK = Long.MAX_VALUE;
		private static final Comparator<QueueEntry> QUEUE_ORDER = Comparator
			.comparingLong(QueueEntry::potential).reversed()
			.thenComparingLong(QueueEntry::sequence);

		private final Query query;
		private final RouteTimetableRaptorPlanner.CompiledTimetable timetable;
		private final ReverseTrips trips;
		private final ReverseLimitTracker limits;
		private final int origin;
		private final int destination;
		private final int[] fromOrigin;
		/** 출발역에서 (역, 승차 노선)으로 타기 전까지 필요한 최소 승차 수({@link #ridesBeforeBoarding}). */
		private final int[] ridesBefore;
		/** 패턴·하차 위치마다 그 앞 승차 위치들의 최소 선행 승차 수(출발역 승차는 0). */
		private final int[][] ridesBeforeAlight;
		private final PriorityQueue<QueueEntry> queue = new PriorityQueue<>(QUEUE_ORDER);
		private final Map<ReverseStateKey, List<ReverseLabel>> labelsByState = new HashMap<>();
		private final List<Candidate> candidates = new ArrayList<>();
		private long sequence;
		private long latestReadyAt = Long.MIN_VALUE;
		private long windowStart;

		private WindowedReverseSearch(
			Query query,
			RouteTimetableRaptorPlanner.CompiledTimetable timetable,
			ReverseTrips trips,
			ReverseLimitTracker limits,
			int origin,
			int destination
		) {
			this.query = query;
			this.timetable = timetable;
			this.trips = trips;
			this.limits = limits;
			this.origin = origin;
			this.destination = destination;
			this.fromOrigin = RouteTimetableRaptorPlanner.computeProfileLowerBoundsFrom(timetable, origin, trips.overlays());
			this.ridesBeforeAlight = new int[timetable.routePatternCount()][];
			this.windowStart = query.earliestReadyAtSeconds();
			this.ridesBefore = ridesBeforeBoarding(timetable, query, origin, query.maxTransfers());
		}

		private int ridesBefore(int station, int line) {
			if (station == origin) return 0;
			return line < 0 ? RouteTimetableRaptorPlanner.UNREACHABLE_BOARDINGS
				: ridesBefore[station * timetable.lineCount() + line];
		}

		/** 이 패턴의 alight 위치에서 내리는 승차 중 출발역에서 가장 적은 선행 승차로 닿는 값. */
		private int ridesBeforeAlight(int pattern, int alight) {
			int[] values = ridesBeforeAlight[pattern];
			if (values == null) {
				int[] stops = timetable.stopsByPattern(pattern);
				RouteTimetableRaptorPlanner.ScheduledTrip representative = timetable.patternRepresentative(pattern);
				int[] result = new int[stops.length];
				int best = RouteTimetableRaptorPlanner.UNREACHABLE_BOARDINGS;
				for (int position = 0; position < stops.length; position += 1) {
					result[position] = best;
					if (representative.allowsPickup(position)) {
						best = Math.min(best, ridesBefore(stops[position],
							timetable.lineIndex(representative.lineId(position))));
					}
				}
				values = result;
				ridesBeforeAlight[pattern] = values;
			}
			return values[alight];
		}

		private Result run() {
			boolean permittedDestinationStopExists = false;
			boolean arrivalCanMeetDeadline = false;
			for (int pattern : timetable.patternsByStop(destination)) {
				if (query.cancelled().getAsBoolean()) return Result.cancelled();
				limits.consumeWork();
				ReversePatternTrips patternTrips = trips.pattern(pattern);
				if (patternTrips.isEmpty()) continue;
				int[] stops = timetable.stopsByPattern(pattern);
				for (int alight = 1; alight < stops.length; alight += 1) {
					if (stops[alight] != destination) continue;
					limits.consumeWork();
					// 같은 패턴의 열차는 정차역마다 승하차 허용이 같다(패턴 키에 포함).
					if (!patternTrips.first().allowsDropOff(alight)) continue;
					permittedDestinationStopExists = true;
					boolean withinBudget = ridesBeforeAlight(pattern, alight) <= query.maxTransfers();
					if (!withinBudget) limits.count(JourneyRaptorPruningInventoryV1.PROFILE_TRANSFER_BUDGET);
					for (int block = 0; block < patternTrips.blockCount(); block += 1) {
						int start = patternTrips.blockStart(block);
						int end = patternTrips.blockEnd(block);
						if (patternTrips.ordered(block)) {
							int last = patternTrips.lastArrivingAtOrBefore(start, end, alight, query.arrivalDeadlineSeconds());
							if (last >= start) {
								arrivalCanMeetDeadline = true;
								if (withinBudget) pushSeed(new Seed(pattern, alight, block, last, true));
							}
						} else {
							for (int index = start; index < end; index += 1) {
								limits.consumeWork();
								if (patternTrips.arrivalSeconds(index, alight) > query.arrivalDeadlineSeconds()) continue;
								arrivalCanMeetDeadline = true;
								if (withinBudget) pushSeed(new Seed(pattern, alight, block, index, false));
							}
						}
					}
				}
			}
			while (!queue.isEmpty()) {
				if (query.cancelled().getAsBoolean()) return Result.cancelled();
				QueueEntry entry = queue.poll();
				if (entry.potential() < windowStart) {
					limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
					break;
				}
				if (entry.seed() != null) {
					expandSeed(entry.seed());
				} else if (isCurrent(entry.label())) {
					expand(entry.label());
				}
			}
			if (candidates.isEmpty()) {
				if (!permittedDestinationStopExists) return Result.of(Outcome.NO_OD_CONNECTION);
				return Result.of(arrivalCanMeetDeadline ? Outcome.NO_OD_CONNECTION : Outcome.DEADLINE_MISS);
			}
			long threshold = Math.max(query.earliestReadyAtSeconds(),
				latestReadyAt - JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW_SECONDS);
			List<Candidate> windowed = new ArrayList<>(candidates.size());
			for (Candidate candidate : candidates) {
				if (candidate.readyAtSeconds() >= threshold) windowed.add(candidate);
				else limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
			}
			List<Candidate> frontier = destinationFrontier(windowed, limits);
			limits.observeDestinationLabels(frontier.size());
			if (frontier.size() > limits.maxDestinationProfileLabels()) {
				limits.count("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
				throw new ReversePlanningLimitException(PlanningLimit.MAX_DESTINATION_PROFILE_LABELS,
					frontier.size(), limits.maxDestinationProfileLabels());
			}
			return Result.found(frontier, query, timetable);
		}

		/** 도착역에 내리는 열차 하나. 순서가 보장된 묶음은 한 번에 하나씩, 더 이른 열차를 다음 씨앗으로 넣는다. */
		private void pushSeed(Seed seed) {
			ReversePatternTrips patternTrips = trips.pattern(seed.pattern());
			// 이 열차에서 만들 라벨의 준비 시각 상한: 도착 - 출발역에서 도착역까지 최소 주행 - 승차 여유.
			long potential = (long) patternTrips.arrivalSeconds(seed.index(), seed.alight())
				- fromOrigin[destination] - query.boardingSlackSeconds();
			queue.add(new QueueEntry(potential, sequence++, seed, null));
		}

		private void expandSeed(Seed seed) {
			limits.consumeWork();
			ReversePatternTrips patternTrips = trips.pattern(seed.pattern());
			if (seed.cursor() && seed.index() - 1 >= patternTrips.blockStart(seed.block())) {
				pushSeed(new Seed(seed.pattern(), seed.alight(), seed.block(), seed.index() - 1, true));
			}
			DatedScheduledTrip trip = patternTrips.trip(seed.index());
			if (trip.realtimeOverlay().cancelled(trip.scheduledTrip())) return;
			// #454: 도착역 승강장에 내리는 시각이 도착이다. 하차 간선·시간을 쓰지 않는다.
			int arrival = patternTrips.arrivalSeconds(seed.index(), seed.alight());
			int[] stops = timetable.stopsByPattern(seed.pattern());
			for (int board = 0; board < seed.alight(); board += 1) {
				limits.consumeWork();
				if (!trip.allowsPickup(board)) continue;
				offer(0, patternTrips, seed.index(), stops, board, seed.alight(), arrival,
					0L, 0L, 0L, NO_TRANSFER_SLACK, null, null);
			}
		}

		private void expand(ReverseLabel label) {
			int station = label.station();
			String stationId = timetable.stationId(station);
			RouteTimetableRaptorPlanner.OutOfStationFootpath[] footpaths =
				timetable.footpathsToStationLine(station, label.line());
			List<Integer> upstreamStations = new ArrayList<>();
			upstreamStations.add(station);
			if (footpaths != null) {
				for (RouteTimetableRaptorPlanner.OutOfStationFootpath footpath : footpaths) {
					if (!upstreamStations.contains(footpath.fromStation())) upstreamStations.add(footpath.fromStation());
				}
			}
			for (int upstreamStation : upstreamStations) {
				String upstreamStationId = timetable.stationId(upstreamStation);
				for (int pattern : timetable.patternsByStop(upstreamStation)) {
					limits.consumeWork();
					ReversePatternTrips patternTrips = trips.pattern(pattern);
					if (patternTrips.isEmpty()) continue;
					int[] stops = timetable.stopsByPattern(pattern);
					for (int alight = 1; alight < stops.length; alight += 1) {
						if (stops[alight] != upstreamStation) continue;
						limits.consumeWork();
						if (!patternTrips.first().allowsDropOff(alight)) continue;
						if ((long) label.transfersUsed() + 1 + ridesBeforeAlight(pattern, alight) > query.maxTransfers()) {
							limits.count(JourneyRaptorPruningInventoryV1.PROFILE_TRANSFER_BUDGET);
							continue;
						}
						int upstreamLine = timetable.lineIndex(patternTrips.first().lineId(alight));
						for (int block = 0; block < patternTrips.blockCount(); block += 1) {
							int start = patternTrips.blockStart(block);
							int end = patternTrips.blockEnd(block);
							if (start == end) continue;
							TransferEvaluation evaluation = evaluateTransfer(
								timetable, query, station, stationId, label.line(),
								upstreamStation, upstreamStationId, upstreamLine, footpaths,
								RouteTimetableRaptorPlanner.RealtimeOverlay.combine(
									patternTrips.overlay(block), label.ride().trip().realtimeOverlay()));
							if (evaluation.match() == null) {
								if (evaluation.hasOpportunity()) limits.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
								continue;
							}
							TransferMatch match = evaluation.match();
							int seconds = transferSeconds(query, timetable, match.transition());
							long latestArrival = (long) label.departure() - seconds - query.boardingSlackSeconds();
							TraceAccess access = new TraceAccess(match.transition(), match.fromStationId(), stationId);
							if (patternTrips.ordered(block)) {
								int last = patternTrips.lastArrivingAtOrBefore(start, end, alight, latestArrival);
								for (int index = last; index >= start; index -= 1) {
									limits.consumeWork();
									int arrival = patternTrips.arrivalSeconds(index, alight);
									if ((long) arrival - fromOrigin[upstreamStation] - query.boardingSlackSeconds() < windowStart) {
										// 순서가 보장된 묶음에서는 더 이른 열차도 모두 창 밖이다.
										limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
										break;
									}
									boardUpstream(label, patternTrips, index, stops, alight, arrival, seconds, match.transition(), access);
								}
							} else {
								for (int index = start; index < end; index += 1) {
									limits.consumeWork();
									int arrival = patternTrips.arrivalSeconds(index, alight);
									if (arrival > latestArrival) continue;
									if ((long) arrival - fromOrigin[upstreamStation] - query.boardingSlackSeconds() < windowStart) {
										limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
										continue;
									}
									boardUpstream(label, patternTrips, index, stops, alight, arrival, seconds, match.transition(), access);
								}
							}
						}
					}
				}
			}
		}

		private void boardUpstream(
			ReverseLabel label,
			ReversePatternTrips patternTrips,
			int index,
			int[] stops,
			int alight,
			int arrival,
			int transferSeconds,
			int transition,
			TraceAccess access
		) {
			DatedScheduledTrip trip = patternTrips.trip(index);
			if (trip.realtimeOverlay().cancelled(trip.scheduledTrip())) return;
			long transferSlack = (long) label.departure() - arrival - transferSeconds - query.boardingSlackSeconds();
			long slack = label.slack() == NO_TRANSFER_SLACK ? transferSlack : Math.min(label.slack(), transferSlack);
			long accessSeconds = Math.addExact(label.accessSeconds(), transferSeconds);
			long accessMeters = Math.addExact(label.accessMeters(), timetable.transitionDistanceMeters(transition));
			long stairs = Math.addExact(label.stairs(), timetable.transitionIncludesStairs(transition) ? 1L : 0L);
			for (int board = 0; board < alight; board += 1) {
				limits.consumeWork();
				if (!trip.allowsPickup(board)) continue;
				offer(label.transfersUsed() + 1, patternTrips, index, stops, board, alight, label.arrivalAtDestination(),
					accessSeconds, accessMeters, stairs, slack, access, label);
			}
		}

		private void offer(
			int transfersUsed,
			ReversePatternTrips patternTrips,
			int index,
			int[] stops,
			int board,
			int alight,
			int arrivalAtDestination,
			long accessSeconds,
			long accessMeters,
			long stairs,
			long slack,
			TraceAccess access,
			ReverseLabel parent
		) {
			int departure = patternTrips.departureSeconds(index, board);
			int station = stops[board];
			TraceRide ride = new TraceRide(patternTrips.trip(index), board, alight);
			if (station == origin) {
				// #454: 출발역 승강장에서 바로 탄다. 준비 시각 = 출발 - 승차 여유(진입 간선·시간 없음).
				int readyAt = departure - query.boardingSlackSeconds();
				if (readyAt < query.earliestReadyAtSeconds()) return;
				List<TraceLeg> legs = new ArrayList<>();
				legs.add(ride);
				if (access != null) legs.add(access);
				for (ReverseLabel downstream = parent; downstream != null; downstream = downstream.parent()) {
					legs.add(downstream.ride());
					if (downstream.access() != null) legs.add(downstream.access());
				}
				candidates.add(new Candidate(readyAt, arrivalAtDestination, transfersUsed, accessSeconds, accessMeters,
					stairs, slack == NO_TRANSFER_SLACK ? new JourneyProfileRaptorPort.NoTransfer()
						: new JourneyProfileRaptorPort.MinimumTransferSeconds(slack), legs));
				if (readyAt > latestReadyAt) {
					latestReadyAt = readyAt;
					windowStart = Math.max(windowStart,
						latestReadyAt - JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW_SECONDS);
				}
				return;
			}
			long potential = (long) departure - fromOrigin[station] - query.boardingSlackSeconds();
			if (potential < windowStart) {
				limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
				return;
			}
			if (transfersUsed >= query.maxTransfers()) return;
			int line = timetable.lineIndex(patternTrips.trip(index).lineId(board));
			if (line < 0) return;
			if ((long) transfersUsed + ridesBefore(station, line) > query.maxTransfers()) {
				limits.count(JourneyRaptorPruningInventoryV1.PROFILE_TRANSFER_BUDGET);
				return;
			}
			ReverseLabel label = new ReverseLabel(new ReverseStateKey(transfersUsed, station, line,
				trips.blockedGroup(patternTrips.trip(index).realtimeOverlay())), departure, arrivalAtDestination,
				accessSeconds, accessMeters, stairs, slack, potential, ride, access, parent);
			if (admit(label)) queue.add(new QueueEntry(potential, sequence++, null, label));
		}

		private boolean admit(ReverseLabel candidate) {
			limits.consumeWork();
			List<ReverseLabel> labels = labelsByState.computeIfAbsent(candidate.stateKey(), ignored -> new ArrayList<>(4));
			// 한 번 훑으며 (1) 창 시작이 올라 창 밖이 된 라벨을 비우고(창 안 라벨을 지배할 수 없음: 출발이 더 이름)
			// (2) 후보를 지배하는 라벨이 있으면 거절하고 (3) 후보가 지배하는 라벨을 뺀다. 파레토 집합 안에서는 후보를
			// 지배하는 라벨과 후보가 지배하는 라벨이 함께 있을 수 없으므로(추이성) 거절될 후보는 아무것도 빼지 않는다.
			int kept = 0;
			boolean rejected = false;
			for (int read = 0; read < labels.size(); read += 1) {
				ReverseLabel existing = labels.get(read);
				if (existing.potential() < windowStart) {
					limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
					continue;
				}
				if (!rejected) {
					if (dominates(existing, candidate)) {
						limits.count("REVERSE_STATE_DOMINANCE_V1");
						rejected = true;
					} else if (sameVector(existing, candidate)) {
						limits.count("REVERSE_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1");
						if (existing.traceKey().compareTo(candidate.traceKey()) <= 0) {
							rejected = true;
						} else {
							continue;
						}
					} else if (dominates(candidate, existing)) {
						limits.count("REVERSE_STATE_DOMINANCE_V1");
						continue;
					}
				}
				labels.set(kept++, existing);
			}
			labels.subList(kept, labels.size()).clear();
			if (rejected) return false;
			labels.add(candidate);
			limits.observeStateLabels(labels.size());
			if (labels.size() > limits.maxLabelsPerState()) {
				limits.count("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
				throw new ReversePlanningLimitException(PlanningLimit.MAX_LABELS_PER_STATE,
					labels.size(), limits.maxLabelsPerState());
			}
			return true;
		}

		private boolean isCurrent(ReverseLabel label) {
			List<ReverseLabel> labels = labelsByState.get(label.stateKey());
			if (labels == null) return false;
			for (ReverseLabel existing : labels) {
				if (existing == label) return true;
			}
			return false;
		}

		private static boolean dominates(ReverseLabel left, ReverseLabel right) {
			return left.departure() >= right.departure()
				&& left.arrivalAtDestination() <= right.arrivalAtDestination()
				&& left.accessSeconds() <= right.accessSeconds()
				&& left.accessMeters() <= right.accessMeters()
				&& left.stairs() <= right.stairs()
				&& left.slack() >= right.slack()
				&& (left.departure() > right.departure()
					|| left.arrivalAtDestination() < right.arrivalAtDestination()
					|| left.accessSeconds() < right.accessSeconds()
					|| left.accessMeters() < right.accessMeters()
					|| left.stairs() < right.stairs()
					|| left.slack() > right.slack());
		}

		private static boolean sameVector(ReverseLabel left, ReverseLabel right) {
			return left.departure() == right.departure()
				&& left.arrivalAtDestination() == right.arrivalAtDestination()
				&& left.accessSeconds() == right.accessSeconds()
				&& left.accessMeters() == right.accessMeters()
				&& left.stairs() == right.stairs()
				&& left.slack() == right.slack();
		}
	}

	private record Seed(int pattern, int alight, int block, int index, boolean cursor) {
	}

	private record QueueEntry(long potential, long sequence, Seed seed, ReverseLabel label) {
	}

	/**
	 * 상태 키. {@code blockedGroup}은 라벨 승차 열차의 운행일 overlay가 막는 전환 집합의 번호다(같은 집합이면 같은 번호).
	 * 앞쪽 환승 판정은 앞 승차와 이 열차의 overlay 합집합으로 하므로, 차단 집합이 같은 라벨끼리만 지배를 비교해야
	 * 정확하다(#461 리뷰 F1).
	 */
	private record ReverseStateKey(int transfersUsed, int station, int line, int blockedGroup) {
	}

	/** 승차 하나부터 도착역까지의 뒷부분 여정. 같은 상태 안 비교는 객체 동일성으로 한다. */
	private static final class ReverseLabel {
		private final ReverseStateKey stateKey;
		private final int departure;
		private final int arrivalAtDestination;
		private final long accessSeconds;
		private final long accessMeters;
		private final long stairs;
		private final long slack;
		private final long potential;
		private final TraceRide ride;
		private final TraceAccess access;
		private final ReverseLabel parent;
		private String traceKey;

		private ReverseLabel(
			ReverseStateKey stateKey, int departure, int arrivalAtDestination,
			long accessSeconds, long accessMeters, long stairs, long slack, long potential,
			TraceRide ride, TraceAccess access, ReverseLabel parent
		) {
			this.stateKey = stateKey;
			this.departure = departure;
			this.arrivalAtDestination = arrivalAtDestination;
			this.accessSeconds = accessSeconds;
			this.accessMeters = accessMeters;
			this.stairs = stairs;
			this.slack = slack;
			this.potential = potential;
			this.ride = ride;
			this.access = access;
			this.parent = parent;
		}

		ReverseStateKey stateKey() { return stateKey; }
		int transfersUsed() { return stateKey.transfersUsed(); }
		int station() { return stateKey.station(); }
		int line() { return stateKey.line(); }
		int departure() { return departure; }
		int arrivalAtDestination() { return arrivalAtDestination; }
		long accessSeconds() { return accessSeconds; }
		long accessMeters() { return accessMeters; }
		long stairs() { return stairs; }
		long slack() { return slack; }
		long potential() { return potential; }
		TraceRide ride() { return ride; }
		TraceAccess access() { return access; }
		ReverseLabel parent() { return parent; }

		/** 같은 벡터 중 하나를 고르는 결정적 키. 승차·환승 trace를 도착역 쪽으로 이어 쓴다. */
		String traceKey() {
			if (traceKey == null) {
				StringBuilder key = new StringBuilder();
				for (ReverseLabel label = this; label != null; label = label.parent) {
					TraceRide value = label.ride;
					key.append("/r:").append(value.trip().serviceDate()).append(':').append(value.trip().index())
						.append(':').append(value.boardIndex()).append(':').append(value.alightIndex());
					if (label.access != null) key.append("/a:").append(label.access.transition());
				}
				traceKey = key.toString();
			}
			return traceKey;
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

	/** 막차 기준 도착: 그 서비스일에 도착역에 내리는 운행 열차 중 가장 늦은 도착. 도착역 패턴만 본다. */
	private static Integer terminalDeadline(
		LastConnectionQuery query,
		RouteTimetableRaptorPlanner.CompiledTimetable timetable,
		ReverseTrips trips,
		ReverseLimitTracker limits
	) {
		int destination = timetable.stationIndex(query.destinationStationId());
		if (destination < 0) return null;
		Integer latest = null;
		for (int pattern : timetable.patternsByStop(destination)) {
			limits.consumeWork();
			if (query.cancelled().getAsBoolean()) return null;
			ReversePatternTrips patternTrips = trips.pattern(pattern);
			if (patternTrips.isEmpty()) continue;
			int[] stops = timetable.stopsByPattern(pattern);
			for (int alight = 1; alight < stops.length; alight += 1) {
				if (stops[alight] != destination || !patternTrips.first().allowsDropOff(alight)) continue;
				for (int block = 0; block < patternTrips.blockCount(); block += 1) {
					for (int index = patternTrips.blockEnd(block) - 1; index >= patternTrips.blockStart(block); index -= 1) {
						limits.consumeWork();
						DatedScheduledTrip trip = patternTrips.trip(index);
						if (trip.realtimeOverlay().cancelled(trip.scheduledTrip())) continue;
						// #454: 도착역 승강장에 내리는 시각이 도착이다(하차 시간 없음).
						int arrival = patternTrips.arrivalSeconds(index, alight);
						if (latest == null || arrival > latest) latest = arrival;
						// 순서가 보장된 묶음은 마지막 열차의 도착이 가장 늦다.
						if (patternTrips.ordered(block)) break;
					}
				}
			}
		}
		return latest;
	}

	/**
	 * #461: 질의 서비스일 범위의 활성 운행. 패턴별 열차는 탐색이 그 패턴을 처음 볼 때만 펼친다(전 열차 사전 복사 없음).
	 * 시각은 질의 서비스일 0시 기준 초다.
	 */
	private static final class ReverseTrips {
		private final List<DateBlock> blocks;
		private final LocalDate anchorServiceDate;
		private final ReverseLimitTracker limits;
		/** 패턴 번호로 바로 찾는 질의 단위 캐시(#462). 처음 보는 패턴만 펼친다. */
		private final ReversePatternTrips[] byPattern;
		private final Map<RouteTimetableRaptorPlanner.RealtimeOverlay, Integer> groupByOverlay =
			new java.util.IdentityHashMap<>();
		private final Map<java.util.BitSet, Integer> groupByBlockedTransitions = new HashMap<>();

		private ReverseTrips(
			List<DateBlock> blocks, LocalDate anchorServiceDate, ReverseLimitTracker limits, int patternCount
		) {
			this.blocks = List.copyOf(blocks);
			this.anchorServiceDate = anchorServiceDate;
			this.limits = limits;
			this.byPattern = new ReversePatternTrips[patternCount];
		}

		/** 취소되면 null이다. */
		private static ReverseTrips of(
			LocalDate anchorServiceDate,
			LocalDate firstServiceDate,
			LocalDate lastServiceDate,
			Function<LocalDate, RouteTimetableRaptorPlanner.ActiveServiceDay> activeDays,
			Function<LocalDate, RouteTimetableRaptorPlanner.RealtimeOverlay> overlays,
			ReverseLimitTracker limits,
			BooleanSupplier cancelled
		) {
			List<DateBlock> blocks = new ArrayList<>();
			int patternCount = 0;
			for (LocalDate serviceDate = firstServiceDate; !serviceDate.isAfter(lastServiceDate);
				serviceDate = serviceDate.plusDays(1)) {
				if (cancelled.getAsBoolean()) return null;
				limits.consumeWork();
				RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay = activeDays.apply(serviceDate);
				patternCount = activeServiceDay.patternCount();
				if (activeServiceDay.routePatternTripLinkCount() == 0) continue;
				blocks.add(new DateBlock(serviceDate, activeServiceDay,
					Objects.requireNonNull(overlays.apply(serviceDate), "realtime overlay must not be null")));
			}
			return new ReverseTrips(blocks, anchorServiceDate, limits, patternCount);
		}

		private boolean noActiveService() {
			return blocks.isEmpty();
		}

		/** overlay가 막는 전환 집합의 번호. 집합 내용이 같으면 overlay가 달라도 같은 번호다. */
		private int blockedGroup(RouteTimetableRaptorPlanner.RealtimeOverlay overlay) {
			Integer known = groupByOverlay.get(overlay);
			if (known != null) return known;
			java.util.BitSet blocked = overlay.blockedTransitionsCopy();
			Integer group = groupByBlockedTransitions.get(blocked);
			if (group == null) {
				group = groupByBlockedTransitions.size();
				groupByBlockedTransitions.put(blocked, group);
			}
			groupByOverlay.put(overlay, group);
			return group;
		}

		private List<RouteTimetableRaptorPlanner.RealtimeOverlay> overlays() {
			return blocks.stream().map(DateBlock::realtimeOverlay).toList();
		}

		/** 한 패턴의 날짜별 열차. 펼친 열차마다 작업량 1이다. */
		private ReversePatternTrips pattern(int pattern) {
			ReversePatternTrips cached = byPattern[pattern];
			if (cached != null) return cached;
			List<DatedScheduledTrip> datedTrips = new ArrayList<>();
			List<Integer> offsets = new ArrayList<>();
			int[] starts = new int[blocks.size()];
			boolean[] ordered = new boolean[blocks.size()];
			RouteTimetableRaptorPlanner.RealtimeOverlay[] blockOverlays =
				new RouteTimetableRaptorPlanner.RealtimeOverlay[blocks.size()];
			for (int index = 0; index < blocks.size(); index += 1) {
				DateBlock block = blocks.get(index);
				starts[index] = datedTrips.size();
				ordered[index] = !block.realtimeOverlay().affectsPattern(pattern);
				blockOverlays[index] = block.realtimeOverlay();
				int offset = serviceDateOffsetSeconds(anchorServiceDate, block.serviceDate());
				for (RouteTimetableRaptorPlanner.ScheduledTrip trip : block.activeServiceDay().tripsByPattern(pattern)) {
					limits.consumeWork();
					datedTrips.add(new DatedScheduledTrip(block.serviceDate(), trip, block.realtimeOverlay()));
					offsets.add(offset);
				}
			}
			ReversePatternTrips value = new ReversePatternTrips(datedTrips,
				offsets.stream().mapToInt(Integer::intValue).toArray(), starts, ordered, blockOverlays);
			byPattern[pattern] = value;
			return value;
		}
	}

	private record DateBlock(
		LocalDate serviceDate,
		RouteTimetableRaptorPlanner.ActiveServiceDay activeServiceDay,
		RouteTimetableRaptorPlanner.RealtimeOverlay realtimeOverlay
	) {
	}

	/**
	 * 한 패턴의 날짜별 열차 묶음. 묶음 i는 {@code blockStarts[i]}부터 다음 묶음 시작 전까지다. 실시간 변경이 닿지 않은
	 * 묶음은 정차역마다 도착·출발이 줄지 않는다(비추월 패턴)라서 이분 탐색과 조기 종료를 쓴다.
	 */
	private static final class ReversePatternTrips {
		private final List<DatedScheduledTrip> trips;
		private final int[] offsets;
		private final int[] blockStarts;
		private final boolean[] ordered;
		private final RouteTimetableRaptorPlanner.RealtimeOverlay[] overlays;

		private ReversePatternTrips(
			List<DatedScheduledTrip> trips,
			int[] offsets,
			int[] blockStarts,
			boolean[] ordered,
			RouteTimetableRaptorPlanner.RealtimeOverlay[] overlays
		) {
			this.trips = List.copyOf(trips);
			this.offsets = offsets;
			this.blockStarts = blockStarts;
			this.ordered = ordered;
			this.overlays = overlays;
		}

		private boolean isEmpty() {
			return trips.isEmpty();
		}

		private DatedScheduledTrip first() {
			return trips.getFirst();
		}

		private DatedScheduledTrip trip(int index) {
			return trips.get(index);
		}

		private int blockCount() {
			return blockStarts.length;
		}

		private int blockStart(int block) {
			return blockStarts[block];
		}

		private int blockEnd(int block) {
			return block + 1 < blockStarts.length ? blockStarts[block + 1] : trips.size();
		}

		private boolean ordered(int block) {
			return ordered[block];
		}

		private RouteTimetableRaptorPlanner.RealtimeOverlay overlay(int block) {
			return overlays[block];
		}

		private int arrivalSeconds(int index, int stop) {
			DatedScheduledTrip trip = trips.get(index);
			return Math.addExact(offsets[index], trip.realtimeOverlay().arrivalSeconds(trip.scheduledTrip(), stop));
		}

		private int departureSeconds(int index, int stop) {
			DatedScheduledTrip trip = trips.get(index);
			return Math.addExact(offsets[index], trip.realtimeOverlay().departureSeconds(trip.scheduledTrip(), stop));
		}

		/** 순서가 보장된 [start, end)에서 stop 도착이 limit 이하인 마지막 열차. 없으면 start - 1. */
		private int lastArrivingAtOrBefore(int start, int end, int stop, long limit) {
			int low = start;
			int high = end;
			while (low < high) {
				int middle = (low + high) >>> 1;
				if (arrivalSeconds(middle, stop) <= limit) low = middle + 1;
				else high = middle;
			}
			return low - 1;
		}
	}

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
		ReverseTrips trips,
		ReverseLimitTracker limitTracker
	) {
		private PreparedLastConnection {
			outcome = Objects.requireNonNull(outcome, "outcome");
			limitTracker = Objects.requireNonNull(limitTracker, "limitTracker");
			if (outcome == Outcome.FOUND) {
				Objects.requireNonNull(terminalArrivalAtDestinationSeconds, "found preparation needs terminal arrival");
				Objects.requireNonNull(trips, "found preparation needs active trips");
			} else if (terminalArrivalAtDestinationSeconds != null || trips != null) {
				throw new IllegalArgumentException("terminal failure must not retain active trips or terminal arrival");
			}
		}

		static PreparedLastConnection terminal(Outcome outcome, ReverseLimitTracker limitTracker) {
			return new PreparedLastConnection(outcome, null, null, limitTracker);
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

		private int maxLabelsPerState() {
			return limits.maxLabelsPerState();
		}
	}

}
