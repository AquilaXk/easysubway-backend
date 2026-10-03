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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class RouteTimetableRaptorPlanner {

	private static final ZoneId SERVICE_ZONE = ServiceDayResolver.ZONE;
	private static final int PARETO_LIMIT = 4;
	private static final int TRANSFER_DURATION_SECONDS = 360;
	private static final int TRANSFER_DISTANCE_METERS = 260;
	/**
	 * #454: 경로는 출발역 승강장(역-노선)에서 시작해 도착역 승강장에서 끝난다(QA 2026-10-02).
	 * 출발·도착 경계는 이동이 없는 단일 전환으로 표현한다. 거리·시간 0, 경고·차단 없음, 검증 상태는
	 * {@code NOT_APPLICABLE}이며 응답 구간으로 내보내지 않는다. 진입·하차(ENTRY/EXIT) 간선과 그 기본값은 쓰지 않는다.
	 */
	static final int PLATFORM_BOUNDARY = 0;
	static final String PLATFORM_BOUNDARY_STATUS = "NOT_APPLICABLE";
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
	static final int PREFER_STEP_FREE_PROFILE_MASK = profileMask(ConstraintMode.PREFER_STEP_FREE);
	static boolean prefersStepFree(int profileBit) {
		return (profileBit & PREFER_STEP_FREE_PROFILE_MASK) != 0;
	}
	private static final int NON_STRICT_PROFILE_MASK = profileMask(
		ConstraintMode.PREFER_STEP_FREE, ConstraintMode.ALLOW_WITH_WARNINGS);
	private final ScanWorkspacePool workspacePool;

	public RouteTimetableRaptorPlanner() {
		this(ScanWorkspacePool.shared());
	}

	public RouteTimetableRaptorPlanner(ScanWorkspacePool workspacePool) {
		this.workspacePool = Objects.requireNonNull(workspacePool, "workspacePool");
	}

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
			new JourneyRequestMeasurement(query.requestId()), query.requestId(),
			timetable.routeBundleSha256(), timetable.generation());
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
		List<JourneyItinerary> raw = scanResult.labels().stream()
			.sorted(RouteTimetableRaptorPlanner::compareLabels)
			.limit(input.candidateLimit())
			.map(label -> toJourneyItinerary(input, timetable, label))
			.toList();
		return assignPersonas(raw);
	}


	private static JourneyItinerary toJourneyItinerary(
		ScanInput input,
		CompiledTimetable timetable,
		Label label
	) {
		List<JourneyLegProjection> legs = new ArrayList<>();
		List<RideLeg> path = label.path();
		RideLeg firstRide = path.getFirst();
		for (int index = 0; index < path.size(); index += 1) {
			RideLeg ride = path.get(index);
			if (index > 0) {
				RideLeg previous = path.get(index - 1);
				int transferTransition = label.accessTransitions()[index];
				boolean isOutOfStation = timetable.isOutOfStationTransition(transferTransition);
				String transferType = isOutOfStation ? "OUT_OF_STATION" : null;
				Boolean farePenaltyApplies = null;
				Integer transferLimitMinutes = null;
				if (isOutOfStation) {
					int elapsed = ride.departureSeconds() - previous.arrivalSeconds();
					int limit = getTransferLimitSeconds(previous.arrivalSeconds(), ride.departureSeconds());
					boolean timeout = elapsed > limit;
					farePenaltyApplies = timeout;
					transferLimitMinutes = limit / 60;
				}
				legs.add(new JourneyAccessProjection(
					JourneyAccessKind.TRANSFER,
					previous.to().stationId(),
					ride.from().stationId(),
					journeyTransferSeconds(input,
						timetable.transitionDurationSeconds(transferTransition),
						timetable.transitionDistanceMeters(transferTransition)),
					timetable.transitionDistanceMeters(transferTransition),
					timetable.transitionIncludesStairs(transferTransition),
					timetable.transitionVerified(transferTransition),
					timetable.transitionVerificationStatus(transferTransition),
					transferType,
					farePenaltyApplies,
					transferLimitMinutes
				));
			}
			RealtimeEvidence evidence = ride.realtimeOverlay().evidence(ride.scheduledTrip());
			boolean nextIsTransfer = (index + 1 < path.size());
			boolean stepFree = (input.constraintMode() == ConstraintMode.STRICT_STEP_FREE
				|| input.mobilityPreset() == MobilityPreset.STEP_FREE);
			List<AlightingCarDoor> alightingCarDoors = timetable.selectAlightingCarDoors(
				ride.to().stationId(),
				ride.lineId(),
				ride.scheduledTrip().trip().directionId(),
				nextIsTransfer,
				stepFree
			);
			List<JourneyStopProjection> stops = new ArrayList<>();
			for (int i = ride.fromIndex(); i <= ride.toIndex(); i++) {
				if (i == ride.fromIndex() || i == ride.toIndex()
						|| ride.scheduledTrip().allowsPickup(i) || ride.scheduledTrip().allowsDropOff(i)) {
					String stationId = ride.scheduledTrip().stopTimes().get(i).stationId();
					Instant plannedArr = i == ride.fromIndex() ? null
						: serviceInstant(ride.serviceDay(input.serviceDay()), ride.scheduledTrip().arrivalSeconds(i));
					Instant plannedDep = i == ride.toIndex() ? null
						: serviceInstant(ride.serviceDay(input.serviceDay()), ride.scheduledTrip().departureSeconds(i));
					Instant realtimeArr = evidence == null || i == ride.fromIndex() ? null
						: serviceInstant(ride.serviceDay(input.serviceDay()), ride.realtimeOverlay().arrivalSeconds(ride.scheduledTrip(), i));
					Instant realtimeDep = evidence == null || i == ride.toIndex() ? null
						: serviceInstant(ride.serviceDay(input.serviceDay()), ride.realtimeOverlay().departureSeconds(ride.scheduledTrip(), i));
					stops.add(new JourneyStopProjection(
						stationId, plannedArr, plannedDep, realtimeArr, realtimeDep
					));
				}
			}
			legs.add(new JourneyRideProjection(
				ride.lineId(),
				ride.tripId(),
				ride.scheduledTrip().stopTimes().getLast().stationId(),
				ride.from().stationId(),
				ride.to().stationId(),
				ride.trip().servicePattern(),
				ride.plannedDepartureTime(input.serviceDay()),
				ride.plannedArrivalTime(input.serviceDay()),
				evidence == null ? null : ride.realtimeDepartureTime(input.serviceDay()),
				evidence == null ? null : ride.realtimeArrivalTime(input.serviceDay()),
				List.copyOf(stops),
				alightingCarDoors,
				timetable.platformGaps(ride.from().stationId(), ride.lineId(), ride.scheduledTrip().trip().directionId()),
				timetable.platformGaps(ride.to().stationId(), ride.lineId(), ride.scheduledTrip().trip().directionId())
			));
		}
		RideLeg lastRide = path.getLast();
		return new JourneyItinerary(
			input.serviceDay().date(),
			serviceInstant(input.serviceDay(), label.startSeconds()),
			lastRide.plannedArrivalTime(input.serviceDay()),
			firstRide.realtimeOverlay().available()
				? serviceInstant(input.serviceDay(), label.startSeconds()) : null,
			lastRide.realtimeOverlay().available()
				? lastRide.realtimeArrivalTime(input.serviceDay())
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
				pendingTransfer = access;
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

	private static Instant serviceInstant(ServiceDay serviceDay, int seconds) {
		return serviceDay.date().atStartOfDay(SERVICE_ZONE).plusSeconds(seconds).toInstant();
	}

	/**
	 * #462: 하한 Dijkstra의 역 우선순위 큐. 키는 거리 배열 값이고 역마다 한 칸만 두며, 거리가 줄면 그 칸의 위치를
	 * 고친다(decrease-key). 꺼낸 역의 거리가 다시 줄면 다시 넣는다. 원시 배열만 쓰므로 완화마다 객체를 만들지 않고,
	 * 큐 크기는 역 수를 넘지 않는다. 최단 거리는 꺼내는 순서(동률 순서)와 무관하게 같다.
	 */
	static final class StationHeap {
		private final int[] distance;
		private final int[] heap;
		private final int[] position;
		private int size;

		StationHeap(int[] distance) {
			this.distance = distance;
			this.heap = new int[distance.length];
			this.position = new int[distance.length];
			Arrays.fill(position, -1);
		}

		boolean isEmpty() {
			return size == 0;
		}

		/** {@code distance[station]}이 줄었을 때 부른다. 큐에 없으면 넣는다. */
		void decreased(int station) {
			int index = position[station];
			siftUp(index < 0 ? size++ : index, station);
		}

		int poll() {
			int top = heap[0];
			position[top] = -1;
			size -= 1;
			if (size > 0) siftDown(heap[size]);
			return top;
		}

		private void siftUp(int start, int station) {
			int key = distance[station];
			int index = start;
			while (index > 0) {
				int parent = (index - 1) >>> 1;
				int other = heap[parent];
				if (distance[other] <= key) break;
				heap[index] = other;
				position[other] = index;
				index = parent;
			}
			heap[index] = station;
			position[station] = index;
		}

		private void siftDown(int station) {
			int key = distance[station];
			int index = 0;
			int half = size >>> 1;
			while (index < half) {
				int child = 2 * index + 1;
				if (child + 1 < size && distance[heap[child + 1]] < distance[heap[child]]) child += 1;
				int other = heap[child];
				if (key <= distance[other]) break;
				heap[index] = other;
				position[other] = index;
				index = child;
			}
			heap[index] = station;
			position[station] = index;
		}
	}

	static int[] computeStationLowerBounds(CompiledTimetable timetable, int destinationStation) {
		int stationCount = timetable.stationCount();
		int[] lb = new int[stationCount];
		Arrays.fill(lb, Integer.MAX_VALUE / 2);
		if (destinationStation < 0 || destinationStation >= stationCount) {
			return lb;
		}
		lb[destinationStation] = 0;
		StationHeap pq = new StationHeap(lb);
		pq.decreased(destinationStation);

		while (!pq.isEmpty()) {
			int u = pq.poll();
			int dist = lb[u];
			propagatePatternLowerBounds(timetable, u, dist, lb, pq);
			propagateFootpathLowerBounds(timetable, u, dist, lb, pq);
		}
		return lb;
	}

	private static void propagatePatternLowerBounds(
		CompiledTimetable timetable, int u, int dist, int[] lb, StationHeap pq
	) {
		int[] occurrences = timetable.stopOccurrences(u);
		for (int index = 0; index < occurrences.length; index += 2) {
			int pattern = occurrences[index];
			int pos = occurrences[index + 1];
			int[] stops = timetable.stopsByPattern(pattern);
			int[] hops = timetable.minPatternRunningTimes(pattern);
			for (int prevPos = 0; prevPos < pos; prevPos += 1) {
				int v = stops[prevPos];
				long nextDist = (long) dist + hops[prevPos * stops.length + pos];
				if (nextDist < lb[v]) {
					lb[v] = (int) nextDist;
					pq.decreased(v);
				}
			}
		}
	}

	private static void propagateFootpathLowerBounds(
		CompiledTimetable timetable, int u, int dist, int[] lb, StationHeap pq
	) {
		OutOfStationFootpath[] incoming = timetable.footpathsToStation(u);
		if (incoming != null) {
			for (OutOfStationFootpath fp : incoming) {
				int v = fp.fromStation();
				int[] cand = fp.candidateTransitions();
				int footTime = cand.length > 0 ? timetable.transitionDurationSeconds(cand[0]) : 0;
				long nextDist = (long) dist + footTime;
				if (nextDist < lb[v]) {
					lb[v] = (int) nextDist;
					pq.decreased(v);
				}
			}
		}
	}

	/**
	 * #461 프로필 대안 창의 가지치기 하한(각 역에서 도착역까지). 노선 최소 주행 시간만 더하고 역 밖 환승 보행은 0으로
	 * 둔다(걸음 속도에 따라 줄 수 있음). 실시간 변경이 닿은 패턴은 변경 시각으로 최소 주행 시간을 다시 잡아 하한이
	 * 실제보다 커지지 않게 한다.
	 */
	static int[] computeProfileLowerBounds(
		CompiledTimetable timetable,
		int destinationStation,
		List<RealtimeOverlay> overlays
	) {
		return profileLowerBounds(timetable, destinationStation, overlays, true);
	}

	/** {@link #computeProfileLowerBounds}의 방향을 뒤집은 하한(출발역에서 각 역까지). 도착 희망·막차 탐색이 쓴다. */
	static int[] computeProfileLowerBoundsFrom(
		CompiledTimetable timetable,
		int originStation,
		List<RealtimeOverlay> overlays
	) {
		return profileLowerBounds(timetable, originStation, overlays, false);
	}

	private static int[] profileLowerBounds(
		CompiledTimetable timetable,
		int anchorStation,
		List<RealtimeOverlay> overlays,
		boolean towardAnchor
	) {
		int stationCount = timetable.stationCount();
		int[] lb = new int[stationCount];
		Arrays.fill(lb, Integer.MAX_VALUE / 2);
		if (anchorStation < 0 || anchorStation >= stationCount) return lb;
		Map<Integer, int[]> realtimeHops = realtimeMinimumHops(timetable, overlays);
		lb[anchorStation] = 0;
		StationHeap pq = new StationHeap(lb);
		pq.decreased(anchorStation);
		while (!pq.isEmpty()) {
			int u = pq.poll();
			int dist = lb[u];
			for (int pattern : timetable.patternsByStop(u)) {
				int[] stops = timetable.stopsByPattern(pattern);
				int[] adjusted = realtimeHops.get(pattern);
				for (int pos = 0; pos < stops.length; pos += 1) {
					if (stops[pos] != u) continue;
					int from = towardAnchor ? 0 : pos + 1;
					int to = towardAnchor ? pos : stops.length;
					for (int other = from; other < to; other += 1) {
						int earlier = towardAnchor ? other : pos;
						int later = towardAnchor ? pos : other;
						int runTime = adjusted != null ? adjusted[earlier * stops.length + later]
							: timetable.minPatternRunningTime(pattern, earlier, later);
						long nextDist = (long) dist + Math.max(0, runTime);
						int v = stops[other];
						if (nextDist < lb[v]) {
							lb[v] = (int) nextDist;
							pq.decreased(v);
						}
					}
				}
			}
			OutOfStationFootpath[] footpaths = towardAnchor
				? timetable.footpathsToStation(u) : timetable.footpathsFromStation(u);
			if (footpaths != null) {
				for (OutOfStationFootpath fp : footpaths) {
					int v = towardAnchor ? fp.fromStation() : fp.toStation();
					if (dist < lb[v]) {
						lb[v] = dist;
						pq.decreased(v);
					}
				}
			}
		}
		return lb;
	}

	private static Map<Integer, int[]> realtimeMinimumHops(CompiledTimetable timetable, List<RealtimeOverlay> overlays) {
		Map<Integer, int[]> adjusted = new HashMap<>();
		for (RealtimeOverlay overlay : overlays) {
			for (int entry = 0; entry < overlay.tripIndexes.length; entry += 1) {
				if (overlay.cancelled[entry]) continue;
				ScheduledTrip trip = timetable.scheduledTrip(overlay.tripIndexes[entry]);
				int pattern = timetable.patternOfScheduledTrip(trip.index());
				if (pattern < 0) continue;
				int numStops = timetable.stopsByPattern(pattern).length;
				int[] hops = adjusted.computeIfAbsent(pattern, key -> {
					int[] base = new int[numStops * numStops];
					for (int i = 0; i < numStops; i += 1) {
						for (int j = i + 1; j < numStops; j += 1) base[i * numStops + j] = timetable.minPatternRunningTime(key, i, j);
					}
					return base;
				});
				for (int i = 0; i < numStops; i += 1) {
					for (int j = i + 1; j < numStops; j += 1) {
						int duration = overlay.arrivalSeconds(trip, j) - overlay.departureSeconds(trip, i);
						if (duration < hops[i * numStops + j]) hops[i * numStops + j] = duration;
					}
				}
			}
		}
		return adjusted;
	}

	/** 도착(출발)역에 닿을 수 없음을 뜻하는 남은 승차 수. */
	static final int UNREACHABLE_BOARDINGS = Integer.MAX_VALUE / 4;

	/**
	 * #461: (역, 들어온 노선)에서 도착역까지 필요한 최소 승차 수. 시각을 보지 않는 완화이고 환승 적격성은 출발 범위
	 * 탐색과 같은 규칙(역 안 환승 {@link CompiledTimetable#transferTransition}, 역 밖 보행 {@code selectTransition})을
	 * 쓴다. 결과는 {@code station * lineCount + line} 색인이며 {@code maxBoardings}를 넘으면
	 * {@link #UNREACHABLE_BOARDINGS}다.
	 */
	static int[] forwardRemainingBoardings(
		CompiledTimetable timetable,
		ScanInput input,
		RealtimeOverlay accessOverlay,
		int destination,
		int maxBoardings
	) {
		int lines = timetable.lineCount();
		int stations = timetable.stationCount();
		int[] remaining = new int[stations * lines];
		Arrays.fill(remaining, UNREACHABLE_BOARDINGS);
		if (destination < 0) return remaining;
		for (int line = 0; line < lines; line += 1) remaining[destination * lines + line] = 0;
		for (int boardings = 1; boardings <= maxBoardings; boardings += 1) {
			boolean[] boardable = new boolean[stations * lines];
			for (int pattern = 0; pattern < timetable.routePatternCount(); pattern += 1) {
				int[] stops = timetable.stopsByPattern(pattern);
				ScheduledTrip representative = timetable.patternRepresentative(pattern);
				for (int board = 0; board < stops.length; board += 1) {
					if (!representative.allowsPickup(board)) continue;
					int line = timetable.lineIndex(representative.lineId(board));
					if (line < 0) continue;
					for (int alight = board + 1; alight < stops.length; alight += 1) {
						if (representative.allowsDropOff(alight)
							&& remaining[stops[alight] * lines + line] <= boardings - 1) {
							boardable[stops[board] * lines + line] = true;
							break;
						}
					}
				}
			}
			int[] next = remaining.clone();
			for (int station = 0; station < stations; station += 1) {
				OutOfStationFootpath[] footpaths = timetable.footpathsFromStation(station);
				for (int incoming = 0; incoming < lines; incoming += 1) {
					int index = station * lines + incoming;
					if (remaining[index] <= boardings) continue;
					boolean reachable = false;
					for (int local = 0; local < timetable.stationLineCount(station) && !reachable; local += 1) {
						int boardingLine = timetable.stationLine(station, local);
						reachable = boardable[station * lines + boardingLine]
							&& timetable.transferTransition(station, incoming, boardingLine, input.accessProfileBit(), false,
								input.requiresVerifiedJourneyDistance(), accessOverlay) >= 0;
					}
					if (!reachable && footpaths != null) {
						for (OutOfStationFootpath footpath : footpaths) {
							if (footpath.fromLine() == incoming && boardable[footpath.toStation() * lines + footpath.toLine()]
								&& timetable.selectTransition(footpath.candidateTransitions(), input.accessProfileBit(), false,
									input.requiresVerifiedJourneyDistance(), accessOverlay) >= 0) {
								reachable = true;
								break;
							}
						}
					}
					if (reachable) next[index] = boardings;
				}
			}
			remaining = next;
		}
		return remaining;
	}

	private ScanResult scanDestinationLabels(
		ScanInput input,
		CompiledTimetable timetable,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		ActiveServiceDay activeServiceDay = timetable.activeServiceDay(input.serviceDay().date());
		ScanWorkspace workspace = workspacePool.acquire();
		try {
			workspace.prepare(timetable);
			if (activeServiceDay.trips().isEmpty()) {
				return new ScanResult(input.serviceDay(), List.of(), scanMetrics(workspace));
			}
			int origin = timetable.stationIndex(input.originStationId());
			int destination = timetable.stationIndex(input.destinationStationId());
			if (origin < 0 || destination < 0) {
				return new ScanResult(input.serviceDay(), List.of(), scanMetrics(workspace));
			}
			int[] lowerBounds = computeStationLowerBounds(timetable, destination);
			workspace.setTargetStation(destination, lowerBounds);
			workspace.improveOrigin(origin, input.readyAtSeconds());

			int slackSeconds = input.boardingSlackSeconds();
			int accessProfileBit = input.accessProfileBit();
			scanMarkedRounds(
				input, timetable, activeServiceDay, workspace, slackSeconds, accessProfileBit, ignoreAccessBlocks, realtimeOverlay);
			return destinationScanResult(input, timetable, workspace, destination, realtimeOverlay);
		} finally {
			workspacePool.release(workspace);
		}
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
		RealtimeOverlay realtimeOverlay
	) {
		List<Label> destinationLabels = limitDestinationLabels(
			destinationLabels(
				input.destinationStationId(), timetable, workspace, destination, input.readyAtSeconds(),
				input, realtimeOverlay),
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
			int boardingLine = timetable.patternLine(pattern, position);
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
				int boardingLine = timetable.patternLine(pattern, position);
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

	@SuppressWarnings("java:S107")
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
			// #454: 0회차는 출발역 승강장에서 바로 탄다. 진입 간선을 찾지 않는다.
			int canonicalTransition = round == 0
				? PLATFORM_BOUNDARY
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

			if (round > 0 && input.prefersStepFree()) {
				int[] candidates = timetable.transferTransitions(station, incomingLine, boardingLine);
				byte canonicalWarnings = timetable.transitionWarningCodes(
					canonicalTransition, accessProfileBit, ignoreAccessBlocks);
				int bestAlternative = -1;
				for (int alt : candidates) {
					if (alt == canonicalTransition
						|| (realtimeOverlay != null && realtimeOverlay.isTransitionBlocked(alt))
						|| !timetable.isTransitionEligible(alt, accessProfileBit, ignoreAccessBlocks,
							input.requiresVerifiedJourneyDistance(), true)) {
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
			int accessSeconds = round == 0 ? 0
				: journeyTransferSeconds(input, timetable.transitionDurationSeconds(accessTransition),
					timetable.transitionDistanceMeters(accessTransition));
			int earliestDepartureSeconds = readySeconds + accessSeconds + slackSeconds;
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
		for (int fpIndex = 0; fpIndex < footpaths.length; fpIndex += 1) {
			OutOfStationFootpath footpath = footpaths[fpIndex];
			boolean footpathDominated = evaluateFootpathTransitions(
				timetable, workspace, footpath, station, round, slackSeconds,
				accessProfileBit, input, ignoreAccessBlocks, boardingDeadlineSeconds, realtimeOverlay
			);
			if (footpathDominated && canPruneRemainingFootpaths(
				timetable, workspace, footpaths, fpIndex + 1, station, round,
				slackSeconds, accessProfileBit, input, ignoreAccessBlocks, realtimeOverlay
			)) {
				break;
			}
		}
	}

	@SuppressWarnings("java:S107")
	private static boolean evaluateFootpathTransitions(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		OutOfStationFootpath footpath,
		int station,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		int boardingDeadlineSeconds,
		RealtimeOverlay realtimeOverlay
	) {
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
					+ journeyTransferSeconds(input,
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
		}
		return footpathDominated
			&& minDepartureForFootpath != Integer.MAX_VALUE
			&& workspace.isTargetDominatingDeparture(station, minDepartureForFootpath);
	}

	@SuppressWarnings("java:S107")
	private static boolean canPruneRemainingFootpaths(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		OutOfStationFootpath[] footpaths,
		int startIndex,
		int station,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
		for (int nextIdx = startIndex; nextIdx < footpaths.length; nextIdx += 1) {
			if (!isFootpathCandidateDominated(
				timetable, workspace, footpaths[nextIdx], station, round,
				slackSeconds, accessProfileBit, input, ignoreAccessBlocks, realtimeOverlay
			)) {
				return false;
			}
		}
		return true;
	}

	@SuppressWarnings("java:S107")
	private static boolean isFootpathCandidateDominated(
		CompiledTimetable timetable,
		ScanWorkspace workspace,
		OutOfStationFootpath nextFp,
		int station,
		int round,
		int slackSeconds,
		int accessProfileBit,
		ScanInput input,
		boolean ignoreAccessBlocks,
		RealtimeOverlay realtimeOverlay
	) {
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
					+ journeyTransferSeconds(input,
						timetable.transitionDurationSeconds(nextCandidate),
						timetable.transitionDistanceMeters(nextCandidate))
					+ slackSeconds;
				byte warningBits = (byte) (workspace.warningBits[slot]
					| timetable.transitionWarningCodes(nextCandidate, accessProfileBit, ignoreAccessBlocks));
				int candidateWarningState = Byte.toUnsignedInt(warningBits);
				if (!workspace.isDominatedByTarget(station, dep, candidateWarningState)) {
					return false;
				}
			}
		}
		return true;
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
		ScanInput input,
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
				// #454: 도착역의 어느 승강장(역-노선)에 내려도 도착이다. 하차 간선을 찾지 않는다.
				int incomingLine = timetable.stationLine(lineOffset + lineIdx);
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
						workspace.arrivalSeconds[slot],
						startSeconds,
						boardings,
						List.copyOf(path),
						accessTransitions,
						PLATFORM_BOUNDARY,
						workspace.warningBits[slot],
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

	static Map<RoutePersona, JourneyItinerary> classifyPersonas(List<JourneyItinerary> itineraries) {
		if (itineraries == null || itineraries.isEmpty()) {
			return Map.of();
		}
		Map<RoutePersona, JourneyItinerary> map = new EnumMap<>(RoutePersona.class);

		JourneyItinerary fastest = itineraries.stream()
			.min(Comparator.comparing((JourneyItinerary it) -> Duration.between(it.plannedDepartureTime(), it.plannedArrivalTime()))
				.thenComparing(JourneyItinerary::plannedArrivalTime))
			.orElse(itineraries.getFirst());
		map.put(RoutePersona.FASTEST, fastest);

		JourneyItinerary stepFree = itineraries.stream()
			.min(Comparator.comparing((JourneyItinerary it) -> it.metrics().accessibilityBurden())
				.thenComparing(it -> Duration.between(it.plannedDepartureTime(), it.plannedArrivalTime())))
			.orElse(fastest);
		map.put(RoutePersona.STEP_FREE, stepFree);

		JourneyItinerary minWalk = itineraries.stream()
			.min(Comparator.comparing((JourneyItinerary it) -> it.metrics().accessDistanceMeters())
				.thenComparing(it -> Duration.between(it.plannedDepartureTime(), it.plannedArrivalTime())))
			.orElse(fastest);
		map.put(RoutePersona.MIN_WALK, minWalk);

		JourneyItinerary relaxedSlack = itineraries.stream()
			.filter(it -> slackSeconds(it) >= 300)
			.min(Comparator.comparing((JourneyItinerary it) -> Duration.between(it.plannedDepartureTime(), it.plannedArrivalTime())))
			.orElseGet(() -> itineraries.stream()
				.max(Comparator.comparing(RouteTimetableRaptorPlanner::slackSeconds))
				.orElse(fastest));
		map.put(RoutePersona.RELAXED_SLACK, relaxedSlack);

		return map;
	}

	static long slackSeconds(JourneyItinerary itinerary) {
		if (itinerary.metrics().connectionSlack() instanceof JourneyProfileRaptorPort.MinimumTransferSeconds min) {
			return min.seconds();
		}
		return itinerary.metrics().connectionSlack() instanceof JourneyProfileRaptorPort.NoTransfer ? Long.MAX_VALUE : 0;
	}

	static List<JourneyItinerary> assignPersonas(List<JourneyItinerary> itineraries) {
		if (itineraries == null || itineraries.isEmpty()) {
			return List.of();
		}
		Map<RoutePersona, JourneyItinerary> personas = classifyPersonas(itineraries);
		List<JourneyItinerary> result = new ArrayList<>(itineraries.size());

		for (JourneyItinerary it : itineraries) {
			RoutePersona personaToAssign = RoutePersona.FASTEST;
			for (RoutePersona persona : RoutePersona.values()) {
				if (personas.get(persona) == it) {
					personaToAssign = persona;
					break;
				}
			}
			result.add(it.withPersona(personaToAssign));
		}
		return List.copyOf(result);
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


	static int journeyTransferSeconds(
		ScanInput input,
		int baselineSeconds,
		int distanceMeters
	) {
		if (input.requiresVerifiedJourneyDistance()) {
			return verifiedTransferSeconds(baselineSeconds, distanceMeters,
				input.walkingSpeedMetersPerHour(), input.mobilityPreset());
		}
		return ProfileWalkTimeCalculator.estimateSeconds(
			baselineSeconds, input.mobilityPreset(), WalkTimeSource.OFFICIAL_BASELINE, false).seconds();
	}

	/**
	 * 검증 환승의 프로필 반영 시간. 공식 거리가 있으면 거리 ÷ 걸음 속도(기존 규칙)이고, 거리 없이 공식 실측 시간만
	 * 있으면(#454·data#876) {@link ProfileWalkTimeCalculator#measuredJourneySeconds}로 실측 시간을 하한으로 쓴다.
	 */
	static int verifiedTransferSeconds(
		int durationSeconds,
		int distanceMeters,
		int walkingSpeedMetersPerHour,
		MobilityPreset mobilityPreset
	) {
		if (distanceMeters > 0) {
			return ProfileWalkTimeCalculator.journeySeconds(
				distanceMeters, walkingSpeedMetersPerHour, mobilityPreset, false);
		}
		return ProfileWalkTimeCalculator.measuredJourneySeconds(
			durationSeconds, walkingSpeedMetersPerHour, mobilityPreset, false);
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

	CompiledTimetable compile(String routeBundleSha256, long generation, RouteTimetable timetable) {
		return new CompiledTimetable(timetable, routeBundleSha256, generation);
	}

	static String computeTimetableDigest(RouteTimetable timetable) {
		Objects.requireNonNull(timetable, "timetable must not be null");
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (TransitRoute route : timetable.transitRoutes()) {
				digest.update(Objects.toString(route.id(), "").getBytes(StandardCharsets.UTF_8));
			}
			for (TransitTrip trip : timetable.transitTrips()) {
				digest.update(Objects.toString(trip.id(), "").getBytes(StandardCharsets.UTF_8));
				digest.update(Objects.toString(trip.routeId(), "").getBytes(StandardCharsets.UTF_8));
			}
			ByteBuffer buffer = ByteBuffer.allocate(8);
			for (TransitStopTime stopTime : timetable.transitStopTimes()) {
				digest.update(Objects.toString(stopTime.tripId(), "").getBytes(StandardCharsets.UTF_8));
				digest.update(Objects.toString(stopTime.stationId(), "").getBytes(StandardCharsets.UTF_8));
				digest.update(Objects.toString(stopTime.lineId(), "").getBytes(StandardCharsets.UTF_8));
				buffer.clear();
				buffer.putInt(stopTime.departureSeconds()).putInt(stopTime.arrivalSeconds());
				digest.update(buffer.array());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
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
		return departureProfile(query, timetable, ignored -> realtimeOverlay, realtimeOverlay, planningLimits, null);
	}

	List<JourneyDepartureProfilePoint> departureProfile(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		RealtimeOverlay realtimeOverlay,
		JourneyProfileResourcePolicy.ProfilePlanningLimits planningLimits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		return departureProfile(query, timetable, ignored -> realtimeOverlay, realtimeOverlay, planningLimits, observations);
	}

	List<JourneyDepartureProfilePoint> departureProfile(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		RaptorRealtimeRuntimeView realtimeRuntime,
		JourneyProfileResourcePolicy.ProfilePlanningLimits planningLimits,
		JourneyProfilePruningObservationAccumulator observations
	) {
		Objects.requireNonNull(realtimeRuntime, "realtimeRuntime must not be null");
		return departureProfile(query, timetable, realtimeRuntime::realtimeOverlay, RealtimeOverlay.empty(),
			planningLimits, observations);
	}

	// accessOverlay는 진입·환승·출구 전환 선택에 적용하는 차단 집합이다(운행일과 무관).
	private List<JourneyDepartureProfilePoint> departureProfile(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		Function<LocalDate, RealtimeOverlay> overlays,
		RealtimeOverlay accessOverlay,
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
				Objects.requireNonNull(accessOverlay, "accessOverlay must not be null"),
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
		// #461: 날짜별 운행 집합만 고르고 패턴별 열차는 탐색이 그 패턴을 처음 볼 때 만든다(전 열차 사전 복사 없음).
		List<ProfileServiceDateBlock> blocks = new ArrayList<>();
		for (LocalDate nativeServiceDate = firstNativeServiceDate;; nativeServiceDate = nativeServiceDate.plusDays(1)) {
			ScanInput cancellationInput = scanInput(query, new ServiceDay(nativeServiceDate, 0));
			throwIfCancelled(cancellationInput);
			limits.consumeWork();
			ActiveServiceDay activeServiceDay = timetable.activeServiceDay(nativeServiceDate);
			if (!activeServiceDay.trips().isEmpty()) {
				blocks.add(new ProfileServiceDateBlock(nativeServiceDate, activeServiceDay,
					Objects.requireNonNull(overlays.apply(nativeServiceDate), "realtime overlay must not be null")));
			}
			if (nativeServiceDate.equals(lastNativeServiceDate)) break;
		}
		return new ProfileDatedTripOccurrences(List.copyOf(blocks));
	}

	private List<JourneyDepartureProfilePoint> departureProfileSlice(
		JourneyRaptorQuery query,
		CompiledTimetable timetable,
		ProfileDatedTripOccurrences datedTrips,
		LocalDate serviceDate,
		int earliestReadyAtSeconds,
		int latestReadyAtSeconds,
		RealtimeOverlay accessOverlay,
		ProfileLimitTracker limits
	) {
		if (earliestReadyAtSeconds > latestReadyAtSeconds) {
			return List.of();
		}
		ServiceDay serviceDay = new ServiceDay(serviceDate, latestReadyAtSeconds);
		ScanInput profileInput = scanInput(query, serviceDay);
		throwIfCancelled(profileInput);
		ProfileDatedTripView trips = datedTrips.forReadinessAnchor(serviceDay.date(), limits, timetable.routePatternCount());
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
			timetable, profileInput.originStationId()).stream().map(event -> readyAtBreakpoint(timetable, slackSeconds, event))
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
			profileInput, timetable, trips, accessOverlay, limits, destination,
			JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW_SECONDS);
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
				assignPersonas(itineraries),
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
		CompiledTimetable timetable,
		int slackSeconds,
		ProfileDepartureEvent event
	) {
		int boardingLine = timetable.lineIndex(event.trip().scheduledTrip().lineId(event.stopIndex()));
		if (boardingLine < 0) {
			return OptionalIntValue.empty();
		}
		// #454: 출발역 승강장에서 바로 타므로 준비 시각은 출발 - 승차 여유다(진입 시간 없음).
		return OptionalIntValue.of(event.effectiveDepartureSeconds() - slackSeconds);
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

		private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

		private final RouteTimetable source;
		private final String routeBundleSha256;
		private final long generation;
		private final Map<String, Integer> stationIndex;
		private final String[] stationIds;
		private final Map<String, Integer> routeIndex;
		private final Map<String, Integer> tripIndex;
		private final Map<String, Integer> lineIndex;
		private final int[][] stopsByPattern;
		private final int[][] patternsByStop;
		private final List<List<ScheduledTrip>> tripsByPattern;
		private final int[] patternByScheduledTrip;
		private final Map<DayOfWeek, List<ServiceCalendar>> calendarsByDay;
		private final Map<LocalDate, List<ServiceCalendarDate>> exceptionsByDate;
		private final List<ScheduledTrip> scheduledTrips;
		private final AccessTransitions accessTransitions;
		private final OutOfStationFootpath[][] footpathsByFromStation;
		private final OutOfStationFootpath[][] footpathsByToStationLine;
		private final OutOfStationFootpath[][] footpathsByToStation;
		private final int[][] minPatternRunningTimes;
		private final int[][] lineByPatternPosition;
		private final int[][] stopOccurrences;
		private final LinkedHashMap<LocalDate, ActiveServiceDay> activeServiceDays = new LinkedHashMap<>(16, 0.75f, true);
		private final int[] stationLineOffsets;
		private final int[] stationLines;
		private final int[] stationSlotOffsets;
		private final int totalStationSlots;

		private CompiledTimetable(RouteTimetable source) {
			this(source, computeTimetableDigest(source), 1L);
		}

		private CompiledTimetable(RouteTimetable source, String routeBundleSha256, long generation) {
			this.source = Objects.requireNonNull(source, "timetable must not be null");
			this.routeBundleSha256 = requireSha256(routeBundleSha256);
			if (generation < 1) {
				throw new IllegalArgumentException("generation must be positive");
			}
			this.generation = generation;
			stationIndex = denseIndex(source.transitStopTimes().stream().map(TransitStopTime::stationId).toList());
			stationIds = new String[stationIndex.size()];
			for (Map.Entry<String, Integer> entry : stationIndex.entrySet()) {
				stationIds[entry.getValue()] = entry.getKey();
			}
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
			stopsByPattern = routePatterns.stopsByPattern().toArray(int[][]::new);
			patternsByStop = invertPatterns(stopsByPattern, stationIndex.size());
			tripsByPattern = routePatterns.tripsByPattern();
			// 패턴 키에 노선 순서가 들어 있어 같은 패턴의 열차는 위치마다 노선이 같다. 탐색 내부 루프가 노선 ID 문자열로
			// 색인을 찾지 않도록 (패턴, 위치)별 노선 색인을 미리 둔다(#462).
			lineByPatternPosition = new int[stopsByPattern.length][];
			for (int pattern = 0; pattern < stopsByPattern.length; pattern += 1) {
				ScheduledTrip representative = tripsByPattern.get(pattern).getFirst();
				int[] lines = new int[stopsByPattern[pattern].length];
				for (int position = 0; position < lines.length; position += 1) {
					lines[position] = lineIndex.getOrDefault(representative.lineId(position), -1);
				}
				lineByPatternPosition[pattern] = lines;
			}
			// 역마다 (패턴, 위치) 쌍을 패턴 번호·위치 순으로 미리 둔다. 하한 Dijkstra가 역을 꺼낼 때마다 그 역을 지나는
			// 패턴의 정차 배열을 처음부터 훑어 위치를 찾지 않게 한다(#462).
			stopOccurrences = new int[stationIndex.size()][];
			for (int station = 0; station < stopOccurrences.length; station += 1) {
				int[] occurrences = new int[0];
				for (int pattern : patternsByStop[station]) {
					int[] stops = stopsByPattern[pattern];
					for (int position = 0; position < stops.length; position += 1) {
						if (stops[position] == station) {
							occurrences = Arrays.copyOf(occurrences, occurrences.length + 2);
							occurrences[occurrences.length - 2] = pattern;
							occurrences[occurrences.length - 1] = position;
						}
					}
				}
				stopOccurrences[station] = occurrences;
			}
			patternByScheduledTrip = new int[scheduledTrips.size()];
			Arrays.fill(patternByScheduledTrip, -1);
			for (int pattern = 0; pattern < tripsByPattern.size(); pattern += 1) {
				for (ScheduledTrip trip : tripsByPattern.get(pattern)) {
					patternByScheduledTrip[trip.index()] = pattern;
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
			List<List<OutOfStationFootpath>> toStationList = new ArrayList<>(numStations);
			for (int i = 0; i < numStations; i += 1) {
				toStationList.add(new ArrayList<>());
			}
			for (OutOfStationFootpath footpath : accessTransitions.outOfStationFootpaths()) {
				fromList.get(footpath.fromStation()).add(footpath);
				toList.get(footpath.toStation() * numLines + footpath.toLine()).add(footpath);
				toStationList.get(footpath.toStation()).add(footpath);
			}
			footpathsByFromStation = new OutOfStationFootpath[numStations][];
			for (int i = 0; i < numStations; i += 1) {
				List<OutOfStationFootpath> list = fromList.get(i);
				footpathsByFromStation[i] = list.isEmpty() ? null : list.toArray(OutOfStationFootpath[]::new);
			}
			footpathsByToStation = new OutOfStationFootpath[numStations][];
			for (int i = 0; i < numStations; i += 1) {
				List<OutOfStationFootpath> list = toStationList.get(i);
				footpathsByToStation[i] = list.isEmpty() ? null : list.toArray(OutOfStationFootpath[]::new);
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

			minPatternRunningTimes = new int[stopsByPattern.length][];
			for (int pattern = 0; pattern < stopsByPattern.length; pattern += 1) {
				int numStops = stopsByPattern[pattern].length;
				int[] minHops = new int[numStops * numStops];
				Arrays.fill(minHops, Integer.MAX_VALUE / 2);
				List<ScheduledTrip> pTrips = tripsByPattern.get(pattern);
				for (ScheduledTrip trip : pTrips) {
					for (int i = 0; i < numStops; i += 1) {
						for (int j = i + 1; j < numStops; j += 1) {
							int dur = trip.arrivalSeconds(j) - trip.departureSeconds(i);
							int idx = i * numStops + j;
							if (dur < minHops[idx]) {
								minHops[idx] = dur;
							}
						}
					}
				}
				minPatternRunningTimes[pattern] = minHops;
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

		/** 패턴의 대표 열차. 같은 패턴은 정차역·노선·승하차 허용이 같다(패턴 키). */
		ScheduledTrip patternRepresentative(int pattern) {
			return tripsByPattern.get(pattern).getFirst();
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

		int minPatternRunningTime(int pattern, int fromPos, int toPos) {
			// 없는 패턴(음수 포함)은 도달 불가 값이다. 부호 없는 비교로 음수도 범위 밖으로 본다.
			if (Integer.compareUnsigned(pattern, minPatternRunningTimes.length) >= 0) {
				return Integer.MAX_VALUE / 2;
			}
			return minPatternRunningTimes[pattern][fromPos * stopsByPattern[pattern].length + toPos];
		}

		OutOfStationFootpath[] footpathsToStation(int station) {
			return (station >= 0 && station < footpathsByToStation.length) ? footpathsByToStation[station] : null;
		}


		RouteTimetable source() {
			return source;
		}

		String routeBundleSha256() {
			return routeBundleSha256;
		}

		long generation() {
			return generation;
		}

		private static String requireSha256(String value) {
			Objects.requireNonNull(value, "routeBundleSha256 must not be null");
			if (!SHA256_PATTERN.matcher(value).matches()) {
				throw new IllegalArgumentException("routeBundleSha256 must be lowercase SHA-256");
			}
			return value;
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

		String stationId(int station) {
			return station >= 0 && station < stationIds.length ? stationIds[station] : null;
		}

		int lineIndex(String lineId) {
			return lineIndex.getOrDefault(lineId, -1);
		}

		int[] transitionIdsForEdge(String edgeId) {
			return accessTransitions.transitionIdsForEdge(edgeId);
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
		int selectTransition(
			int[] candidates,
			int profileBit,
			boolean ignoreBlocked,
			boolean requireVerifiedDistance,
			RealtimeOverlay realtimeOverlay
		) {
			return accessTransitions.select(
				candidates, profileBit, ignoreBlocked, requireVerifiedDistance, requireVerifiedDistance, realtimeOverlay);
		}
		int[] transferTransitions(int station, int fromLine, int toLine) {
			return accessTransitions.transferCandidates(station, fromLine, toLine);
		}
		int allocatedTransferSlotCount() {
			return accessTransitions.allocatedTransferSlotCount();
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
		List<AlightingCarDoor> selectAlightingCarDoors(
			String stationId,
			String lineId,
			String directionId,
			boolean nextIsTransfer,
			boolean stepFree
		) {
			if (stationId == null || lineId == null) {
				return List.of();
			}
			// BOTH는 공식 원천이 방향 구분 없이 제공한 힌트라 모든 트립에 적용한다.
			// UP/DOWN은 공식 up/down 트립에만 대응하며 INNER/OUTER와 그 외 방향은 추정하지 않는다.
			String targetDirection = "up".equals(directionId) ? "UP" : "down".equals(directionId) ? "DOWN" : null;
			List<com.easysubway.route.application.port.out.LoadRouteTimetablePort.CarDoorHint> allHints =
				source.routeAccessData().carDoorHints();
			if (allHints.isEmpty()) {
				return List.of();
			}
			return allHints.stream()
				.filter(h -> stationId.equals(h.stationId())
					&& lineId.equals(h.lineId())
					&& ("BOTH".equals(h.direction()) || (targetDirection != null && targetDirection.equals(h.direction()))))
				.filter(h -> {
					if (nextIsTransfer) {
						return "TRANSFER".equals(h.targetFacilityType());
					} else {
						if (stepFree) {
							return "ELEVATOR".equals(h.targetFacilityType());
						} else {
							return "ELEVATOR".equals(h.targetFacilityType())
								|| "ESCALATOR".equals(h.targetFacilityType())
								|| "STAIR".equals(h.targetFacilityType());
						}
					}
				})
				.map(h -> new AlightingCarDoor(h.carNumber(), h.doorNumber(), h.targetFacilityType()))
				.distinct()
				.sorted(Comparator.comparing(AlightingCarDoor::targetFacilityType)
					.thenComparingInt(AlightingCarDoor::carNumber)
					.thenComparingInt(AlightingCarDoor::doorNumber))
				.toList();
		}

		// 공식 상·하행(up/down) trip만 UP/DOWN 행에 대응한다. increasing/decreasing 등은 방향 근거가 없어 싣지 않는다.
		List<com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap> platformGaps(
			String stationId, String lineId, String directionId
		) {
			String direction = "up".equals(directionId) ? "UP" : "down".equals(directionId) ? "DOWN" : null;
			if (stationId == null || lineId == null || direction == null) {
				return List.of();
			}
			return source.routeAccessData().platformGaps().getOrDefault(
				new com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGapKey(
					stationId, lineId, direction),
				List.of());
		}

		String tripDirection(String tripId) {
			Integer idx = tripIndex.get(tripId);
			if (idx == null) return null;
			return source.transitTrips().get(idx).directionId();
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
			return stopsByPattern.length;
		}

		/** 없는 패턴(음수 포함)은 {@code null}이다(#462 배열 전환 전 계약). */
		int[] stopsByPattern(int pattern) {
			return Integer.compareUnsigned(pattern, stopsByPattern.length) < 0 ? stopsByPattern[pattern] : null;
		}

		int patternStopCount(int pattern) {
			return stopsByPattern(pattern).length;
		}

		/**
		 * 역이 나오는 (패턴, 위치) 쌍을 이어 붙인 배열. 패턴 번호, 같은 패턴 안에서는 위치 순이다. 하한 Dijkstra 내부
		 * 루프용이라 범위를 검사하지 않는다. 역 번호는 {@code [0, stationCount())} 안이어야 한다.
		 */
		int[] stopOccurrences(int station) {
			return stopOccurrences[station];
		}

		/**
		 * 패턴의 위치 쌍별 최소 주행 시간({@code from * 정차 수 + to}). 내부 루프용이라 범위를 검사하지 않는다. 패턴
		 * 번호는 {@code [0, routePatternCount())} 안이어야 한다(없는 패턴의 도달 불가 값은 {@link #minPatternRunningTime}).
		 */
		int[] minPatternRunningTimes(int pattern) {
			return minPatternRunningTimes[pattern];
		}

		/**
		 * 패턴의 그 위치에서 타는 노선 색인. 같은 패턴의 모든 열차가 같은 값을 갖는다. 탐색 내부 루프용이라 범위를
		 * 검사하지 않는다. 패턴과 위치는 유효한 패턴의 정차 위치여야 한다.
		 */
		int patternLine(int pattern, int position) {
			return lineByPatternPosition[pattern][position];
		}

		/** 없는 역(음수 포함)은 빈 배열이다(#462 배열 전환 전 계약). */
		int[] patternsByStop(int station) {
			return Integer.compareUnsigned(station, patternsByStop.length) < 0 ? patternsByStop[station] : NO_PATTERNS;
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
			return tripsByPattern.stream().mapToInt(List::size).sum();
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
			List<List<ScheduledTrip>> activeTripsByPattern = tripsByPattern.stream()
				.map(trips -> trips.stream()
					.filter(trip -> activeServiceIds.contains(trip.trip().serviceId()))
					.toList())
				.toList();
			ActiveServiceDay compiled = new ActiveServiceDay(activeTrips, activeTripsByPattern);
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
			List<int[]> stopsByPattern = new ArrayList<>();
			List<List<ScheduledTrip>> tripsByPattern = new ArrayList<>();
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
					stopsByPattern.add(entry.getKey().stationSequence().stream().mapToInt(Integer::intValue).toArray());
					tripsByPattern.add(List.copyOf(group));
				}
			}
			return new CompiledRoutePatterns(List.copyOf(stopsByPattern), List.copyOf(tripsByPattern));
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

		private static int[][] invertPatterns(int[][] stopsByPattern, int stationCount) {
			List<List<Integer>> patternLists = new ArrayList<>(stationCount);
			for (int index = 0; index < stationCount; index += 1) {
				patternLists.add(new ArrayList<>());
			}
			for (int pattern = 0; pattern < stopsByPattern.length; pattern += 1) {
				for (int station : stopsByPattern[pattern]) {
					List<Integer> patterns = patternLists.get(station);
					if (!patterns.contains(pattern)) {
						patterns.add(pattern);
					}
				}
			}
			int[][] patternsByStop = new int[stationCount][];
			for (int index = 0; index < patternLists.size(); index += 1) {
				patternsByStop[index] = patternLists.get(index).isEmpty() ? NO_PATTERNS
					: patternLists.get(index).stream().mapToInt(Integer::intValue).sorted().toArray();
			}
			return patternsByStop;
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
		private final long[] transferKeys;
		/**
		 * #462: (역, 출발 노선)마다 정렬된 {@link #transferKeys}에서 그 접두사가 시작하는 칸. 칸 {@code p}의 범위는
		 * {@code [transferRangeStart[p], transferRangeStart[p + 1])}이고 그 안에서 도착 노선만 찾는다.
		 */
		private final int[] transferRangeStart;
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
			long[] transferKeys,
			int[][] transferTransitions,
			List<Candidate> candidates,
			boolean[] outOfStation,
			List<OutOfStationFootpath> outOfStationFootpaths,
			int unsupportedTransferCount
		) {
			this.stationCount = stationCount;
			this.lineCount = lineCount;
			this.transferKeys = transferKeys;
			this.transferRangeStart = new int[stationCount * lineCount + 1];
			for (long key : transferKeys) {
				transferRangeStart[(int) (key / lineCount) + 1] += 1;
			}
			for (int prefix = 0; prefix < stationCount * lineCount; prefix += 1) {
				transferRangeStart[prefix + 1] += transferRangeStart[prefix];
			}
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
			// #454: 진입·하차(ENTRY/EXIT) 간선은 번들에 남아 있어도 전환으로 만들지 않는다(하위 호환 무시).
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
			// 출발·도착 승강장 경계(PLATFORM_BOUNDARY): 이동 없음, 경고·차단 없음, 간선 없음.
			flattened.add(new Candidate(0, 0, 0, 0, (byte) 0, false, null, PLATFORM_BOUNDARY_STATUS));

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
			return new AccessTransitions(stationCount, lineCount, transferKeys, transferIds, flattened, outOfStation, outOfStationFootpaths, unsupported);
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
				&& "VERIFIED".equals(rule.verificationStatus());
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
				|| "STALE".equals(rule.verificationStatus())) {
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
			List<String> statuses = List.of(
				edge.verificationStatus(), evidence.verificationStatus(), rule.verificationStatus());
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
		private static long transferKey(int station, int fromLine, int toLine, int lineCount) {
			return (((long) station) * lineCount + fromLine) * lineCount + toLine;
		}
		int[] transitionIdsForEdge(String edgeId) {
			if (edgeId == null) {
				return NO_TRANSITIONS;
			}
			int[] ids = edgeTransitions.get(edgeId);
			return ids != null ? ids : NO_TRANSITIONS;
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
			boolean requireMeasurement,
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
						&& (!requireMeasurement || hasMeasurement(transition))
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
		/**
		 * 검증 환승의 근거 측정값이 있는지. 공식 거리가 있거나, 거리 없이 공식 실측 소요시간만 있는 경우다
		 * (#454·data#876, 서울교통공사 15098252). 둘 다 없으면 시간을 계산할 근거가 없어 쓰지 않는다.
		 */
		private boolean hasMeasurement(int transition) {
			return distanceMeters[transition] > 0 || durationSeconds[transition] > 0;
		}
		private boolean isPreferredVerifiedTransition(int candidate, int selected, int profileBit) {
			boolean preferStepFree = prefersStepFree(profileBit);
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
		int allocatedTransferSlotCount() {
			return transferKeys.length;
		}
		private int[] transferCandidates(int station, int fromLine, int toLine) {
			if (station < 0 || station >= stationCount || fromLine < 0 || fromLine >= lineCount || toLine < 0 || toLine >= lineCount) {
				return NO_TRANSITIONS;
			}
			long key = transferKey(station, fromLine, toLine, lineCount);
			int prefix = station * lineCount + fromLine;
			for (int index = transferRangeStart[prefix]; index < transferRangeStart[prefix + 1]; index += 1) {
				if (transferKeys[index] == key) {
					return transferTransitions[index];
				}
			}
			return NO_TRANSITIONS;
		}
		private boolean isEligible(
			int transition, int profileBit, boolean ignoreBlocked, boolean requireVerifiedDistance, boolean requireMeasurement
		) {
			if (transition < 0 || transition >= blockedProfiles.length) {
				return false;
			}
			if (!ignoreBlocked && (blockedProfiles[transition] & profileBit) != 0) {
				return false;
			}
			if (requireVerifiedDistance) {
				if (requireMeasurement && !hasMeasurement(transition)) {
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
		private final List<List<ScheduledTrip>> tripsByPattern;
		private volatile Map<String, List<BoardingStop>> boardingsByStation;

		private ActiveServiceDay(List<ScheduledTrip> trips, List<List<ScheduledTrip>> tripsByPattern) {
			this.trips = List.copyOf(trips);
			this.tripsByPattern = tripsByPattern;
		}

		private List<ScheduledTrip> trips() {
			return trips;
		}

		/** 없는 패턴(음수 포함)은 빈 목록이다(#462 배열 전환 전 계약). */
		List<ScheduledTrip> tripsByPattern(int pattern) {
			return Integer.compareUnsigned(pattern, tripsByPattern.size()) < 0 ? tripsByPattern.get(pattern) : List.of();
		}

		/** 컴파일 시간표의 패턴 수. 비운행 패턴도 빈 목록으로 칸을 갖는다. */
		int patternCount() {
			return tripsByPattern.size();
		}

		int routePatternTripLinkCount() {
			return tripsByPattern.stream()
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
		int[] lowerBounds = null;

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

		private int[] touchedSlots = new int[0];
		private int touchedSlotCount;

		void recordTouchedSlot(int slot) {
			if (touchedSlotCount >= touchedSlots.length) {
				touchedSlots = Arrays.copyOf(touchedSlots, Math.max(32, touchedSlots.length * 2));
			}
			touchedSlots[touchedSlotCount++] = slot;
		}

		int touchedSlotCount() {
			return touchedSlotCount;
		}

		int parentTrip(int slot) {
			return parentTrip[slot];
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
			boolean bufferReallocated = false;
			if (arrivalSeconds.length < labelSlots) {
				arrivalSeconds = new int[labelSlots];
				parentTrip = new int[labelSlots];
				parentBoardStop = new int[labelSlots];
				parentAlightStop = new int[labelSlots];
				parentAccessTransition = new int[labelSlots];
				parentLabelSlot = new int[labelSlots];
				warningBits = new byte[labelSlots];
				bufferReallocated = true;
			}
			boolean markedReallocated = false;
			if (markedStops.length < requiredStationCount) {
				markedStops = new int[requiredStationCount];
				nextMarkedStops = new int[requiredStationCount];
				marked = new boolean[requiredStationCount];
				nextMarked = new boolean[requiredStationCount];
				markedReallocated = true;
			}
			if (markedPatterns.length < patternCount) {
				markedPatterns = new int[patternCount];
				firstMarkedPosition = new int[patternCount];
			}
			epoch += 1;
			targetStation = -1;
			lowerBounds = null;
			Arrays.fill(bestTargetArrivalSeconds, 0, WARNING_STATE_COUNT, UNREACHED);

			if (bufferReallocated || touchedSlotCount == 0) {
				Arrays.fill(arrivalSeconds, 0, labelSlots, UNREACHED);
				Arrays.fill(parentTrip, 0, labelSlots, -1);
				Arrays.fill(parentBoardStop, 0, labelSlots, -1);
				Arrays.fill(parentAlightStop, 0, labelSlots, -1);
				Arrays.fill(parentAccessTransition, 0, labelSlots, -1);
				Arrays.fill(parentLabelSlot, 0, labelSlots, -1);
				Arrays.fill(warningBits, 0, labelSlots, (byte) 0);
				touchedSlotCount = 0;
			} else {
				for (int i = 0; i < touchedSlotCount; i += 1) {
					int s = touchedSlots[i];
					arrivalSeconds[s] = UNREACHED;
					parentTrip[s] = -1;
					parentBoardStop[s] = -1;
					parentAlightStop[s] = -1;
					parentAccessTransition[s] = -1;
					parentLabelSlot[s] = -1;
					warningBits[s] = (byte) 0;
				}
				touchedSlotCount = 0;
			}

			if (markedReallocated || markedStopCount == 0) {
				Arrays.fill(marked, 0, requiredStationCount, false);
				markedStopCount = 0;
			} else {
				for (int i = 0; i < markedStopCount; i += 1) {
					marked[markedStops[i]] = false;
				}
				markedStopCount = 0;
			}
			if (markedReallocated || nextMarkedStopCount == 0) {
				Arrays.fill(nextMarked, 0, requiredStationCount, false);
				nextMarkedStopCount = 0;
			} else {
				for (int i = 0; i < nextMarkedStopCount; i += 1) {
					nextMarked[nextMarkedStops[i]] = false;
				}
				nextMarkedStopCount = 0;
			}
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
			setTargetStation(destination, null);
		}

		void setTargetStation(int destination, int[] bounds) {
			targetStation = destination;
			lowerBounds = bounds;
		}

		boolean isDominatedByTarget(int station, int candidateArrivalSeconds, int candidateWarningState) {
			int lb = (lowerBounds != null && station >= 0 && station < lowerBounds.length) ? lowerBounds[station] : 0;
			long estimatedArrival = (long) candidateArrivalSeconds + lb;
			for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
				if ((warningState & candidateWarningState) == warningState) {
					int best = bestTargetArrivalSeconds[warningState];
					if (best != UNREACHED && (station == targetStation ? best < candidateArrivalSeconds : best <= estimatedArrival)) {
						return true;
					}
				}
			}
			return false;
		}

		boolean isTargetDominatingDeparture(int station, int earliestDepartureSeconds) {
			int lb = (lowerBounds != null && station >= 0 && station < lowerBounds.length) ? lowerBounds[station] : 0;
			long estimatedArrival = (long) earliestDepartureSeconds + lb;
			boolean anyTargetReached = false;
			for (int warningState = 0; warningState < WARNING_STATE_COUNT; warningState += 1) {
				int best = bestTargetArrivalSeconds[warningState];
				if (best != UNREACHED) {
					anyTargetReached = true;
					if (station == targetStation ? earliestDepartureSeconds <= best : estimatedArrival < best) {
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

		boolean improveOrigin(int origin, int readyAtSeconds) {
			int slot = slot(0, origin, noIncomingLine(), 0);
			if (arrivalSeconds[slot] <= readyAtSeconds) {
				return false;
			}
			if (arrivalSeconds[slot] == UNREACHED) {
				recordTouchedSlot(slot);
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
			if (candidateSlot >= totalSlots()) {
				return;
			}
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
			if (existingArrivalSeconds == UNREACHED) {
				recordTouchedSlot(candidateSlot);
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

	/**
	 * Lock-free, bounded workspace pool for {@link ScanWorkspace}.
	 *
	 * <p>Under Virtual Threads, {@link ThreadLocal} storage leads to per-request workspace allocations
	 * (~28MB per request for Seoul Metro topology) and severe GC churn because virtual threads are
	 * ephemeral and discarded after task completion. This pool decouples workspace lifecycle from
	 * thread lifecycle, allowing virtual threads to borrow pre-allocated workspaces, reuse memory,
	 * and return them safely.</p>
	 */
	public static final class ScanWorkspacePool {

		private static final int DEFAULT_MAX_IDLE_WORKSPACES = Math.max(16, Runtime.getRuntime().availableProcessors() * 2);

		private static final class SharedHolder {
			private static final ScanWorkspacePool INSTANCE = new ScanWorkspacePool(DEFAULT_MAX_IDLE_WORKSPACES);
		}

		private final int maxIdleWorkspaces;
		private final ConcurrentLinkedQueue<ScanWorkspace> idleWorkspaces = new ConcurrentLinkedQueue<>();
		private final AtomicInteger idleCount = new AtomicInteger();
		private final AtomicInteger totalAllocated = new AtomicInteger();

		public static ScanWorkspacePool shared() {
			return SharedHolder.INSTANCE;
		}

		public ScanWorkspacePool() {
			this(DEFAULT_MAX_IDLE_WORKSPACES);
		}

		public ScanWorkspacePool(int maxIdleWorkspaces) {
			if (maxIdleWorkspaces <= 0) {
				throw new IllegalArgumentException("maxIdleWorkspaces must be positive: " + maxIdleWorkspaces);
			}
			this.maxIdleWorkspaces = maxIdleWorkspaces;
		}

		ScanWorkspace acquire() {
			ScanWorkspace workspace = idleWorkspaces.poll();
			if (workspace != null) {
				idleCount.decrementAndGet();
				return workspace;
			}
			totalAllocated.incrementAndGet();
			return new ScanWorkspace();
		}

		void release(ScanWorkspace workspace) {
			if (workspace == null) {
				return;
			}
			while (true) {
				int current = idleCount.get();
				if (current >= maxIdleWorkspaces) {
					return;
				}
				if (idleCount.compareAndSet(current, current + 1)) {
					idleWorkspaces.offer(workspace);
					return;
				}
			}
		}

		public int idleCount() {
			return Math.max(0, idleCount.get());
		}

		public int maxIdleWorkspaces() {
			return maxIdleWorkspaces;
		}

		public int totalAllocated() {
			return totalAllocated.get();
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
	private record ProfileDatedTripOccurrences(List<ProfileServiceDateBlock> blocks) {
		private ProfileDatedTripView forReadinessAnchor(LocalDate anchorDate, ProfileLimitTracker limits, int patternCount) {
			return new ProfileDatedTripView(blocks, anchorDate, limits, patternCount);
		}
	}

	/** 한 원본 운행일의 활성 운행. 패턴 안에서는 정류장마다 출발·도착이 줄지 않는다(비추월 묶음). */
	private record ProfileServiceDateBlock(
		LocalDate nativeServiceDate,
		ActiveServiceDay activeServiceDay,
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

	/**
	 * 한 패턴의 날짜별 열차 묶음. {@code blockStarts[i]}부터 다음 묶음 시작 전까지가 i번째 운행일이다.
	 * {@code ordered[i]}가 참이면 그 묶음은 시각 순서가 보장되어(실시간 변경 없음) 이분 탐색과 조기 종료를 쓸 수 있다.
	 */
	private static final class ProfilePatternTrips {
		private final List<ProfileDatedTrip> trips;
		private final int[] blockStarts;
		private final boolean[] ordered;

		private ProfilePatternTrips(List<ProfileDatedTrip> trips, int[] blockStarts, boolean[] ordered) {
			this.trips = trips;
			this.blockStarts = blockStarts;
			this.ordered = ordered;
		}

		private List<ProfileDatedTrip> trips() {
			return trips;
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
	}

	private static final class ProfileDatedTripView {
		private final List<ProfileServiceDateBlock> blocks;
		private final LocalDate anchorDate;
		private final ProfileLimitTracker limits;
		/** 패턴 번호로 바로 찾는 질의 단위 캐시(#462). 처음 보는 패턴만 펼친다. */
		private final ProfilePatternTrips[] byPattern;

		private ProfileDatedTripView(
			List<ProfileServiceDateBlock> blocks, LocalDate anchorDate, ProfileLimitTracker limits, int patternCount
		) {
			this.blocks = blocks;
			this.anchorDate = anchorDate;
			this.limits = limits;
			this.byPattern = new ProfilePatternTrips[patternCount];
		}

		private boolean isEmpty() {
			return blocks.isEmpty();
		}

		private List<RealtimeOverlay> overlays() {
			return blocks.stream().map(ProfileServiceDateBlock::realtimeOverlay).toList();
		}

		/** 탐색이 처음 보는 패턴만 날짜별 열차로 펼친다. 펼친 열차마다 작업량 1이다. */
		private ProfilePatternTrips tripsByPattern(int pattern) {
			ProfilePatternTrips cached = byPattern[pattern];
			if (cached != null) return cached;
			List<ProfileDatedTrip> trips = new ArrayList<>();
			int[] starts = new int[blocks.size()];
			boolean[] ordered = new boolean[blocks.size()];
			for (int index = 0; index < blocks.size(); index += 1) {
				ProfileServiceDateBlock block = blocks.get(index);
				starts[index] = trips.size();
				ordered[index] = !block.realtimeOverlay().affectsPattern(pattern);
				int offset = Math.toIntExact(Duration.between(anchorDate.atStartOfDay(SERVICE_ZONE),
					block.nativeServiceDate().atStartOfDay(SERVICE_ZONE)).toSeconds());
				for (ScheduledTrip trip : block.activeServiceDay().tripsByPattern(pattern)) {
					limits.consumeWork();
					trips.add(new ProfileDatedTrip(block.nativeServiceDate(), trip, block.realtimeOverlay(), offset));
				}
			}
			ProfilePatternTrips value = new ProfilePatternTrips(List.copyOf(trips), starts, ordered);
			byPattern[pattern] = value;
			return value;
		}

		private List<ProfileDepartureEvent> departureEvents(CompiledTimetable timetable, String originStationId) {
			List<ProfileDepartureEvent> events = new ArrayList<>();
			int origin = timetable.stationIndex(originStationId);
			if (origin < 0) return List.of();
			for (int pattern : timetable.patternsByStop(origin)) {
				for (ProfileDatedTrip trip : tripsByPattern(pattern).trips()) {
					if (trip.cancelled()) continue;
					for (int stopIndex = 0; stopIndex < trip.stopTimes().size(); stopIndex += 1) {
						if (!originStationId.equals(trip.stopTimes().get(stopIndex).stationId())
							|| !trip.allowsPickup(stopIndex)) continue;
						events.add(new ProfileDepartureEvent(trip, stopIndex, trip.departureSeconds(stopIndex)));
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

	static final class PrimitiveProfileLabelPool {
		static final int NO_TRANSFER_SLACK = Integer.MAX_VALUE;

		static int encodeSlack(JourneyProfileRaptorPort.ConnectionSlack s) {
			if (s == null || s instanceof JourneyProfileRaptorPort.NoTransfer) {
				return NO_TRANSFER_SLACK;
			}
			if (s instanceof JourneyProfileRaptorPort.MinimumTransferSeconds m) {
				long sec = m.seconds();
				if (sec >= NO_TRANSFER_SLACK) {
					throw new IllegalStateException("transfer slack exceeds max representation: " + sec);
				}
				return (int) sec;
			}
			throw new IllegalArgumentException("Unknown ConnectionSlack: " + s);
		}

		static JourneyProfileRaptorPort.ConnectionSlack decodeSlack(int slack) {
			if (slack == NO_TRANSFER_SLACK) {
				return new JourneyProfileRaptorPort.NoTransfer();
			}
			return new JourneyProfileRaptorPort.MinimumTransferSeconds(slack);
		}

		private int capacity;
		private int size;

		int[] startSeconds;
		int[] arrivalSeconds;
		int[] boardings;
		int[] station;
		int[] incomingLine;
		byte[] warningBits;
		int[] accessSeconds;
		int[] accessDistanceMeters;
		int[] stairBurden;
		int[] slackSeconds;
		int[] parentIndex;
		int[] tripIndex;
		int[] fromStopIndex;
		int[] toStopIndex;
		int[] transition;
		LocalDate[] serviceDate;
		ProfileDatedTrip[] trip;
		String[] traceKeys;

		PrimitiveProfileLabelPool(int initialCapacity) {
			this.capacity = Math.max(1, initialCapacity);
			this.size = 0;
			initArrays(this.capacity);
		}

		private void initArrays(int cap) {
			startSeconds = new int[cap];
			arrivalSeconds = new int[cap];
			boardings = new int[cap];
			station = new int[cap];
			incomingLine = new int[cap];
			warningBits = new byte[cap];
			accessSeconds = new int[cap];
			accessDistanceMeters = new int[cap];
			stairBurden = new int[cap];
			slackSeconds = new int[cap];
			parentIndex = new int[cap];
			tripIndex = new int[cap];
			fromStopIndex = new int[cap];
			toStopIndex = new int[cap];
			transition = new int[cap];
			serviceDate = new LocalDate[cap];
			trip = new ProfileDatedTrip[cap];
			traceKeys = new String[cap];
		}

		private void ensureCapacity(int minCapacity) {
			if (minCapacity <= capacity) {
				return;
			}
			int newCap = Math.max(minCapacity, capacity * 2);
			startSeconds = Arrays.copyOf(startSeconds, newCap);
			arrivalSeconds = Arrays.copyOf(arrivalSeconds, newCap);
			boardings = Arrays.copyOf(boardings, newCap);
			station = Arrays.copyOf(station, newCap);
			incomingLine = Arrays.copyOf(incomingLine, newCap);
			warningBits = Arrays.copyOf(warningBits, newCap);
			accessSeconds = Arrays.copyOf(accessSeconds, newCap);
			accessDistanceMeters = Arrays.copyOf(accessDistanceMeters, newCap);
			stairBurden = Arrays.copyOf(stairBurden, newCap);
			slackSeconds = Arrays.copyOf(slackSeconds, newCap);
			parentIndex = Arrays.copyOf(parentIndex, newCap);
			tripIndex = Arrays.copyOf(tripIndex, newCap);
			fromStopIndex = Arrays.copyOf(fromStopIndex, newCap);
			toStopIndex = Arrays.copyOf(toStopIndex, newCap);
			transition = Arrays.copyOf(transition, newCap);
			serviceDate = Arrays.copyOf(serviceDate, newCap);
			trip = Arrays.copyOf(trip, newCap);
			traceKeys = Arrays.copyOf(traceKeys, newCap);
			capacity = newCap;
		}

		int allocate(
			int start,
			int arrival,
			int boardingsCount,
			int stationIndex,
			int lineIndex,
			byte warnings,
			int accessSec,
			int accessMeters,
			int stairs,
			int slack,
			int parent,
			int tripIdx,
			int fromStop,
			int toStop,
			int trans,
			LocalDate date,
			ProfileDatedTrip datedTrip,
			JourneyProfileRaptorPort.ConnectionSlack slackObj
		) {
			ensureCapacity(size + 1);
			int idx = size++;
			startSeconds[idx] = start;
			arrivalSeconds[idx] = arrival;
			boardings[idx] = boardingsCount;
			station[idx] = stationIndex;
			incomingLine[idx] = lineIndex;
			warningBits[idx] = warnings;
			accessSeconds[idx] = accessSec;
			accessDistanceMeters[idx] = accessMeters;
			stairBurden[idx] = stairs;
			slackSeconds[idx] = slackObj != null ? encodeSlack(slackObj) : slack;
			parentIndex[idx] = parent;
			tripIndex[idx] = datedTrip != null && datedTrip.scheduledTrip() != null ? datedTrip.scheduledTrip().index() : tripIdx;
			fromStopIndex[idx] = fromStop;
			toStopIndex[idx] = toStop;
			transition[idx] = trans;
			serviceDate[idx] = datedTrip != null && datedTrip.nativeServiceDate() != null ? datedTrip.nativeServiceDate() : date;
			trip[idx] = datedTrip;
			traceKeys[idx] = null;
			return idx;
		}

		int allocate(
			int start,
			int arrival,
			int boardingsCount,
			int stationIndex,
			int lineIndex,
			byte warnings,
			int accessSec,
			int accessMeters,
			int stairs,
			int slack,
			int parent,
			int tripIdx,
			int fromStop,
			int toStop,
			int trans,
			LocalDate date,
			ProfileDatedTrip datedTrip
		) {
			return allocate(start, arrival, boardingsCount, stationIndex, lineIndex, warnings,
				accessSec, accessMeters, stairs, slack, parent, tripIdx, fromStop, toStop, trans, date, datedTrip, null);
		}

		/** 방금 만든 마지막 라벨이 상태에 들어가지 못했으면 자리를 되돌린다(참조가 없음). */
		void discardLast(int index) {
			if (index != size - 1) throw new IllegalStateException("only the last allocated label can be discarded");
			trip[index] = null;
			serviceDate[index] = null;
			traceKeys[index] = null;
			size -= 1;
		}

		/**
		 * 상태 지배. 출발 범위 탐색은 늦은 준비 시각부터 처리하므로 상태에 있는 모든 라벨의 시작은 지금과 이후 모든
		 * 시점의 준비 시각 이상이다. 그래서 시작 시각은 어떤 시점의 결과도 가르지 않으며(#461) 지배 차원에서 뺀다.
		 */
		static boolean dominates(PrimitiveProfileLabelPool pool, int left, int right) {
			if (left == right) {
				return false;
			}
			int lArrival = pool.arrivalSeconds[left];
			int rArrival = pool.arrivalSeconds[right];
			int lAccessSec = pool.accessSeconds[left];
			int rAccessSec = pool.accessSeconds[right];
			int lAccessMeters = pool.accessDistanceMeters[left];
			int rAccessMeters = pool.accessDistanceMeters[right];
			int lStairs = pool.stairBurden[left];
			int rStairs = pool.stairBurden[right];
			int lSlack = pool.slackSeconds[left];
			int rSlack = pool.slackSeconds[right];
			byte lWarnings = pool.warningBits[left];
			byte rWarnings = pool.warningBits[right];
			int lBoardings = pool.boardings[left];
			int rBoardings = pool.boardings[right];

			boolean noWorse = lArrival <= rArrival
				&& lAccessSec <= rAccessSec
				&& lAccessMeters <= rAccessMeters
				&& lStairs <= rStairs
				&& lSlack >= rSlack
				&& lBoardings <= rBoardings
				&& (lWarnings & rWarnings) == lWarnings;

			if (!noWorse) {
				return false;
			}

			return lArrival < rArrival
				|| lAccessSec < rAccessSec
				|| lAccessMeters < rAccessMeters
				|| lStairs < rStairs
				|| lSlack > rSlack
				|| lBoardings < rBoardings
				|| lWarnings != rWarnings;
		}

		static boolean sameVector(PrimitiveProfileLabelPool pool, int left, int right) {
			if (left == right) {
				return true;
			}
			return pool.arrivalSeconds[left] == pool.arrivalSeconds[right]
				&& pool.boardings[left] == pool.boardings[right]
				&& pool.accessSeconds[left] == pool.accessSeconds[right]
				&& pool.accessDistanceMeters[left] == pool.accessDistanceMeters[right]
				&& pool.stairBurden[left] == pool.stairBurden[right]
				&& pool.slackSeconds[left] == pool.slackSeconds[right]
				&& pool.warningBits[left] == pool.warningBits[right];
		}

		static String traceKey(PrimitiveProfileLabelPool pool, int index) {
			if (index < 0) {
				return "";
			}
			if (pool.traceKeys[index] != null) {
				return pool.traceKeys[index];
			}
			int parent = pool.parentIndex[index];
			String parentKey = parent >= 0 ? traceKey(pool, parent) : "";
			if (pool.trip[index] == null && pool.tripIndex[index] < 0) {
				return pool.traceKeys[index] = parentKey;
			}
			return pool.traceKeys[index] = parentKey + '/' + pool.serviceDate[index] + ':'
				+ pool.tripIndex[index] + ':' + pool.fromStopIndex[index]
				+ ':' + pool.toStopIndex[index] + ':' + pool.transition[index];
		}

		static int compareTrace(PrimitiveProfileLabelPool pool, int left, int right) {
			if (left == right) {
				return 0;
			}
			return traceKey(pool, left).compareTo(traceKey(pool, right));
		}
	}

	/** long 값 최소 힙. 프로필 탐색은 상위 32비트에 우선순위, 하위 32비트에 라벨 번호를 넣는다. */
	private static final class LongMinHeap {
		private long[] heap;
		private int size;

		LongMinHeap(int initialCapacity) {
			heap = new long[Math.max(16, initialCapacity)];
		}

		boolean isEmpty() {
			return size == 0;
		}

		void add(long value) {
			if (size == heap.length) heap = Arrays.copyOf(heap, size << 1);
			int index = size++;
			while (index > 0) {
				int parent = (index - 1) >>> 1;
				if (heap[parent] <= value) break;
				heap[index] = heap[parent];
				index = parent;
			}
			heap[index] = value;
		}

		long poll() {
			if (size == 0) throw new IllegalStateException("Empty heap");
			long result = heap[0];
			long last = heap[--size];
			int index = 0;
			int half = size >>> 1;
			while (index < half) {
				int child = 2 * index + 1;
				if (child + 1 < size && heap[child + 1] < heap[child]) child += 1;
				if (last <= heap[child]) break;
				heap[index] = heap[child];
				index = child;
			}
			if (size > 0) heap[index] = last;
			return result;
		}
	}

	private static final class IntArrayList {
		private int[] data;
		private int size;

		IntArrayList(int cap) {
			data = new int[Math.max(2, cap)];
			size = 0;
		}

		int size() {
			return size;
		}

		int get(int index) {
			return data[index];
		}

		void set(int index, int value) {
			data[index] = value;
		}

		void add(int value) {
			if (size == data.length) {
				data = Arrays.copyOf(data, data.length * 2);
			}
			data[size++] = value;
		}

		void truncate(int newSize) {
			size = newSize;
		}

		boolean contains(int value) {
			for (int i = 0; i < size; i++) {
				if (data[i] == value) {
					return true;
				}
			}
			return false;
		}
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
		private final RealtimeOverlay accessOverlay;
		private final ProfileLimitTracker limits;
		private final PrimitiveProfileLabelPool pool = new PrimitiveProfileLabelPool(1024);
		private final Map<ProfileStateKey, IntArrayList> labelsByState = new HashMap<>();
		/** 도착 + 하한이 작은 라벨부터 꺼낸다. 대안 창 상한이 빨리 좁혀져 같은 결과를 더 적은 확장으로 얻는다. */
		private final LongMinHeap pending = new LongMinHeap(256);
		/** 도착역까지 노선 최소 주행 시간 하한. 환승 시간을 빼므로 실제보다 크지 않다. */
		private final int[] lowerBounds;
		private final int destination;
		private final int windowSeconds;
		/** 지금까지 도착역에 닿은 가장 이른 도착. 처리 중인 시점보다 늦게 출발한 라벨도 그 시점에서 탈 수 있다. */
		private int bestDestinationArrival = UNREACHED;
		/** 이 시각보다 늦게 도착하는 여정은 어떤 남은 시점에서도 대안 창 밖이다. */
		private int arrivalBound = UNREACHED;
		/** 도착역까지 남은 최소 승차 수({@link #forwardRemainingBoardings}). */
		private final int[] remainingBoardings;
		/**
		 * 패턴·승차 위치마다 그 뒤 하차로 도착역까지 남는 최소 승차 수(그 노선으로 내린 상태 기준). 패턴 번호로 찾는
		 * 질의 단위 캐시이고 처음 보는 패턴만 채운다(#462).
		 */
		private final int[][] remainingAfterBoarding;
		private int expandedRoutes;
		private int expandedTrips;
		private int expandedTransfers;

		private ProfileMultiLabelForwardScan(
			ScanInput input,
			CompiledTimetable timetable,
			ProfileDatedTripView trips,
			RealtimeOverlay accessOverlay,
			ProfileLimitTracker limits,
			int destination,
			int windowSeconds
		) {
			this.input = Objects.requireNonNull(input, "input");
			this.timetable = Objects.requireNonNull(timetable, "timetable");
			this.trips = Objects.requireNonNull(trips, "trips");
			this.accessOverlay = Objects.requireNonNull(accessOverlay, "accessOverlay");
			this.limits = Objects.requireNonNull(limits, "limits");
			this.destination = destination;
			this.lowerBounds = computeProfileLowerBounds(timetable, destination, trips.overlays());
			this.windowSeconds = windowSeconds;
			this.remainingBoardings = forwardRemainingBoardings(
				timetable, input, accessOverlay, destination, input.maxTransfers() + 1);
			this.remainingAfterBoarding = new int[timetable.routePatternCount()][];
		}

		/** 지금 승차 수에서 이 상태로 내린 뒤 도착역까지 가면 승차 예산을 넘는가. */
		private boolean overBudget(int boardings, int station, int incomingLine) {
			return incomingLine < 0 || (long) boardings
				+ remainingBoardings[station * timetable.lineCount() + incomingLine] > input.maxTransfers() + 1L;
		}

		private int remainingAfterBoarding(int pattern, int position) {
			int[] values = remainingAfterBoarding[pattern];
			if (values == null) {
				int[] stops = timetable.stopsByPattern(pattern);
				ScheduledTrip representative = timetable.patternRepresentative(pattern);
				int[] result = new int[stops.length];
				for (int board = 0; board < stops.length; board += 1) {
					int line = timetable.lineIndex(representative.lineId(board));
					int best = UNREACHABLE_BOARDINGS;
					for (int alight = board + 1; alight < stops.length && line >= 0; alight += 1) {
						if (!representative.allowsDropOff(alight)) continue;
						best = Math.min(best, remainingBoardings[stops[alight] * timetable.lineCount() + line]);
					}
					result[board] = best;
				}
				values = result;
				remainingAfterBoarding[pattern] = values;
			}
			return values[position];
		}

		private boolean improveOrigin(int origin, int readyAtSeconds) {
			int label = pool.allocate(
				readyAtSeconds, readyAtSeconds, 0, origin, -1, (byte) 0,
				0, 0, 0, PrimitiveProfileLabelPool.NO_TRANSFER_SLACK,
				-1, -1, -1, -1, -1, null, null);
			return admit(label);
		}

		/** 하한을 더해도 대안 창 끝을 넘으면 도착역에서 창 안으로 들어올 수 없다. */
		private boolean outsideWindow(int arrivalSeconds, int station) {
			return arrivalBound != UNREACHED && (long) arrivalSeconds + lowerBounds[station] > arrivalBound;
		}

		private void propagate() {
			while (!pending.isEmpty()) {
				throwIfCancelled(input);
				int label = (int) pending.poll();
				if (!isCurrent(label)) continue;
				int labelBoardings = pool.boardings[label];
				if (labelBoardings > input.maxTransfers()) continue;
				int labelStation = pool.station[label];
				// 도착역에서 더 타고 나가 다시 오는 여정은 앞부분 여정에 지배된다(도착·환승·보행·계단·여유 모두 같거나 나쁨).
				if (labelBoardings > 0 && labelStation == destination) continue;
				if (outsideWindow(pool.arrivalSeconds[label], labelStation)) {
					limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
					continue;
				}
				int labelIncomingLine = pool.incomingLine[label];

				for (int pattern : timetable.patternsByStop(labelStation)) {
					limits.consumeWork();
					expandedRoutes += 1;
					int position = indexOf(timetable.stopsByPattern(pattern), labelStation);
					if (position < 0) continue;
					ProfilePatternTrips patternTrips = trips.tripsByPattern(pattern);
					if (patternTrips.trips().isEmpty()) continue;
					int boardingLine = timetable.patternLine(pattern, position);
					if (boardingLine < 0) continue;
					int transition = labelBoardings == 0
						? PLATFORM_BOUNDARY
						: timetable.transferTransition(labelStation, labelIncomingLine, boardingLine,
							input.accessProfileBit(), false, input.requiresVerifiedJourneyDistance(), accessOverlay);
					if (transition < 0) {
						limits.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
						continue;
					}
					if (labelBoardings > 0) expandedTransfers += 1;
					boardPattern(label, pattern, position, boardingLine, transition, patternTrips);
				}
				if (labelBoardings > 0) {
					propagateOutOfStationFootpaths(label, labelStation, labelIncomingLine);
				}
			}
		}

		// 역 밖 환승은 역 안 환승 목록이 아니라 footpath로 컴파일되므로 point·역방향 탐색처럼 따로 읽는다.
		private void propagateOutOfStationFootpaths(int label, int labelStation, int labelIncomingLine) {
			OutOfStationFootpath[] footpaths = timetable.footpathsFromStation(labelStation);
			if (footpaths == null) return;
			for (OutOfStationFootpath footpath : footpaths) {
				if (footpath.fromLine() != labelIncomingLine) continue;
				int transition = timetable.selectTransition(footpath.candidateTransitions(), input.accessProfileBit(),
					false, input.requiresVerifiedJourneyDistance(), accessOverlay);
				if (transition < 0) {
					limits.count("HARD_TRANSFER_ACCESS_ELIGIBILITY_V1");
					continue;
				}
				for (int pattern : timetable.patternsByStop(footpath.toStation())) {
					limits.consumeWork();
					expandedRoutes += 1;
					// patternsByStop은 stopsByPattern의 역색인이라 도착역은 항상 패턴 안에 있다.
					int position = indexOf(timetable.stopsByPattern(pattern), footpath.toStation());
					ProfilePatternTrips patternTrips = trips.tripsByPattern(pattern);
					if (patternTrips.trips().isEmpty()
						|| timetable.patternLine(pattern, position) != footpath.toLine()) continue;
					expandedTransfers += 1;
					boardPattern(label, pattern, position, footpath.toLine(), transition, patternTrips);
				}
			}
		}

		private void boardPattern(
			int label,
			int pattern,
			int position,
			int boardingLine,
			int transition,
			ProfilePatternTrips patternTrips
		) {
			int labelBoardings = pool.boardings[label];
			int labelArrivalSeconds = pool.arrivalSeconds[label];
			int labelAccessSeconds = pool.accessSeconds[label];
			int labelAccessMeters = pool.accessDistanceMeters[label];
			int labelStairs = pool.stairBurden[label];
			int labelSlack = pool.slackSeconds[label];
			byte labelWarnings = pool.warningBits[label];
			int accessSeconds = labelBoardings == 0 ? 0 : journeyTransferSeconds(input,
				timetable.transitionDurationSeconds(transition), timetable.transitionDistanceMeters(transition));
			int earliestDeparture = Math.addExact(Math.addExact(labelArrivalSeconds, accessSeconds),
				input.boardingSlackSeconds());
			int[] stops = timetable.stopsByPattern(pattern);
			if ((long) labelBoardings + 1 + remainingAfterBoarding(pattern, position) > input.maxTransfers() + 1L) {
				limits.count(JourneyRaptorPruningInventoryV1.PROFILE_TRANSFER_BUDGET);
				return;
			}
			List<ProfileDatedTrip> datedTrips = patternTrips.trips();
			for (int block = 0; block < patternTrips.blockCount(); block += 1) {
				int end = patternTrips.blockEnd(block);
				boolean ordered = patternTrips.ordered(block);
				int from = ordered
					? firstDepartingAtOrAfter(datedTrips, patternTrips.blockStart(block), end, position, earliestDeparture)
					: patternTrips.blockStart(block);
				for (int index = from; index < end; index += 1) {
					ProfileDatedTrip trip = datedTrips.get(index);
					limits.consumeWork();
					int departure = trip.departureSeconds(position);
					if (departure < earliestDeparture) continue;
					if (outsideWindow(departure, stops[position])) {
						limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
						// 시각 순서가 보장된 묶음에서는 뒤 열차도 모두 창 밖이다.
						if (ordered) break;
						continue;
					}
					if (!trip.allowsPickup(position) || trip.cancelled()) continue;
					expandedTrips += 1;
					long transferSlack = (long) departure - labelArrivalSeconds - accessSeconds - input.boardingSlackSeconds();
					if (transferSlack < 0) continue;
					int childSlack = labelBoardings == 0
						? PrimitiveProfileLabelPool.NO_TRANSFER_SLACK
						: Math.min(labelSlack, (int) Math.min(transferSlack, Integer.MAX_VALUE - 1L));
					byte warnings = (byte) (labelWarnings
						| timetable.transitionWarningCodes(transition, input.accessProfileBit(), false));
					for (int alight = position + 1; alight < trip.stopTimes().size(); alight += 1) {
						limits.consumeWork();
						if (!trip.allowsDropOff(alight)) continue;
						if (overBudget(labelBoardings + 1, stops[alight], boardingLine)) {
							limits.count(JourneyRaptorPruningInventoryV1.PROFILE_TRANSFER_BUDGET);
							continue;
						}
						int arrival = trip.arrivalSeconds(alight);
						if (outsideWindow(arrival, stops[alight])) {
							limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
							continue;
						}
						int child = pool.allocate(
							pool.startSeconds[label],
							arrival,
							labelBoardings + 1,
							stops[alight],
							boardingLine,
							warnings,
							Math.addExact(labelAccessSeconds, accessSeconds),
							Math.addExact(labelAccessMeters, timetable.transitionDistanceMeters(transition)),
							Math.addExact(labelStairs, timetable.transitionIncludesStairs(transition) ? 1 : 0),
							childSlack,
							label,
							trip.scheduledTrip().index(),
							position,
							alight,
							transition,
							trip.nativeServiceDate(),
							trip);
						if (!admit(child)) pool.discardLast(child);
					}
				}
			}
		}

		/** 시각 순서가 보장된 [start, end) 구간에서 position 출발이 earliest 이상인 첫 열차. */
		private static int firstDepartingAtOrAfter(
			List<ProfileDatedTrip> trips, int start, int end, int position, int earliest
		) {
			int low = start;
			int high = end;
			while (low < high) {
				int middle = (low + high) >>> 1;
				if (trips.get(middle).departureSeconds(position) < earliest) low = middle + 1;
				else high = middle;
			}
			return low;
		}

		private boolean isCurrent(int label) {
			ProfileStateKey state = new ProfileStateKey(
				pool.boardings[label], pool.station[label], pool.incomingLine[label]);
			IntArrayList labels = labelsByState.get(state);
			return labels != null && labels.contains(label);
		}

		private List<JourneyItinerary> destinationItineraries(
			ScanInput pointInput,
			int destination,
			int accessProfileBit
		) {
			List<ProfileDestinationLabel> candidates = new ArrayList<>();
			int earliestArrival = UNREACHED;
			for (Map.Entry<ProfileStateKey, IntArrayList> entry : labelsByState.entrySet()) {
				ProfileStateKey state = entry.getKey();
				// #454: 도착역의 어느 승강장(역-노선)에 내려도 도착이다. 하차 시간·거리를 더하지 않는다.
				if (state.station() != destination || state.boardings() == 0) continue;
				IntArrayList list = entry.getValue();
				int count = list.size();
				for (int i = 0; i < count; i++) {
					int label = list.get(i);
					limits.consumeWork();
					earliestArrival = Math.min(earliestArrival, pool.arrivalSeconds[label]);
					candidates.add(new ProfileDestinationLabel(label,
						pool.arrivalSeconds[label],
						pool.accessSeconds[label],
						pool.accessDistanceMeters[label],
						pool.stairBurden[label],
						pool.warningBits[label]));
				}
			}
			// #461: 이 시점의 가장 이른 도착 + 대안 창 안의 여정만 파레토 집합에 넣는다.
			long windowEnd = (long) earliestArrival + windowSeconds;
			List<ProfileDestinationLabel> windowed = new ArrayList<>(candidates.size());
			for (ProfileDestinationLabel candidate : candidates) {
				if (candidate.arrivalSeconds() <= windowEnd) windowed.add(candidate);
				else limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
			}
			List<ProfileDestinationLabel> frontier = destinationFrontier(windowed);
			limits.observeDestinationLabels(frontier.size());
			if (frontier.size() > limits.maxDestinationProfileLabels()) {
				limits.count("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
				throw new ProfilePlanningLimitException(ProfilePlanningLimit.MAX_DESTINATION_PROFILE_LABELS,
					frontier.size(), limits.maxDestinationProfileLabels());
			}
			return frontier.stream()
				.map(candidate -> toJourneyItinerary(pointInput, timetable,
					candidate.toScalarLabel(materialize(candidate.labelIndex()), pointInput.readyAtSeconds())))
				.sorted(Comparator.comparing(JourneyItinerary::plannedArrivalTime)
					.thenComparing(JourneyItinerary::plannedDepartureTime))
				.toList();
		}

		private ScanMetrics scanMetrics() {
			return new ScanMetrics(expandedRoutes, expandedTrips, expandedTransfers);
		}

		private boolean admit(int candidate) {
			limits.consumeWork();
			if (outsideWindow(pool.arrivalSeconds[candidate], pool.station[candidate])) {
				limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
				return false;
			}
			if (pool.boardings[candidate] > 0 && pool.station[candidate] == destination
				&& pool.arrivalSeconds[candidate] < bestDestinationArrival) {
				bestDestinationArrival = pool.arrivalSeconds[candidate];
				long bound = (long) bestDestinationArrival + windowSeconds;
				if (arrivalBound == UNREACHED || bound < arrivalBound) arrivalBound = (int) Math.min(bound, UNREACHED - 1L);
			}
			ProfileStateKey state = new ProfileStateKey(
				pool.boardings[candidate], pool.station[candidate], pool.incomingLine[candidate]);
			IntArrayList labels = labelsByState.computeIfAbsent(state, ignored -> new IntArrayList(4));
			// 한 번 훑으며 (1) 상한이 좁혀져 창 밖이 된 라벨을 비우고(창 안 라벨을 지배할 수 없음: 도착이 더 늦음)
			// (2) 후보를 지배하는 라벨이 있으면 거절하고 (3) 후보가 지배하는 라벨을 뺀다. 파레토 집합 안에서는 후보를
			// 지배하는 라벨과 후보가 지배하는 라벨이 함께 있을 수 없으므로(추이성) 거절될 후보는 아무것도 빼지 않는다.
			int kept = 0;
			boolean rejected = false;
			for (int read = 0; read < labels.size(); read++) {
				int existing = labels.get(read);
				if (outsideWindow(pool.arrivalSeconds[existing], pool.station[existing])) {
					limits.count(JourneyRaptorPruningInventoryV1.PROFILE_ALTERNATIVE_WINDOW);
					continue;
				}
				if (!rejected) {
					if (PrimitiveProfileLabelPool.dominates(pool, existing, candidate)) {
						limits.count("FORWARD_STATE_DOMINANCE_V1");
						rejected = true;
					} else if (PrimitiveProfileLabelPool.sameVector(pool, existing, candidate)) {
						limits.count("FORWARD_STATE_EQUAL_VECTOR_CANONICAL_TRACE_V1");
						if (PrimitiveProfileLabelPool.compareTrace(pool, existing, candidate) <= 0) {
							rejected = true;
						} else {
							continue;
						}
					} else if (PrimitiveProfileLabelPool.dominates(pool, candidate, existing)) {
						limits.count("FORWARD_STATE_DOMINANCE_V1");
						continue;
					}
				}
				labels.set(kept++, existing);
			}
			labels.truncate(kept);
			if (rejected) return false;
			labels.add(candidate);
			limits.observeStateLabels(labels.size());
			if (labels.size() > limits.maxLabelsPerState()) {
				limits.count("FAIL_CLOSED_FRONTIER_CAPACITY_V1");
				throw new ProfilePlanningLimitException(ProfilePlanningLimit.MAX_LABELS_PER_STATE,
					labels.size(), limits.maxLabelsPerState());
			}
			long priority = (long) pool.arrivalSeconds[candidate] + lowerBounds[pool.station[candidate]];
			pending.add((priority << 32) | candidate);
			return true;
		}

		private List<ProfileDestinationLabel> destinationFrontier(List<ProfileDestinationLabel> labels) {
			List<ProfileDestinationLabel> frontier = new ArrayList<>();
			for (ProfileDestinationLabel candidate : labels) {
				boolean dominated = false;
				for (ProfileDestinationLabel other : labels) {
					if (other != candidate && destinationDominates(other, candidate)) {
						dominated = true;
						break;
					}
				}
				if (!dominated) frontier.add(candidate);
				else limits.count("FORWARD_DESTINATION_DOMINANCE_V1");
			}
			frontier.sort(Comparator.comparing(candidate -> PrimitiveProfileLabelPool.traceKey(pool, candidate.labelIndex())));
			return List.copyOf(frontier);
		}

		private boolean destinationDominates(ProfileDestinationLabel left, ProfileDestinationLabel right) {
			int leftLabel = left.labelIndex();
			int rightLabel = right.labelIndex();
			int leftBoardings = pool.boardings[leftLabel];
			int rightBoardings = pool.boardings[rightLabel];
			int leftSlack = pool.slackSeconds[leftLabel];
			int rightSlack = pool.slackSeconds[rightLabel];

			// 한 profile point의 준비 시각은 같다. 이전 iteration의 시작 시각은 state 재사용에만 쓴다.
			return left.arrivalSeconds() <= right.arrivalSeconds()
				&& leftBoardings <= rightBoardings
				&& left.accessSeconds() <= right.accessSeconds()
				&& left.accessDistanceMeters() <= right.accessDistanceMeters()
				&& left.stairBurden() <= right.stairBurden()
				&& leftSlack >= rightSlack
				&& (left.warningBits() & right.warningBits()) == left.warningBits()
				&& (left.arrivalSeconds() < right.arrivalSeconds()
					|| leftBoardings < rightBoardings
					|| left.accessSeconds() < right.accessSeconds()
					|| left.accessDistanceMeters() < right.accessDistanceMeters()
					|| left.stairBurden() < right.stairBurden()
					|| leftSlack > rightSlack
					|| left.warningBits() != right.warningBits());
		}

		private ProfileLabel materialize(int index) {
			if (index < 0) {
				return null;
			}
			ProfileRideTrace trace = null;
			int parent = pool.parentIndex[index];
			if (parent >= 0 && pool.trip[index] != null) {
				trace = new ProfileRideTrace(
					materialize(parent),
					pool.trip[index],
					pool.fromStopIndex[index],
					pool.toStopIndex[index],
					pool.transition[index]
				);
			}
			return new ProfileLabel(
				pool.startSeconds[index],
				pool.arrivalSeconds[index],
				pool.boardings[index],
				pool.station[index],
				pool.incomingLine[index],
				pool.warningBits[index],
				pool.accessSeconds[index],
				pool.accessDistanceMeters[index],
				pool.stairBurden[index],
				PrimitiveProfileLabelPool.decodeSlack(pool.slackSeconds[index]),
				trace
			);
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
		int labelIndex,
		int arrivalSeconds,
		long accessSeconds,
		long accessDistanceMeters,
		long stairBurden,
		byte warningBits
	) {
		private Label toScalarLabel(ProfileLabel label, int readyAtSeconds) {
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
				transitions, PLATFORM_BOUNDARY, warningBits);
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

	public enum RoutePersona {
		FASTEST,
		STEP_FREE,
		MIN_WALK,
		RELAXED_SLACK
	}

	record JourneyItinerary(
		LocalDate serviceDate,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime,
		JourneyProfileRaptorPort.ItineraryMetrics metrics,
		List<JourneyLegProjection> legs,
		RoutePersona persona
	) {
		JourneyItinerary {
			metrics = Objects.requireNonNull(metrics, "metrics");
			legs = List.copyOf(legs);
		}

		JourneyItinerary(
			LocalDate serviceDate,
			Instant plannedDepartureTime,
			Instant plannedArrivalTime,
			Instant realtimeDepartureTime,
			Instant realtimeArrivalTime,
			JourneyProfileRaptorPort.ItineraryMetrics metrics,
			List<JourneyLegProjection> legs
		) {
			this(serviceDate, plannedDepartureTime, plannedArrivalTime, realtimeDepartureTime, realtimeArrivalTime, metrics, legs, null);
		}

		JourneyItinerary withPersona(RoutePersona newPersona) {
			return new JourneyItinerary(
				serviceDate, plannedDepartureTime, plannedArrivalTime, realtimeDepartureTime, realtimeArrivalTime,
				metrics, legs, newPersona
			);
		}
	}

	sealed interface JourneyLegProjection permits JourneyAccessProjection, JourneyRideProjection {
	}

	/** #454: 승강장 기준 여정의 이동 구간은 승차 사이의 환승뿐이다. 진입·하차 구간은 만들지 않는다. */
	enum JourneyAccessKind {
		TRANSFER
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
			this(kind, fromStationId, toStationId, durationSeconds, distanceMeters, includesStairs, verified, verificationStatus, null, null, null);
		}
	}
	public record AlightingCarDoor(int carNumber, int doorNumber, String targetFacilityType) {
		public AlightingCarDoor {
			if (carNumber < 1 || carNumber > 10) {
				throw new IllegalArgumentException("carNumber must be between 1 and 10");
			}
			if (doorNumber < 1 || doorNumber > 4) {
				throw new IllegalArgumentException("doorNumber must be between 1 and 4");
			}
			Objects.requireNonNull(targetFacilityType, "targetFacilityType");
		}
	}

	public record JourneyStopProjection(
		String stationId,
		Instant plannedArrivalTime,
		Instant plannedDepartureTime,
		Instant realtimeArrivalTime,
		Instant realtimeDepartureTime
	) {
	}

	record JourneyRideProjection(
		String lineId,
		String tripId,
		String directionStationId,
		String fromStationId,
		String toStationId,
		String servicePattern,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime,
		List<JourneyStopProjection> stops,
		List<AlightingCarDoor> alightingCarDoors,
		List<com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap> boardingPlatformGaps,
		List<com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap> alightingPlatformGaps
	) implements JourneyLegProjection {
		public JourneyRideProjection {
			alightingCarDoors = alightingCarDoors == null ? List.of() : List.copyOf(alightingCarDoors);
			boardingPlatformGaps = boardingPlatformGaps == null ? List.of() : List.copyOf(boardingPlatformGaps);
			alightingPlatformGaps = alightingPlatformGaps == null ? List.of() : List.copyOf(alightingPlatformGaps);
		}
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

		static RealtimeOverlay blockedOnly(BitSet blockedTransitions) {
			if (blockedTransitions == null || blockedTransitions.isEmpty()) {
				return EMPTY;
			}
			return new RealtimeOverlay(
				null, false, new int[0], new int[0], new int[0], new boolean[0], new RealtimeEvidence[0], new int[0], blockedTransitions);
		}

		static RealtimeOverlay combine(RealtimeOverlay a, RealtimeOverlay b) {
			if (a == null || a.isEmpty()) {
				return b != null ? b : EMPTY;
			}
			if (b == null || b.isEmpty()) {
				return a;
			}
			if (a == b) {
				return a;
			}
			BitSet mergedBlocked = (BitSet) a.blockedTransitions.clone();
			mergedBlocked.or(b.blockedTransitions);

			int[] mergedPatterns = java.util.stream.IntStream.concat(
				java.util.Arrays.stream(a.affectedPatterns),
				java.util.Arrays.stream(b.affectedPatterns))
				.distinct().sorted().toArray();

			String version = a.version != null ? a.version : b.version;
			int[] tripIndexes = a.tripIndexes.length > 0 ? a.tripIndexes : b.tripIndexes;
			int[] arrivalDeltas = a.tripIndexes.length > 0 ? a.arrivalDeltas : b.arrivalDeltas;
			int[] departureDeltas = a.tripIndexes.length > 0 ? a.departureDeltas : b.departureDeltas;
			boolean[] cancelled = a.tripIndexes.length > 0 ? a.cancelled : b.cancelled;
			RealtimeEvidence[] evidence = a.evidence.length > 0 ? a.evidence : b.evidence;

			return new RealtimeOverlay(
				version,
				a.available || b.available,
				tripIndexes,
				arrivalDeltas,
				departureDeltas,
				cancelled,
				evidence,
				mergedPatterns,
				mergedBlocked);
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

		/** 이 overlay가 막는 전환 집합의 복사본. 역방향 탐색이 같은 차단 집합의 라벨끼리만 비교할 때 쓴다. */
		BitSet blockedTransitionsCopy() {
			return (BitSet) blockedTransitions.clone();
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
		List<int[]> stopsByPattern,
		List<List<ScheduledTrip>> tripsByPattern
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
	/** 규칙별 횟수. 가지치기마다 부르므로 박싱 없이 칸 하나짜리 배열을 고친다(#462). */
	private final Map<String, long[]> counts = new HashMap<>();
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
		JourneyRaptorPruningInventoryV1.activeRuleIds(algorithmIdentity).forEach(rule -> counts.put(rule, new long[1]));
	}

	void increment(String ruleId) {
		long[] count = counts.get(ruleId);
		if (count == null) throw new IllegalArgumentException("inactive pruning rule: " + ruleId);
		count[0] = Math.addExact(count[0], 1L);
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
		Map<String, Long> snapshot = new LinkedHashMap<>();
		counts.forEach((rule, count) -> snapshot.put(rule, count[0]));
		return new JourneyRaptorPruningInventoryV1.CountSnapshot(requestId, algorithmIdentity, snapshot);
	}

	JourneyProfileRaptorPort.PlanningMetrics planningMetrics() {
		return new JourneyProfileRaptorPort.PlanningMetrics(
			workConsumed, peakStateLabels, peakDestinationLabels, reservedProfileBreakpoints);
	}
}
