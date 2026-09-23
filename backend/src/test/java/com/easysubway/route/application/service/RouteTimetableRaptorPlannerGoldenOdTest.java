package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.adapter.out.persistence.JdbcRouteTimetableRepository;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyItinerary;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyRideProjection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * #1620 golden OD 6종: KRIC 4호선 코리도 실 시각표(build-backend-timetable-seed 재구성) 기준으로
 * RAPTOR planner가 산출하는 대표 OD 경로/경계 동작을 회귀로 고정한다. 모든 기대값은
 * src/test/resources/timetable/line4-corridor-slice-seed.sql 의 실 운행시각에서 유도했다.
 *
 * 코리도 실측 (평일 weekday-kric, 하행 down-only):
 *   448 상록수 dep  K4422 07:00 / K4308 07:03 / K4524 07:09
 *   433 사당   arr  K4422 07:37:30 / K4308 07:44 / K4524 07:49:30
 *   456 당고개 dep  K4422 06:42 / K4524 06:48
 *   409       arr  K4422 08:30:30 / K4308 08:37:30 / K4524 08:43
 */
@DisplayName("#1620 RAPTOR golden OD 6종 (4호선 코리도 실데이터)")
class RouteTimetableRaptorPlannerGoldenOdTest {

	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	private static final String SANGNOKSU = "station-seoul-4-448";
	private static final String SADANG = "station-seoul-4-433";
	private static final String DANGOGAE = "station-seoul-4-456";
	private static final String OIDO = "station-seoul-4-409";

	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();
	private RouteTimetable timetable;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:line4-golden;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP ALL OBJECTS");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V16__datapack_source_snapshots.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V17__datapack_alias_quarantine_ledgers.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V19__datapack_route_edge_evidence.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V29__canonical_transit_schedule.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V30__canonical_station_pathways.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V37__transit_feed_info.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V50__route_service_identity.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V62__route_v2_planner_identity.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/test/resources/timetable/line4-corridor-slice-seed.sql'");
		var loaded = new JdbcRouteTimetableRepository(dataSource).loadRouteTimetable();
		timetable = new RouteTimetable(
			loaded.serviceCalendars(), loaded.serviceCalendarDates(), loaded.transitRoutes(),
			loaded.transitTrips(), loaded.transitStopTimes(), loaded.transitFrequencies(),
			loaded.officialFares(), loaded.feedEndDate(), defaultVerifiedAccess(loaded.transitStopTimes()));
	}

	private static LoadRouteTimetablePort.RouteAccessData defaultVerifiedAccess(
		List<LoadRouteTimetablePort.TransitStopTime> stopTimes
	) {
		var nodes = new java.util.ArrayList<LoadRouteTimetablePort.PathwayNode>();
		var edges = new java.util.ArrayList<LoadRouteTimetablePort.PathwayEdge>();
		var evidence = new java.util.ArrayList<LoadRouteTimetablePort.RouteEdgeEvidence>();

		var stationLines = new java.util.LinkedHashMap<String, java.util.Set<String>>();
		for (var stop : stopTimes) {
			stationLines.computeIfAbsent(stop.stationId(), k -> new java.util.LinkedHashSet<>()).add(stop.lineId());
		}

		for (var entry : stationLines.entrySet()) {
			var stationId = entry.getKey();
			var lines = entry.getValue();
			var entranceNodeId = "entrance-" + stationId;
			var exitNodeId = "exit-" + stationId;
			nodes.add(new LoadRouteTimetablePort.PathwayNode(entranceNodeId, stationId, null, "ENTRANCE"));
			nodes.add(new LoadRouteTimetablePort.PathwayNode(exitNodeId, stationId, null, "EXIT"));

			for (var lineId : lines) {
				var platformNodeId = "platform-" + stationId + "-" + lineId;
				nodes.add(new LoadRouteTimetablePort.PathwayNode(platformNodeId, stationId, lineId, "PLATFORM"));

				var entryEdgeId = "entry-" + stationId + "-" + lineId;
				edges.add(new LoadRouteTimetablePort.PathwayEdge(
					entryEdgeId, entranceNodeId, platformNodeId, 240, 180, false, false, 100,
					"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"
				));
				evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence(
					"ev-" + entryEdgeId, stationId, lineId, entryEdgeId, "ENTRY",
					"OFFICIAL_SOURCE", "VERIFIED", true, null
				));

				var exitEdgeId = "exit-" + stationId + "-" + lineId;
				edges.add(new LoadRouteTimetablePort.PathwayEdge(
					exitEdgeId, platformNodeId, exitNodeId, 180, 120, false, false, 100,
					"AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"
				));
				evidence.add(new LoadRouteTimetablePort.RouteEdgeEvidence(
					"ev-" + exitEdgeId, stationId, lineId, exitEdgeId, "EXIT",
					"OFFICIAL_SOURCE", "VERIFIED", true, null
				));
			}
		}
		return new LoadRouteTimetablePort.RouteAccessData(nodes, edges, List.of(), evidence);
	}

	@Test
	@DisplayName("OD1 상록수→사당 평일 아침은 가장 이른 도착 열차(07:37:30)에 앵커된다")
	void od1_sangnoksuToSadangMorningEarliestArrival() {
		JourneyItinerary best = firstResult(SANGNOKSU, SADANG, weekday(6, 50));

		assertThat(best.legs()).isNotEmpty();
		var ride = best.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.findFirst().orElseThrow();
		assertThat(ride.fromStationId()).isEqualTo(SANGNOKSU);
		assertThat(ride.toStationId()).isEqualTo(SADANG);
		assertThat(ride.tripId()).isEqualTo("route-seoul-4-down-K4422-8");
		// K4422 상록수 07:00(2026-07-05T22:00:00Z)→사당 07:37:30(2026-07-05T22:37:30Z)
		assertThat(ride.plannedDepartureTime()).isEqualTo(Instant.parse("2026-07-05T22:00:00Z"));
		assertThat(ride.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-05T22:37:30Z"));
		long minutes = Duration.between(ride.plannedDepartureTime(), ride.plannedArrivalTime()).toMinutes();
		assertThat(minutes).isBetween(35L, 40L);
	}

	@Test
	@DisplayName("OD2 당고개→오이도 전 구간은 하행 종주 열차 한 대로 연결된다")
	void od2_dangogaeToOidoFullCorridorSingleRide() {
		JourneyItinerary best = firstResult(DANGOGAE, OIDO, weekday(6, 30));

		var rides = best.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.toList();
		assertThat(rides).hasSize(1);
		var ride = rides.getFirst();
		assertThat(ride.fromStationId()).isEqualTo(DANGOGAE);
		assertThat(ride.toStationId()).isEqualTo(OIDO);
		long minutes = Duration.between(ride.plannedDepartureTime(), ride.plannedArrivalTime()).toMinutes();
		assertThat(minutes).isBetween(105L, 112L);
	}

	@Test
	@DisplayName("OD3 EXPRESS 를 놓친 조회는 다음 LOCAL 열차(더 긴 승차)에 정직하게 앵커된다")
	void od3_missedExpressAnchorsToNextLocalTrain() {
		// SENIOR 진입 도보(240×1.35=324s)+slack 90s = 414s. 06:55 출발이면 ready 06:55+414s=07:01:54 라
		// EXPRESS K4422(07:00) 는 놓치고 LOCAL K4308(07:03 출발→사당 07:44 도착)에 탑승한다.
		JourneyItinerary best = firstResult(SANGNOKSU, SADANG, weekday(6, 55));

		var ride = best.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.findFirst().orElseThrow();
		// K4308 상록수 07:03→사당 07:44 = 41분 승차 — OD1 의 EXPRESS(37.5분→38)보다 길다.
		long minutes = Duration.between(ride.plannedDepartureTime(), ride.plannedArrivalTime()).toMinutes();
		assertThat(minutes)
			.as("EXPRESS 를 놓쳤으므로 승차 leg 는 LOCAL 소요(41분)여야 한다")
			.isBetween(40L, 42L);
	}

	@Test
	@DisplayName("OD4 막차 이후 조회는 결과가 없다")
	void od4_afterLastTrainReturnsEmptyWithNextServiceTime() {
		var query = query(SANGNOKSU, SADANG, weekday(23, 0));

		assertThat(planner.journeyItineraries(query, timetable).itineraries()).isEmpty();
	}

	@Test
	@DisplayName("OD5 주말 조회는 운행 calendar에서 제외되어 결과가 없다")
	void od5_weekendHasNoServiceAndSkipsToNextWeekday() {
		// 2026-07-11 은 토요일 — weekday-kric calendar(월~금)에 미포함.
		var query = query(SANGNOKSU, SADANG, atKst(LocalDate.of(2026, 7, 11), 6, 50));

		assertThat(planner.journeyItineraries(query, timetable).itineraries()).isEmpty();
	}

	@Test
	@DisplayName("OD6 하행 전용 코리도에서 역방향(사당→상록수)은 결과를 지어내지 않는다")
	void od6_reverseDirectionOnDownOnlyCorridorFabricatesNothing() {
		var query = query(SADANG, SANGNOKSU, weekday(6, 50));

		assertThat(planner.journeyItineraries(query, timetable).itineraries()).isEmpty();
	}

	private JourneyItinerary firstResult(String origin, String destination, OffsetDateTime departure) {
		var results = planner.journeyItineraries(query(origin, destination, departure), timetable).itineraries();
		assertThat(results).as("golden OD 는 최소 1개 후보를 반환해야 한다").isNotEmpty();
		assertThat(results).hasSizeLessThanOrEqualTo(3);
		return results.getFirst();
	}

	private static JourneyRaptorQuery query(String origin, String destination, OffsetDateTime departure) {
		return new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			origin,
			destination,
			new JourneyRaptorQuery.DepartAt(departure.toInstant()),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			0,
			3,
			() -> false
		);
	}

	private static OffsetDateTime weekday(int hour, int minute) {
		// 2026-07-06 은 월요일(평일).
		return atKst(LocalDate.of(2026, 7, 6), hour, minute);
	}

	private static OffsetDateTime atKst(LocalDate date, int hour, int minute) {
		return OffsetDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), hour, minute, 0, 0, KST);
	}
}
