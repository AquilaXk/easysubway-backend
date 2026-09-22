package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteV2SearchUseCase.SearchRouteV2Command;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayEdge;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.PathwayNode;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteAccessData;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteEdgeEvidence;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.ServiceCalendar;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitRoute;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitStopTime;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransitTrip;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.TransferRule;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.OfficialFare;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteStep;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import com.easysubway.journey.application.JourneyRequestMeasurement;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessKind;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.ScanWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("노외 환승 Footpath Relaxation 및 심야 특례 골든 테스트")
class RouteTimetableRaptorPlannerOutOfStationTransferGoldenTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	private static final String ORIGIN = "station-a";
	private static final String MID_OUT = "station-b";
	private static final String MID_IN = "station-c";
	private static final String DESTINATION = "station-d";

	@Test
	@DisplayName("야간 20:50 하차 ↔ 21:30 승차(소요 40분): 야간 60분 특례에 따라 정상 환승(페널티 0초, 1400원) 단언")
	void nightTransferWithinSixtyMinutesAppliesNoPenalty() {
		var planner = new RouteTimetableRaptorPlanner();
		var command = new SearchRouteV2Command(
			ORIGIN,
			DESTINATION,
			OffsetDateTime.of(2026, 7, 6, 20, 20, 0, 0, ZoneOffset.ofHours(9)),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			2
		);

		List<RouteSearchResult> results = planner.search(command, timetable());

		assertThat(results).isNotEmpty();
		RouteSearchResult result = results.getFirst();

		// 환승 스텝 검증: station-b -> station-c 노외 환승
		RouteStep transferStep = result.steps().stream()
			.filter(step -> "transfer".equals(step.stepType()))
			.findFirst()
			.orElseThrow();
		assertThat(transferStep.fromStationId()).isEqualTo(MID_OUT);
		assertThat(transferStep.toStationId()).isEqualTo(MID_IN);

		// 물리 도착 시각 유지 (21:50 열차 도착 + Senior 보행 배율 적용 출구 243초 = 21:54:03 도착, 20:20 출발 기준 94분 소요)
		assertThat(result.score()).isEqualTo(94);

		// 요금: 정상 환승으로 단일 요금 1400원
		assertThat(result.officialFare()).isNotNull();
		assertThat(result.officialFare().adultFareWon()).isEqualTo(1400);
	}

	@Test
	@DisplayName("주간 14:00 하차 ↔ 14:40 승차(소요 40분): 주간 30분 초과에 따라 가상 비용 +600초 적용 및 2800원 요금 단언")
	void daytimeTransferExceedingThirtyMinutesAppliesPenalty() {
		var planner = new RouteTimetableRaptorPlanner();
		var command = new SearchRouteV2Command(
			ORIGIN,
			DESTINATION,
			OffsetDateTime.of(2026, 7, 6, 13, 30, 0, 0, ZoneOffset.ofHours(9)),
			MobilityType.SENIOR,
			ConstraintMode.ALLOW_WITH_WARNINGS,
			false,
			1,
			2
		);

		List<RouteSearchResult> results = planner.search(command, timetable());

		assertThat(results).isNotEmpty();
		RouteSearchResult result = results.getFirst();

		RouteStep transferStep = result.steps().stream()
			.filter(step -> "transfer".equals(step.stepType()))
			.findFirst()
			.orElseThrow();
		assertThat(transferStep.fromStationId()).isEqualTo(MID_OUT);
		assertThat(transferStep.toStationId()).isEqualTo(MID_IN);

		// 가상 비용 +600초(10분) 적용되어 burdenCost(score)는 94 + 10 = 104
		assertThat(result.score()).isEqualTo(104);

		// 요금: 환승 유효시간 초과로 개별 구간 합산 2800원
		assertThat(result.officialFare()).isNotNull();
		assertThat(result.officialFare().adultFareWon()).isEqualTo(2800);
	}

	@Test
	@DisplayName("journeyItineraries 투영에서 노외 환승의 transferType, farePenalty, additionalFare, transferLimit 단언")
	void journeyItinerariesProjectsOutOfStationTransferFields() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = timetable();
		var compiled = planner.compile(timetable);

		// Daytime command (elapsed 40 min > limit 30 min -> timeout = true)
		var dayCommand = new SearchRouteV2Command(
			ORIGIN, DESTINATION,
			OffsetDateTime.of(2026, 7, 6, 13, 30, 0, 0, ZoneOffset.ofHours(9)),
			MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS, false, 1, 2
		);
		var dayPlan = planner.journeyItineraries(
			dayCommand, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-day"), "req-day", "sha", 1L
		);
		assertThat(dayPlan.itineraries()).isNotEmpty();
		var dayItinerary = dayPlan.itineraries().getFirst();
		var dayTransferLeg = dayItinerary.legs().stream()
			.filter(leg -> leg instanceof JourneyAccessProjection acc && acc.kind() == JourneyAccessKind.TRANSFER)
			.map(JourneyAccessProjection.class::cast)
			.findFirst().orElseThrow();
		assertThat(dayTransferLeg.transferType()).isEqualTo("OUT_OF_STATION");
		assertThat(dayTransferLeg.farePenaltyApplies()).isTrue();
		assertThat(dayTransferLeg.additionalFareWon()).isEqualTo(1400);
		assertThat(dayTransferLeg.transferLimitMinutes()).isEqualTo(30);

		// Nighttime command (elapsed 40 min <= limit 60 min -> timeout = false)
		var nightCommand = new SearchRouteV2Command(
			ORIGIN, DESTINATION,
			OffsetDateTime.of(2026, 7, 6, 20, 20, 0, 0, ZoneOffset.ofHours(9)),
			MobilityType.SENIOR, ConstraintMode.ALLOW_WITH_WARNINGS, false, 1, 2
		);
		var nightPlan = planner.journeyItineraries(
			nightCommand, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyRequestMeasurement("req-night"), "req-night", "sha", 1L
		);
		assertThat(nightPlan.itineraries()).isNotEmpty();
		var nightItinerary = nightPlan.itineraries().getFirst();
		var nightTransferLeg = nightItinerary.legs().stream()
			.filter(leg -> leg instanceof JourneyAccessProjection acc && acc.kind() == JourneyAccessKind.TRANSFER)
			.map(JourneyAccessProjection.class::cast)
			.findFirst().orElseThrow();
		assertThat(nightTransferLeg.transferType()).isEqualTo("OUT_OF_STATION");
		assertThat(nightTransferLeg.farePenaltyApplies()).isFalse();
		assertThat(nightTransferLeg.additionalFareWon()).isEqualTo(0);
		assertThat(nightTransferLeg.transferLimitMinutes()).isEqualTo(60);
	}

	@Test
	@DisplayName("relaxFootpaths 도달 상태 및 미도달 상태 분기 검증")
	void relaxFootpathsReachedAndUnreachedBranches() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = timetable();
		var compiled = planner.compile(timetable);

		var workspace = new ScanWorkspace();
		workspace.prepare(10, 5, 5);

		// station-b(index 1)을 nextMarkedStops에 추가하지만 slot arrivalSeconds는 UNREACHED
		// -> reached가 false로 유지되어 markNext가 호출되지 않음
		workspace.nextMarkedStops[0] = 1;
		workspace.nextMarkedStopCount = 1;

		RouteTimetableRaptorPlanner.relaxFootpaths(compiled, workspace, 0);
		// reached == false -> toStation(station-c)이 마킹되지 않음
		assertThat(workspace.nextMarkedStopCount).isEqualTo(1);
	}

	static RouteTimetable timetable() {
		var daily = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE.plusDays(7), "Asia/Seoul");

		List<TransitRoute> routes = List.of(
			new TransitRoute("r1", "l1", "1호선", "1호선", "0", "Asia/Seoul"),
			new TransitRoute("r2", "l2", "2호선", "2호선", "0", "Asia/Seoul")
		);

		// 야간: t1_night 20:30~20:50 (73800~75000), t2_night 21:30~21:50 (77400~78600)
		// 주간: t1_day 13:40~14:00 (49200~50400), t2_day 14:40~15:00 (52800~54000)
		List<TransitTrip> trips = List.of(
			new TransitTrip("t1_day", "r1", "daily", "b행", "0", "LOCAL", 0),
			new TransitTrip("t2_day", "r2", "daily", "d행", "0", "LOCAL", 0),
			new TransitTrip("t1_night", "r1", "daily", "b행", "0", "LOCAL", 0),
			new TransitTrip("t2_night", "r2", "daily", "d행", "0", "LOCAL", 0)
		);

		List<TransitStopTime> stopTimes = List.of(
			new TransitStopTime("t1_day", 1, ORIGIN, "l1", 49200, 49200, 0, 0),
			new TransitStopTime("t1_day", 2, MID_OUT, "l1", 50400, 50400, 0, 0),
			new TransitStopTime("t2_day", 1, MID_IN, "l2", 52800, 52800, 0, 0),
			new TransitStopTime("t2_day", 2, DESTINATION, "l2", 54000, 54000, 0, 0),

			new TransitStopTime("t1_night", 1, ORIGIN, "l1", 73800, 73800, 0, 0),
			new TransitStopTime("t1_night", 2, MID_OUT, "l1", 75000, 75000, 0, 0),
			new TransitStopTime("t2_night", 1, MID_IN, "l2", 77400, 77400, 0, 0),
			new TransitStopTime("t2_night", 2, DESTINATION, "l2", 78600, 78600, 0, 0)
		);

		List<OfficialFare> officialFares = List.of(
			new OfficialFare("t1_day", ORIGIN, MID_OUT, 1400, "KRW", "source-1", "snap-1"),
			new OfficialFare("t2_day", MID_IN, DESTINATION, 1400, "KRW", "source-1", "snap-1"),
			new OfficialFare("t1_night", ORIGIN, MID_OUT, 1400, "KRW", "source-1", "snap-1"),
			new OfficialFare("t2_night", MID_IN, DESTINATION, 1400, "KRW", "source-1", "snap-1")
		);

		List<PathwayNode> nodes = new ArrayList<>();
		List<PathwayEdge> edges = new ArrayList<>();
		List<RouteEdgeEvidence> evidence = new ArrayList<>();

		// Entry / Exit nodes and edges for station-a, station-b, station-c, station-d
		addAccess(nodes, edges, evidence, ORIGIN, "l1");
		addAccess(nodes, edges, evidence, MID_OUT, "l1");
		addAccess(nodes, edges, evidence, MID_IN, "l2");
		addAccess(nodes, edges, evidence, DESTINATION, "l2");

		// Out of station transfer link: station-b:l1 -> station-c:l2 (duration 600s, distance 400m)
		String transferEdgeId = "b-c-out-transfer-edge";
		var transferEdge = new PathwayEdge(
			transferEdgeId, MID_OUT + ":l1", MID_IN + ":l2", 600, 400, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"
		);
		edges.add(transferEdge);
		evidence.add(new RouteEdgeEvidence(
			"b-c-transfer-evidence", MID_IN, "l2", transferEdgeId, "TRANSFER",
			"OFFICIAL_SOURCE", "VERIFIED", true, null
		));

		List<TransferRule> transferRules = List.of(
			new TransferRule(
				"b-c-transfer-rule", MID_OUT, "l1", MID_IN, "l2", "OUT_OF_STATION",
				600, transferEdgeId, transferEdgeId, "VERIFIED"
			),
			new TransferRule(
				"empty-out-rule", MID_OUT, "l1", DESTINATION, "l2", "OUT_OF_STATION",
				0, "nonexistent-edge", "nonexistent-edge", "VERIFIED"
			),
			new TransferRule(
				"unknown-st-rule", "unknown-station", "l1", MID_IN, "l2", "OUT_OF_STATION",
				600, null, null, "VERIFIED"
			),
			new TransferRule(
				"diff-st-no-type-rule", MID_OUT, "l1", MID_IN, "l2", null,
				600, transferEdgeId, transferEdgeId, "VERIFIED"
			)
		);

		var accessData = new RouteAccessData(nodes, edges, transferRules, evidence);

		return new RouteTimetable(
			List.of(daily), List.of(), routes, trips, stopTimes,
			List.of(), officialFares, null, accessData
		);
	}

	private static void addAccess(
		List<PathwayNode> nodes,
		List<PathwayEdge> edges,
		List<RouteEdgeEvidence> evidence,
		String station,
		String line
	) {
		String key = station + "-" + line;
		var entry = new PathwayEdge(
			key + "-entry", key + "-entry-from", station + ":" + line, 180, 120, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		var exit = new PathwayEdge(
			key + "-exit", station + ":" + line, key + "-exit-to", 180, 120, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		edges.add(entry);
		edges.add(exit);
		nodes.add(new PathwayNode(entry.fromNodeId(), station, null, "ENTRANCE"));
		nodes.add(new PathwayNode(station + ":" + line, station, line, "PLATFORM"));
		nodes.add(new PathwayNode(exit.toNodeId(), station, null, "EXIT"));
		evidence.add(new RouteEdgeEvidence(key + "-entry-evidence", station, line, entry.id(), "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));
		evidence.add(new RouteEdgeEvidence(key + "-exit-evidence", station, line, exit.id(), "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));
	}
}
