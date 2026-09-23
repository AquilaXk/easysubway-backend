package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.adapter.out.persistence.JdbcRouteTimetableRepository;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * #1415 ③c: KRIC 4호선 실 시각표(라이브 수집→재구성→seed)로 백엔드 RAPTOR 플래너가 PLANNED 경로를
 * 처음으로 산출함을 실증한다. seed는 build-backend-timetable-seed 도구가 코리도 슬라이스에서 생성한
 * 실데이터(src/test/resources/timetable/line4-corridor-slice-seed.sql).
 */
@DisplayName("4호선 실데이터 timetable PLANNED 가동")
class RouteTimetableRaptorPlannerLine4PlannedTest {

	private static final String SANGNOKSU = "station-seoul-4-448";
	private static final String SADANG = "station-seoul-4-433";

	private RouteTimetable timetable;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:line4-planned;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
	@DisplayName("상록수→사당 평일 아침 조회는 실 시각표 기반 PLANNED 경로를 반환한다")
	void plansPlannedRouteFromRealLine4Timetable() {
		var query = new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			SANGNOKSU,
			SADANG,
			// 2026-07-06(월) 06:50 KST — 상록수 07:00 열차를 탈 수 있는 평일 아침.
			new JourneyRaptorQuery.DepartAt(Instant.parse("2026-07-05T21:50:00Z")),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.SLOW,
			JourneyRequest.MobilityProfile.SLOW,
			JourneyRequest.ConstraintMode.NONE,
			0,
			3,
			() -> false
		);

		var plan = new RouteTimetableRaptorPlanner().journeyItineraries(query, timetable);

		assertThat(plan.itineraries()).isNotEmpty();
		var best = plan.itineraries().getFirst();
		var firstRide = best.legs().stream()
			.filter(RouteTimetableRaptorPlanner.JourneyRideProjection.class::isInstance)
			.map(RouteTimetableRaptorPlanner.JourneyRideProjection.class::cast)
			.findFirst().orElseThrow();
		assertThat(firstRide.fromStationId()).isEqualTo(SANGNOKSU);
		assertThat(firstRide.toStationId()).isEqualTo(SADANG);
		long minutes = Duration.between(firstRide.plannedDepartureTime(), firstRide.plannedArrivalTime()).toMinutes();
		assertThat(minutes).isBetween(30L, 55L);
	}
}
