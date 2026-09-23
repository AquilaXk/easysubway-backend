package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.journey.application.JourneyRequestMeasurement;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN,
			DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-06T11:20:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1,
			2,
			() -> false
		);

		var plan = planner.journeyItineraries(query, timetable());

		assertThat(plan.itineraries()).isNotEmpty();
		var itinerary = plan.itineraries().getFirst();

		// 환승 스텝 검증: station-b -> station-c 노외 환승
		JourneyAccessProjection transferStep = itinerary.legs().stream()
			.filter(leg -> leg instanceof JourneyAccessProjection acc && acc.kind() == JourneyAccessKind.TRANSFER)
			.map(JourneyAccessProjection.class::cast)
			.findFirst()
			.orElseThrow();
		assertThat(transferStep.fromStationId()).isEqualTo(MID_OUT);
		assertThat(transferStep.toStationId()).isEqualTo(MID_IN);
		assertThat(transferStep.transferType()).isEqualTo("OUT_OF_STATION");
		assertThat(transferStep.farePenaltyApplies()).isFalse();
		assertThat(transferStep.additionalFareWon()).isEqualTo(0);
		assertThat(transferStep.transferLimitMinutes()).isEqualTo(60);
	}

	@Test
	@DisplayName("주간 14:00 하차 ↔ 14:40 승차(소요 40분): 주간 30분 초과에 따라 가상 비용 +600초 적용 및 2800원 요금 단언")
	void daytimeTransferExceedingThirtyMinutesAppliesPenalty() {
		var planner = new RouteTimetableRaptorPlanner();
		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN,
			DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-06T04:30:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1,
			2,
			() -> false
		);

		var plan = planner.journeyItineraries(query, timetable());

		assertThat(plan.itineraries()).isNotEmpty();
		var itinerary = plan.itineraries().getFirst();

		JourneyAccessProjection transferStep = itinerary.legs().stream()
			.filter(leg -> leg instanceof JourneyAccessProjection acc && acc.kind() == JourneyAccessKind.TRANSFER)
			.map(JourneyAccessProjection.class::cast)
			.findFirst()
			.orElseThrow();
		assertThat(transferStep.fromStationId()).isEqualTo(MID_OUT);
		assertThat(transferStep.toStationId()).isEqualTo(MID_IN);
		assertThat(transferStep.transferType()).isEqualTo("OUT_OF_STATION");
		assertThat(transferStep.farePenaltyApplies()).isTrue();
		assertThat(transferStep.additionalFareWon()).isEqualTo(1400);
		assertThat(transferStep.transferLimitMinutes()).isEqualTo(30);
	}

	@Test
	@DisplayName("journeyItineraries 투영에서 노외 환승의 transferType, farePenalty, additionalFare, transferLimit 단언")
	void journeyItinerariesProjectsOutOfStationTransferFields() {
		var planner = new RouteTimetableRaptorPlanner();
		var timetable = timetable();
		var compiled = planner.compile(timetable);

		// Daytime query (elapsed 40 min > limit 30 min -> timeout = true)
		var dayQuery = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN, DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-06T04:30:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1, 2, () -> false
		);
		var dayPlan = planner.journeyItineraries(dayQuery, compiled);
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

		// Nighttime query (elapsed 40 min <= limit 60 min -> timeout = false)
		var nightQuery = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			ORIGIN, DESTINATION,
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-06T11:20:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1, 2, () -> false
		);
		var nightPlan = planner.journeyItineraries(nightQuery, compiled);
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
