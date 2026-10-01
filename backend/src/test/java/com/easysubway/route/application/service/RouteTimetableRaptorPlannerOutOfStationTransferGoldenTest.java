package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyProfileResourcePolicy;
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
import java.time.ZoneId;
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
		assertThat(transferStep.transferLimitMinutes()).isEqualTo(60);
	}

	@Test
	@DisplayName("주간 14:00 하차 ↔ 14:40 승차(소요 40분): 주간 30분 초과에 따라 가상 비용 +600초 적용 및 재승차 판정 단언")
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
		assertThat(transferStep.transferLimitMinutes()).isEqualTo(30);
	}

	@Test
	@DisplayName("journeyItineraries 투영에서 노외 환승의 transferType, farePenalty, transferLimit 단언")
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
		assertThat(nightTransferLeg.transferLimitMinutes()).isEqualTo(60);
	}

	@Test
	@DisplayName("출발 시간대 profile도 노외 환승 여정을 반환하고 같은 준비시각 point 탐색과 같은 투영(재승차 판정 포함)을 낸다")
	void departureProfileReturnsOutOfStationTransferJourneysMatchingPointSearch() {
		var planner = new RouteTimetableRaptorPlanner();
		var compiled = planner.compile(timetable());

		// 주간 13:00~13:40(KST): 14:00 하차 -> 14:40 승차(40분 > 30분)는 재승차다.
		assertProfileMatchesPointAndCarriesTransfer(planner, compiled,
			Instant.parse("2026-07-06T04:00:00Z"), Instant.parse("2026-07-06T04:40:00Z"), "t1_day", true, 30);
		// 야간 20:00~20:30(KST): 20:50 하차 -> 21:30 승차(40분 <= 60분)는 정상 환승이다.
		assertProfileMatchesPointAndCarriesTransfer(planner, compiled,
			Instant.parse("2026-07-06T11:00:00Z"), Instant.parse("2026-07-06T11:30:00Z"), "t1_night", false, 60);
	}

	private static void assertProfileMatchesPointAndCarriesTransfer(
		RouteTimetableRaptorPlanner planner,
		RouteTimetableRaptorPlanner.CompiledTimetable compiled,
		Instant earliestReadyAt,
		Instant latestReadyAt,
		String firstTripId,
		boolean expectedFarePenalty,
		int expectedLimitMinutes
	) {
		var profileQuery = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV", ORIGIN, DESTINATION,
			new JourneyRaptorQuery.DepartBetween(earliestReadyAt, latestReadyAt),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1, 2, () -> false);

		var profile = planner.departureProfile(profileQuery, compiled,
			RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
			new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 32, 32));

		assertThat(profile).isNotEmpty();
		assertThat(profile).allSatisfy(point -> {
			var pointQuery = new JourneyRaptorQuery(
				"01ARZ3NDEKTSV4RRFFQ69G5FAV", ORIGIN, DESTINATION,
				new JourneyRaptorQuery.DepartAt(point.serviceDate().atStartOfDay(ZoneId.of("Asia/Seoul"))
					.plusSeconds(point.readyAtSeconds()).toInstant()),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.SLOW,
				JourneyRequest.MobilityProfile.SLOW,
				JourneyRequest.ConstraintMode.NONE,
				1, 2, () -> false);
			// profile frontier는 환승 여유가 더 큰 대안도 남기므로 point 결과를 포함하는지 본다.
			var pointItineraries = planner.journeyItineraries(pointQuery, compiled).itineraries();
			assertThat(pointItineraries).isNotEmpty();
			assertThat(point.itineraries()).containsAll(pointItineraries);
		});
		var itinerary = profile.stream()
			.flatMap(point -> point.itineraries().stream())
			.filter(candidate -> candidate.legs().stream().anyMatch(leg ->
				leg instanceof RouteTimetableRaptorPlanner.JourneyRideProjection ride && firstTripId.equals(ride.tripId())))
			.findFirst().orElseThrow();
		JourneyAccessProjection transferStep = itinerary.legs().stream()
			.filter(leg -> leg instanceof JourneyAccessProjection acc && acc.kind() == JourneyAccessKind.TRANSFER)
			.map(JourneyAccessProjection.class::cast)
			.findFirst().orElseThrow();
		assertThat(transferStep.fromStationId()).isEqualTo(MID_OUT);
		assertThat(transferStep.toStationId()).isEqualTo(MID_IN);
		assertThat(transferStep.transferType()).isEqualTo("OUT_OF_STATION");
		assertThat(transferStep.farePenaltyApplies()).isEqualTo(expectedFarePenalty);
		assertThat(transferStep.transferLimitMinutes()).isEqualTo(expectedLimitMinutes);
	}

	@Test
	@DisplayName("통로 근거 없는 역 밖 환승만 있으면 출발 시간대 profile도 point 탐색처럼 여정을 만들지 않는다")
	void departureProfileRejectsOutOfStationTransferWithoutPathwayEvidenceLikePointSearch() {
		var base = timetable();
		var access = base.routeAccessData();
		var withoutPathway = new RouteTimetable(
			base.serviceCalendars(), base.serviceCalendarDates(), base.transitRoutes(), base.transitTrips(),
			base.transitStopTimes(), base.transitFrequencies(), base.officialFares(), base.feedEndDate(),
			new RouteAccessData(
				access.pathwayNodes(),
				access.pathwayEdges().stream().filter(edge -> !edge.id().equals("b-c-out-transfer-edge")).toList(),
				List.of(new TransferRule(
					"b-c-no-pathway-rule", MID_OUT, "l1", MID_IN, "l2", "OUT_OF_STATION",
					600, null, null, "VERIFIED")),
				access.routeEdgeEvidence().stream()
					.filter(evidence -> !evidence.id().equals("b-c-transfer-evidence")).toList()));
		var planner = new RouteTimetableRaptorPlanner();
		var compiled = planner.compile(withoutPathway);

		var point = planner.journeyItineraries(departAt(Instant.parse("2026-07-06T04:30:00Z")), compiled);
		var profile = planner.departureProfile(
			departBetween(Instant.parse("2026-07-06T04:00:00Z"), Instant.parse("2026-07-06T04:40:00Z")),
			compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), PROFILE_LIMITS);

		assertThat(point.itineraries()).isEmpty();
		assertThat(profile.stream().flatMap(entry -> entry.itineraries().stream())).isEmpty();
	}

	@Test
	@DisplayName("출발 시간대 profile은 도착 노선이 다른 역 밖 통로와 그날 운행하지 않는 패턴으로 환승하지 않는다")
	void departureProfileSkipsFootpathsFromOtherArrivalLinesAndIdlePatterns() {
		var base = timetable();
		var access = base.routeAccessData();
		var idle = new ServiceCalendar(
			"idle", true, true, true, true, true, true, true,
			SERVICE_DATE.plusYears(1), SERVICE_DATE.plusYears(1).plusDays(7), "Asia/Seoul");
		var otherLineEdge = new PathwayEdge(
			"b3-c-out-transfer-edge", MID_OUT + ":l3", MID_IN + ":l2", 60, 40, false, false, 100,
			"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED");
		List<TransferRule> rules = new ArrayList<>(access.transferRules());
		rules.add(new TransferRule(
			"b3-c-transfer-rule", MID_OUT, "l3", MID_IN, "l2", "OUT_OF_STATION",
			60, otherLineEdge.id(), otherLineEdge.id(), "VERIFIED"));
		List<PathwayNode> nodes = new ArrayList<>(access.pathwayNodes());
		nodes.add(new PathwayNode(MID_OUT + ":l3", MID_OUT, "l3", "PLATFORM"));
		List<PathwayEdge> edges = new ArrayList<>(access.pathwayEdges());
		edges.add(otherLineEdge);
		List<RouteEdgeEvidence> evidence = new ArrayList<>(access.routeEdgeEvidence());
		evidence.add(new RouteEdgeEvidence(
			"b3-c-transfer-evidence", MID_IN, "l2", otherLineEdge.id(), "TRANSFER",
			"OFFICIAL_SOURCE", "VERIFIED", true, null));
		List<TransitStopTime> stopTimes = new ArrayList<>(base.transitStopTimes());
		// 그날 운행하지 않는 3호선 패턴: 운행했다면 2호선보다 먼저 도착하는 시각이다.
		stopTimes.add(new TransitStopTime("t3_idle", 1, MID_IN, "l3", 51000, 51000, 0, 0));
		stopTimes.add(new TransitStopTime("t3_idle", 2, DESTINATION, "l3", 51600, 51600, 0, 0));
		List<TransitRoute> routes = new ArrayList<>(base.transitRoutes());
		routes.add(new TransitRoute("r3", "l3", "3호선", "3호선", "0", "Asia/Seoul"));
		List<TransitTrip> trips = new ArrayList<>(base.transitTrips());
		trips.add(new TransitTrip("t3_idle", "r3", "idle", "d행", "0", "LOCAL", 0));
		var extended = new RouteTimetable(
			List.of(base.serviceCalendars().getFirst(), idle), base.serviceCalendarDates(), routes, trips,
			stopTimes, base.transitFrequencies(), base.officialFares(), base.feedEndDate(),
			new RouteAccessData(nodes, edges, rules, evidence));
		var planner = new RouteTimetableRaptorPlanner();
		var compiled = planner.compile(extended);

		assertProfileMatchesPointAndCarriesTransfer(planner, compiled,
			Instant.parse("2026-07-06T04:00:00Z"), Instant.parse("2026-07-06T04:40:00Z"), "t1_day", true, 30);
		var itineraries = planner.departureProfile(
			departBetween(Instant.parse("2026-07-06T04:00:00Z"), Instant.parse("2026-07-06T04:40:00Z")),
			compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(), PROFILE_LIMITS)
			.stream().flatMap(entry -> entry.itineraries().stream()).toList();
		assertThat(itineraries).isNotEmpty().allSatisfy(itinerary -> {
			assertThat(itinerary.legs()).noneMatch(leg ->
				leg instanceof RouteTimetableRaptorPlanner.JourneyRideProjection ride && "t3_idle".equals(ride.tripId()));
			// 1호선으로 도착한 승객은 3호선 승강장에서 나가는 통로(40m)가 아니라 1호선 통로(400m)를 쓴다.
			assertThat(itinerary.legs()).filteredOn(leg ->
					leg instanceof JourneyAccessProjection acc && acc.kind() == JourneyAccessKind.TRANSFER)
				.singleElement()
				.satisfies(leg -> assertThat(((JourneyAccessProjection) leg).distanceMeters()).isEqualTo(400));
		});
	}

	private static final JourneyProfileResourcePolicy.ProfilePlanningLimits PROFILE_LIMITS =
		new JourneyProfileResourcePolicy.ProfilePlanningLimits(100_000L, 32, 32, 32);

	private static JourneyRaptorQuery departAt(Instant departAt) {
		return journeyQuery(new JourneyRaptorQuery.DepartAt(departAt));
	}

	private static JourneyRaptorQuery departBetween(Instant earliestReadyAt, Instant latestReadyAt) {
		return journeyQuery(new JourneyRaptorQuery.DepartBetween(earliestReadyAt, latestReadyAt));
	}

	private static JourneyRaptorQuery journeyQuery(JourneyRaptorQuery.TemporalQuery temporalQuery) {
		return new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV", ORIGIN, DESTINATION, temporalQuery,
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			1, 2, () -> false);
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
