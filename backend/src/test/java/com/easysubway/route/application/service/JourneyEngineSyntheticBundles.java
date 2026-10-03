package com.easysubway.route.application.service;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendarDate;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * #460: 시드 고정 합성 번들 생성기. 같은 시드는 항상 같은 번들을 만든다.
 *
 * <p>노선 수·역 공유(환승)·양방향 운행·급행 패턴·승하차 금지 정차·요일 달력·운행 제외일·
 * 자정을 넘는 운행(서비스일 24시 이후 시각)·03:00 서비스일 경계 직전까지 달리는 열차를 섞는다.
 * 환승 동선은 계단 전용, 무단차 전용, 계단+무단차 이중, 동선 없음 중에서 고르고 일부는 이용 불가로 둔다.
 * 거리 없이 실측 시간만 있는 환승(#454·data#876)도 섞는다.</p>
 *
 * <p>모든 근거는 검증된 공식 출처로 만든다. 저신뢰·미검증 근거는 엔진이 배제하는 별도 계약이며
 * 기준 해 입력 정규화가 그 규칙을 모델링하지 않으므로 이 생성기의 범위 밖이다.</p>
 */
final class JourneyEngineSyntheticBundles {

	static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 1);
	static final LocalDate SATURDAY = LocalDate.of(2026, 7, 4);
	static final String ZONE = "Asia/Seoul";
	/**
	 * 시각·운행 시간·실측 환승 시간을 30초 격자에 맞춘다. 승차 여유(60·90·180초)도 격자 위에 있어 환승 여유가 정확히
	 * 0초인 경계 연결이 자주 생기고, 최소 환승 시간이 1초만 틀려도 결과가 달라진다(경계 off-by-one 검출).
	 */
	static final int GRID = 30;

	private JourneyEngineSyntheticBundles() {
	}

	enum Band {
		/** 06:30~09:30 출근 시간대. */
		MORNING(23_400, 34_200),
		/** 22:30~26:30 자정을 넘는 심야 운행. 일부 열차는 03:00 경계 직후(27:xx)까지 달린다. */
		LATE_NIGHT(81_000, 95_400);

		final int startSeconds;
		final int endSeconds;

		Band(int startSeconds, int endSeconds) {
			this.startSeconds = startSeconds;
			this.endSeconds = endSeconds;
		}
	}

	enum TransferKind { STAIRS_ONLY, STEP_FREE_ONLY, STAIRS_AND_STEP_FREE, NONE }

	record Bundle(
		long seed,
		RouteTimetable timetable,
		LocalDate queryDate,
		Band band,
		List<String> stations
	) {
	}

	static Bundle generate(long seed) {
		Random random = new Random(seed);
		LocalDate queryDate = random.nextInt(4) == 0 ? SATURDAY : WEDNESDAY;
		Band band = random.nextBoolean() ? Band.MORNING : Band.LATE_NIGHT;
		int stationCount = 6 + random.nextInt(4);
		List<String> stations = new ArrayList<>();
		for (int index = 0; index < stationCount; index += 1) stations.add("s" + index);

		int lineCount = 2 + random.nextInt(4);
		Map<String, List<String>> lineStations = new LinkedHashMap<>();
		Set<String> used = new LinkedHashSet<>();
		for (int line = 1; line <= lineCount; line += 1) {
			List<String> shuffled = new ArrayList<>(stations);
			Collections.shuffle(shuffled, random);
			int length = 3 + random.nextInt(Math.min(4, stationCount - 2));
			List<String> sequence = new ArrayList<>(shuffled.subList(0, length));
			if (!used.isEmpty() && sequence.stream().noneMatch(used::contains)) {
				List<String> previous = new ArrayList<>(used);
				sequence.set(random.nextInt(length), previous.get(random.nextInt(previous.size())));
			}
			used.addAll(sequence);
			lineStations.put("L" + line, List.copyOf(sequence));
		}

		List<ServiceCalendar> calendars = List.of(
			calendar("daily", true, true),
			calendar("weekday", true, false),
			calendar("special", true, true));
		List<ServiceCalendarDate> exceptions = random.nextInt(3) == 0
			? List.of(new ServiceCalendarDate("special", queryDate, 2))
			: List.of();
		// 환승 동선을 먼저 만들어 일부 열차를 앞선 열차의 도착에 정확히 맞춰 출발시킨다(여유 0초 경계 연결).
		RouteAccessData access = transfers(lineStations, random);
		Map<String, Integer> measuredTransfer = measuredTransferSeconds(access);
		Map<String, List<int[]>> arrivalsByStation = new LinkedHashMap<>();
		List<TransitRoute> routes = new ArrayList<>();
		List<TransitTrip> trips = new ArrayList<>();
		List<TransitStopTime> stopTimes = new ArrayList<>();
		int lineOrdinal = 0;
		for (Map.Entry<String, List<String>> entry : lineStations.entrySet()) {
			String line = entry.getKey();
			lineOrdinal += 1;
			for (String direction : List.of("up", "down")) {
				List<String> sequence = new ArrayList<>(entry.getValue());
				if (direction.equals("down")) Collections.reverse(sequence);
				String routeId = "r-" + line + "-" + direction;
				routes.add(new TransitRoute(routeId, line, line, line, direction, ZONE));
				int tripCount = 2 + random.nextInt(4);
				for (int tripIndex = 0; tripIndex < tripCount; tripIndex += 1) {
					String tripId = "t-" + line + "-" + direction + "-" + tripIndex;
					boolean express = sequence.size() > 3 && random.nextInt(4) == 0;
					List<String> stops = express ? expressStops(sequence, random) : sequence;
					int[] dwell = new int[stops.size()];
					int[] hop = new int[stops.size()];
					for (int position = 0; position < stops.size(); position += 1) {
						dwell[position] = position == 0 || position == stops.size() - 1 ? 0 : GRID * random.nextInt(2);
						hop[position] = GRID * (3 + random.nextInt(8));
					}
					int departure = band.startSeconds + GRID * random.nextInt((band.endSeconds - band.startSeconds) / GRID);
					if (band == Band.LATE_NIGHT && random.nextInt(6) == 0) departure = 95_400 + GRID * random.nextInt(80);
					Integer aligned = random.nextBoolean()
						? alignedDeparture(line, stops, dwell, hop, arrivalsByStation, measuredTransfer, random) : null;
					if (aligned != null) departure = aligned;
					String serviceId = switch (random.nextInt(10)) {
						case 0 -> "special";
						case 1, 2 -> "weekday";
						default -> "daily";
					};
					trips.add(new TransitTrip(tripId, routeId, serviceId, stops.getLast(), direction,
						express ? "EXPRESS" : "LOCAL", 0));
					int clock = departure;
					for (int position = 0; position < stops.size(); position += 1) {
						int arrival = clock;
						int departs = arrival + dwell[position];
						boolean interior = position > 0 && position < stops.size() - 1;
						int pickup = interior && random.nextInt(16) == 0 ? 1 : 0;
						int dropOff = interior && random.nextInt(16) == 0 ? 1 : 0;
						stopTimes.add(new TransitStopTime(tripId, position + 1, stops.get(position), line,
							arrival, departs, pickup, dropOff));
						arrivalsByStation.computeIfAbsent(stops.get(position), ignored -> new ArrayList<>())
							.add(new int[] {lineOrdinal, arrival});
						clock = departs + hop[position];
					}
				}
			}
		}
		RouteTimetable timetable = new RouteTimetable(calendars, exceptions, routes, trips, stopTimes,
			List.of(), List.of(), null, access);
		return new Bundle(seed, timetable, queryDate, band, List.copyOf(stations));
	}

	/**
	 * 앞선 다른 노선 열차가 환승역 X에 도착한 시각 a에 대해, 이 열차가 X에서 a + 실측 환승 시간 + 승차 여유에 정확히
	 * 출발하도록 첫 역 출발 시각을 고른다. 승차 여유는 표준 60초, 느린 걸음 90초, 무단차 180초(+시설 대기 60초)다.
	 * 걸음 속도가 기준(1.2 m/s) 이상이면 실측 환승 시간을 그대로 쓰므로 그 질의에서 환승 여유가 정확히 0초가 된다.
	 * 일부는 1초 앞뒤로 어긋나게 두어 경계 바로 밖(-1초, 탈 수 없음)과 바로 안(+1초)도 만든다.
	 */
	private static Integer alignedDeparture(
		String line, List<String> stops, int[] dwell, int[] hop, Map<String, List<int[]>> arrivalsByStation,
		Map<String, Integer> measuredTransfer, Random random
	) {
		int lineOrdinal = Integer.parseInt(line.substring(1));
		List<int[]> options = new ArrayList<>();
		int offset = 0;
		for (int position = 0; position < stops.size(); position += 1) {
			int departOffset = offset + dwell[position];
			if (position < stops.size() - 1) {
				for (int[] arrival : arrivalsByStation.getOrDefault(stops.get(position), List.of())) {
					Integer seconds = measuredTransfer.get(stops.get(position) + "-L" + arrival[0] + "-" + line);
					if (arrival[0] != lineOrdinal && seconds != null) options.add(new int[] {arrival[1] + seconds, departOffset});
				}
			}
			offset = departOffset + hop[position];
		}
		if (options.isEmpty()) return null;
		int[] option = options.get(random.nextInt(options.size()));
		int slack = List.of(60, 90, 240).get(random.nextInt(3));
		// -1초면 그 프로필에서 딱 1초 모자라 탈 수 없는 연결, +1초면 여유 1초 연결이다(경계 양쪽).
		int edge = random.nextInt(3) - 1;
		int departure = option[0] + slack + edge - option[1];
		return departure >= 0 && departure + offset < 104_000 ? departure : null;
	}

	/** (역-출발 노선-도착 노선)별로 거리 없이 실측 시간만 있는 이용 가능 동선의 최소 실측 시간. */
	private static Map<String, Integer> measuredTransferSeconds(RouteAccessData access) {
		Map<String, PathwayEdge> edges = new LinkedHashMap<>();
		access.pathwayEdges().forEach(edge -> edges.put(edge.id(), edge));
		Map<String, Integer> result = new LinkedHashMap<>();
		for (TransferRule rule : access.transferRules()) {
			for (String edgeId : java.util.Arrays.asList(rule.pathwayEdgeId(), rule.strictStepFreePathwayEdgeId())) {
				PathwayEdge edge = edgeId == null ? null : edges.get(edgeId);
				if (edge == null || edge.distanceMeters() != 0 || !"AVAILABLE".equals(edge.accessibilityStatus())) continue;
				result.merge(rule.fromStationId() + "-" + rule.fromLineId() + "-" + rule.toLineId(), edge.durationSeconds(),
					Math::min);
			}
		}
		return result;
	}

	private static List<String> expressStops(List<String> sequence, Random random) {
		List<String> stops = new ArrayList<>(sequence);
		int skip = 1 + random.nextInt(stops.size() - 2);
		stops.remove(skip);
		return List.copyOf(stops);
	}

	private static ServiceCalendar calendar(String serviceId, boolean weekdays, boolean weekend) {
		return new ServiceCalendar(serviceId, weekdays, weekdays, weekdays, weekdays, weekdays, weekend, weekend,
			WEDNESDAY.minusDays(3), SATURDAY.plusDays(3), ZONE);
	}

	private static RouteAccessData transfers(Map<String, List<String>> lineStations, Random random) {
		Map<String, List<String>> linesByStation = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : lineStations.entrySet()) {
			for (String station : entry.getValue()) {
				linesByStation.computeIfAbsent(station, ignored -> new ArrayList<>()).add(entry.getKey());
			}
		}
		Map<String, PathwayNode> nodes = new LinkedHashMap<>();
		List<PathwayEdge> edges = new ArrayList<>();
		List<TransferRule> rules = new ArrayList<>();
		List<RouteEdgeEvidence> evidence = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : linesByStation.entrySet()) {
			String station = entry.getKey();
			for (String from : entry.getValue()) {
				for (String to : entry.getValue()) {
					if (from.equals(to)) continue;
					TransferKind kind = TransferKind.values()[random.nextInt(10) < 9 ? random.nextInt(3) : 3];
					if (kind == TransferKind.NONE) continue;
					String fromNode = "p-" + station + "-" + from;
					String toNode = "p-" + station + "-" + to;
					nodes.putIfAbsent(fromNode, new PathwayNode(fromNode, station, from, "PLATFORM"));
					nodes.putIfAbsent(toNode, new PathwayNode(toNode, station, to, "PLATFORM"));
					String key = station + "-" + from + "-" + to;
					int stairsDistance = 30 + random.nextInt(420);
					int stepFreeDistance = 30 + random.nextInt(420);
					if (stepFreeDistance == stairsDistance) stepFreeDistance += 1;
					PathwayEdge stairs = edge("e-" + key + "-stairs", fromNode, toNode, stairsDistance, true, random);
					PathwayEdge stepFree = edge("e-" + key + "-step-free", fromNode, toNode, stepFreeDistance, false, random);
					String normal;
					String strict;
					switch (kind) {
						case STAIRS_ONLY -> { edges.add(stairs); normal = stairs.id(); strict = null; }
						case STEP_FREE_ONLY -> { edges.add(stepFree); normal = stepFree.id(); strict = stepFree.id(); }
						default -> { edges.add(stairs); edges.add(stepFree); normal = stairs.id(); strict = stepFree.id(); }
					}
					for (String edgeId : new LinkedHashSet<>(java.util.Arrays.asList(normal, strict))) {
						if (edgeId == null) continue;
						evidence.add(new RouteEdgeEvidence("ev-" + edgeId, station, to, edgeId, "TRANSFER",
							"OFFICIAL_SOURCE", "VERIFIED", true, null));
					}
					// 규칙 최소 환승 시간은 동선 실측 시간 이하로 둔다. 더 크면 엔진은 규칙 값을, 기준 해 입력
					// 정규화는 동선 값을 쓰는데 이 차이는 탐색 정확성이 아니라 입력 모델링 규칙이라 범위 밖이다.
					int minimum = edges.stream().filter(edge -> edge.id().equals(normal) || edge.id().equals(strict))
						.mapToInt(PathwayEdge::durationSeconds).min().orElseThrow();
					rules.add(new TransferRule("rule-" + key, station, from, station, to, "IN_STATION",
						random.nextInt(minimum + 1), normal, strict, "VERIFIED"));
				}
			}
		}
		return new RouteAccessData(List.copyOf(nodes.values()), edges, rules, evidence);
	}

	private static PathwayEdge edge(String id, String from, String to, int distance, boolean stairs, Random random) {
		int duration = GRID * (1 + random.nextInt(12));
		// 일부 환승은 거리 없이 공식 실측 시간만 있다(#454·data#876). 이 환승은 걸음 속도가 기준 이상이면 실측 시간을
		// 그대로 쓰므로 30초 격자 시각과 맞물려 여유 0초 연결이 자주 생긴다.
		int meters = random.nextInt(3) == 0 ? 0 : distance;
		String status = random.nextInt(12) == 0 ? "UNAVAILABLE" : "AVAILABLE";
		return new PathwayEdge(id, from, to, duration, meters, false, stairs, 100, status, "OFFICIAL_SOURCE", "VERIFIED");
	}

	/** 승차·환승 건수를 줄여 최소 반례를 찾는 데 쓰는 번들 사본. */
	static RouteTimetable without(RouteTimetable source, Set<String> removedTrips, Set<String> removedRules) {
		var access = source.routeAccessData();
		return new RouteTimetable(source.serviceCalendars(), source.serviceCalendarDates(), source.transitRoutes(),
			source.transitTrips().stream().filter(trip -> !removedTrips.contains(trip.id())).toList(),
			source.transitStopTimes().stream().filter(stop -> !removedTrips.contains(stop.tripId())).toList(),
			source.transitFrequencies(), source.officialFares(), source.feedEndDate(),
			new RouteAccessData(access.pathwayNodes(), access.pathwayEdges(),
				access.transferRules().stream().filter(rule -> !removedRules.contains(rule.id())).toList(),
				access.routeEdgeEvidence()));
	}

	/** 사람이 읽고 그대로 고정 테스트로 옮길 수 있는 번들 서술. */
	static String describe(RouteTimetable timetable) {
		StringBuilder text = new StringBuilder();
		Map<String, LoadRouteTimetablePort.TransitTrip> tripsById = new LinkedHashMap<>();
		timetable.transitTrips().forEach(trip -> tripsById.put(trip.id(), trip));
		for (var trip : tripsById.values()) {
			text.append("  trip ").append(trip.id()).append(" service=").append(trip.serviceId())
				.append(" pattern=").append(trip.servicePattern()).append(':');
			timetable.transitStopTimes().stream().filter(stop -> stop.tripId().equals(trip.id()))
				.sorted(java.util.Comparator.comparingInt(TransitStopTime::stopSequence))
				.forEach(stop -> text.append(' ').append(stop.stationId()).append('@').append(stop.arrivalSeconds())
					.append('/').append(stop.departureSeconds())
					.append(stop.pickupType() == 1 ? "[no-pickup]" : "")
					.append(stop.dropOffType() == 1 ? "[no-dropoff]" : ""));
			text.append('\n');
		}
		Map<String, PathwayEdge> edges = new LinkedHashMap<>();
		timetable.routeAccessData().pathwayEdges().forEach(edge -> edges.put(edge.id(), edge));
		for (TransferRule rule : timetable.routeAccessData().transferRules()) {
			text.append("  transfer ").append(rule.fromStationId()).append(' ').append(rule.fromLineId()).append("->")
				.append(rule.toLineId()).append(" min=").append(rule.minTransferSeconds())
				.append(" normal=").append(edgeText(edges.get(rule.pathwayEdgeId())))
				.append(" strict=").append(edgeText(edges.get(rule.strictStepFreePathwayEdgeId()))).append('\n');
		}
		for (ServiceCalendarDate exception : timetable.serviceCalendarDates()) {
			text.append("  exception ").append(exception.serviceId()).append(' ').append(exception.date())
				.append(" type=").append(exception.exceptionType()).append('\n');
		}
		return text.toString();
	}

	private static String edgeText(PathwayEdge edge) {
		if (edge == null) return "-";
		return edge.id() + "(dur=" + edge.durationSeconds() + ",m=" + edge.distanceMeters()
			+ (edge.includesStairs() ? ",stairs" : "") + "," + edge.accessibilityStatus() + ")";
	}
}
