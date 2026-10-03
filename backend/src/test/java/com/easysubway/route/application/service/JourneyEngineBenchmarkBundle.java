package com.easysubway.route.application.service;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * #460: 성능 회귀 게이트용 결정적 격자 번들.
 *
 * <p>운영 범위 번들은 배포 서버와 객체 저장소에만 있어 CI에서 쓸 수 없다. 대신 코드로 고정한 격자형 노선망을 쓴다.
 * 가로·세로 노선(양방향)이 짝수 행·열 교차점에서 만나고, 가로 노선은 4편마다 급행(홀수 열 무정차)이다.
 * 환승역마다 계단 동선과 무단차 동선을 둘 다 둔다. {@link #METRO}는 192역·16노선·약 7천 편·약 11만 정차로
 * 수도권 운영 범위보다 작고 망 모양도 단순하다. 이 번들의 수치는 같은 번들끼리의 회귀 비교에만 쓰고
 * 운영 지연의 추정치로 쓰지 않는다.</p>
 */
final class JourneyEngineBenchmarkBundle {

	/** 격자 번들 정의. 값을 바꾸면 번들 digest가 바뀌어 기준선을 새로 만들어야 한다. */
	record Grid(String id, int linesPerAxis, int headwaySeconds, int firstDeparture, int lastDeparture) {
		int size() { return linesPerAxis * 2; }
	}

	/** 도시철도 규모: 16노선·192역·5분 간격 05:30~24:30. point 탐색과 컴파일을 잰다. */
	static final Grid METRO = new Grid("metro-grid-v1", 8, 300, 19_800, 88_200);
	/** 구역 규모: 8노선·48역·10분 간격 06:00~23:00. 프로필 탐색(도착 희망·출발 시간대·막차)을 잰다. */
	static final Grid DISTRICT = new Grid("district-grid-v1", 4, 600, 21_600, 82_800);
	private static final int HOP_SECONDS = 120;
	private static final int DWELL_SECONDS = 30;
	private static final String ZONE = "Asia/Seoul";

	private JourneyEngineBenchmarkBundle() {
	}

	static RouteTimetable build(Grid grid) {
		Random random = new Random(460L);
		int linesPerAxis = grid.linesPerAxis();
		List<TransitRoute> routes = new ArrayList<>();
		List<TransitTrip> trips = new ArrayList<>();
		List<TransitStopTime> stops = new ArrayList<>();
		for (int index = 0; index < linesPerAxis; index += 1) {
			addLine(grid, "H" + index, horizontal(grid, index * 2), index, true, routes, trips, stops);
			addLine(grid, "V" + index, vertical(grid, index * 2), linesPerAxis + index, false, routes, trips, stops);
		}
		List<PathwayNode> nodes = new ArrayList<>();
		List<PathwayEdge> edges = new ArrayList<>();
		List<TransferRule> rules = new ArrayList<>();
		List<RouteEdgeEvidence> evidence = new ArrayList<>();
		for (int row = 0; row < linesPerAxis; row += 1) {
			for (int column = 0; column < linesPerAxis; column += 1) {
				String station = station(row * 2, column * 2);
				String horizontal = "H" + row;
				String vertical = "V" + column;
				nodes.add(new PathwayNode(station + "-" + horizontal, station, horizontal, "PLATFORM"));
				nodes.add(new PathwayNode(station + "-" + vertical, station, vertical, "PLATFORM"));
				addTransfer(station, horizontal, vertical, random, edges, rules, evidence);
				addTransfer(station, vertical, horizontal, random, edges, rules, evidence);
			}
		}
		ServiceCalendar daily = new ServiceCalendar("daily", true, true, true, true, true, true, true,
			JourneyEngineSyntheticBundles.WEDNESDAY.minusDays(7), JourneyEngineSyntheticBundles.WEDNESDAY.plusDays(7), ZONE);
		return new RouteTimetable(List.of(daily), List.of(), routes, trips, stops, List.of(), List.of(), null,
			new RouteAccessData(nodes, edges, rules, evidence));
	}

	static String station(int row, int column) {
		return "m-" + row + "-" + column;
	}

	/** 노선이 지나는 모든 역. 짝수 행 또는 짝수 열이다. */
	static List<String> stations(Grid grid) {
		List<String> stations = new ArrayList<>();
		for (int row = 0; row < grid.size(); row += 1) {
			for (int column = 0; column < grid.size(); column += 1) {
				if (row % 2 == 0 || column % 2 == 0) stations.add(station(row, column));
			}
		}
		return stations;
	}

	private static List<String> horizontal(Grid grid, int row) {
		List<String> stations = new ArrayList<>();
		for (int column = 0; column < grid.size(); column += 1) stations.add(station(row, column));
		return stations;
	}

	private static List<String> vertical(Grid grid, int column) {
		List<String> stations = new ArrayList<>();
		for (int row = 0; row < grid.size(); row += 1) stations.add(station(row, column));
		return stations;
	}

	private static void addLine(
		Grid grid, String line, List<String> stations, int lineOrdinal, boolean expressPattern,
		List<TransitRoute> routes, List<TransitTrip> trips, List<TransitStopTime> stops
	) {
		for (String direction : List.of("up", "down")) {
			List<String> sequence = new ArrayList<>(stations);
			if (direction.equals("down")) java.util.Collections.reverse(sequence);
			String routeId = "route-" + line + "-" + direction;
			routes.add(new TransitRoute(routeId, line, line, line, direction, ZONE));
			int departureIndex = 0;
			for (int departure = grid.firstDeparture() + lineOrdinal * 17; departure <= grid.lastDeparture();
				departure += grid.headwaySeconds()) {
				boolean express = expressPattern && departureIndex % 4 == 3;
				String tripId = "trip-" + line + "-" + direction + "-" + departureIndex;
				trips.add(new TransitTrip(tripId, routeId, "daily", sequence.getLast(), direction,
					express ? "EXPRESS" : "LOCAL", 0));
				int clock = departure;
				int sequenceNumber = 1;
				for (int position = 0; position < sequence.size(); position += 1) {
					boolean terminal = position == 0 || position == sequence.size() - 1;
					// 급행은 홀수 열(환승역이 아닌 역)을 통과한다.
					if (express && !terminal && Integer.parseInt(sequence.get(position).split("-")[2]) % 2 == 1) {
						clock += HOP_SECONDS - 20;
						continue;
					}
					int dwell = terminal ? 0 : DWELL_SECONDS;
					stops.add(new TransitStopTime(tripId, sequenceNumber++, sequence.get(position), line,
						clock, clock + dwell, 0, 0));
					clock += dwell + HOP_SECONDS;
				}
				departureIndex += 1;
			}
		}
	}

	private static void addTransfer(
		String station, String fromLine, String toLine, Random random,
		List<PathwayEdge> edges, List<TransferRule> rules, List<RouteEdgeEvidence> evidence
	) {
		String key = station + "-" + fromLine + "-" + toLine;
		String from = station + "-" + fromLine;
		String to = station + "-" + toLine;
		int stairsMeters = 60 + random.nextInt(140);
		int stepFreeMeters = stairsMeters + 40 + random.nextInt(160);
		edges.add(new PathwayEdge(key + "-stairs", from, to, 60 + random.nextInt(120), stairsMeters, false, true, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		edges.add(new PathwayEdge(key + "-step-free", from, to, 120 + random.nextInt(180), stepFreeMeters, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
		for (String edge : List.of(key + "-stairs", key + "-step-free")) {
			evidence.add(new RouteEdgeEvidence("ev-" + edge, station, toLine, edge, "TRANSFER",
				"OFFICIAL_SOURCE", "VERIFIED", true, null));
		}
		rules.add(new TransferRule("rule-" + key, station, fromLine, station, toLine, "IN_STATION", 60,
			key + "-stairs", key + "-step-free", "VERIFIED"));
	}
}
