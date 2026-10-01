package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import com.easysubway.journey.application.JourneyProfileResourcePolicy;
import com.easysubway.journey.application.JourneyRaptorPruningInventoryV1;
import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Profile search golden regression test")
class RouteTimetableRaptorPlannerProfileGoldenTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 1);
	private static final long BASE_SEED = 20260929L;
	private static final int FIXTURE_COUNT = 20;
	private static final Path GOLDEN_DIR = Paths.get("src/test/resources/route/profile-golden");

	private final RouteTimetableRaptorPlanner planner = new RouteTimetableRaptorPlanner();

	@Test
	@DisplayName("20개 시드의 6x6 격자 시간표 프로필 탐색 결과가 골든 파일과 일치한다")
	void matchesProfileGoldenAcrossGridSeeds() throws IOException {
		boolean writeMode = Boolean.parseBoolean(System.getProperty("easysubway.golden.write", "false"))
			|| Boolean.parseBoolean(System.getenv("EASYSUBWAY_GOLDEN_WRITE"));
		if (writeMode && !Files.exists(GOLDEN_DIR)) {
			Files.createDirectories(GOLDEN_DIR);
		}

		for (int i = 0; i < FIXTURE_COUNT; i++) {
			long seed = BASE_SEED + i;
			String fixtureName = "grid-seed-" + seed + ".txt";
			Path goldenPath = GOLDEN_DIR.resolve(fixtureName);

			RouteTimetable timetable = generateGridTimetable(seed);
			var compiled = planner.compile(timetable);

			String requestId = String.format("01ARZ3NDEKTSV4RRFFQ69G5F%02d", i);
			var query = new JourneyRaptorQuery(
				requestId,
				"station-0-0",
				"station-5-5",
				new JourneyRaptorQuery.DepartBetween(instantAt(21_600), instantAt(25_200)),
				JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
				JourneyRequest.WalkingPace.STANDARD,
				JourneyRequest.MobilityProfile.STANDARD,
				JourneyRequest.ConstraintMode.NONE,
				2,
				1,
				() -> false);

			var observations = new JourneyProfilePruningObservationAccumulator(
				requestId, JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR);

			var profile = planner.departureProfile(
				query, compiled, RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
				testPolicy().profilePlanningLimits(), observations);

			String actualCanonical = canonical(profile, observations);

			if (writeMode) {
				Files.writeString(goldenPath, actualCanonical, StandardCharsets.UTF_8);
			} else {
				assertThat(Files.exists(goldenPath))
					.as("Golden file must exist: %s", goldenPath)
					.isTrue();
				String expectedCanonical = Files.readString(goldenPath, StandardCharsets.UTF_8);
				assertThat(actualCanonical)
					.as("Profile output must match golden for seed %d", seed)
					.isEqualTo(expectedCanonical);
			}
		}
	}

	@Test
	@DisplayName("출발 프로필 경계 픽스처(배차 간격·24시간, 자정 넘김, 3일 달력, frontier 충돌, 연결 여유)의 결과가 골든 파일과 일치한다")
	void matchesProfileGoldenAcrossDepartureProfileBoundaryFixtures() throws IOException {
		boolean writeMode = Boolean.parseBoolean(System.getenv("EASYSUBWAY_GOLDEN_WRITE"));
		if (writeMode && !Files.exists(GOLDEN_DIR)) {
			Files.createDirectories(GOLDEN_DIR);
		}
		record BoundaryFixture(String name, RouteTimetable timetable, JourneyRaptorQuery query) {
		}
		List<BoundaryFixture> fixtures = List.of(
			new BoundaryFixture("boundary-frequency-24h", RouteTimetableRaptorPlannerDepartureProfileTest.timetable(),
				boundaryQuery("01ARZ3NDEKTSV4RRFFQ69G5FB1", "station-a", "station-b",
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(32_000),
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(87_000),
					JourneyRequest.WalkingPace.SLOW, JourneyRequest.MobilityProfile.SLOW, 0, 1)),
			new BoundaryFixture("boundary-cross-cutoff", RouteTimetableRaptorPlannerDepartureProfileTest.crossCutoffTimetable(),
				boundaryQuery("01ARZ3NDEKTSV4RRFFQ69G5FB2", "station-a", "station-b",
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(96_000),
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(99_000),
					JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD, 0, 1)),
			new BoundaryFixture("boundary-three-day", RouteTimetableRaptorPlannerDepartureProfileTest.threeDayTimetable(),
				boundaryQuery("01ARZ3NDEKTSV4RRFFQ69G5FB3", "station-a", "station-b",
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(SERVICE_DATE, 10_800),
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(SERVICE_DATE.plusDays(2), 10_800),
					JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD, 0, 1)),
			new BoundaryFixture("boundary-frontier-collision", RouteTimetableRaptorPlannerDepartureProfileTest.frontierCollisionTimetable(),
				boundaryQuery("01ARZ3NDEKTSV4RRFFQ69G5FB4", "origin", "destination",
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(29_240),
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(29_241),
					JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD, 1, 3)),
			new BoundaryFixture("boundary-same-pattern-slack", RouteTimetableRaptorPlannerDepartureProfileTest.samePatternSlackTimetable(),
				boundaryQuery("01ARZ3NDEKTSV4RRFFQ69G5FB5", "origin", "destination",
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(29_240),
					RouteTimetableRaptorPlannerDepartureProfileTest.instantAt(29_241),
					JourneyRequest.WalkingPace.STANDARD, JourneyRequest.MobilityProfile.STANDARD, 1, 3)));

		for (BoundaryFixture fixture : fixtures) {
			Path goldenPath = GOLDEN_DIR.resolve(fixture.name() + ".txt");
			var observations = new JourneyProfilePruningObservationAccumulator(
				fixture.query().requestId(), JourneyRaptorPruningInventoryV1.FORWARD_RANGE_RAPTOR);
			var profile = planner.departureProfile(
				fixture.query(), planner.compile(fixture.timetable()),
				RouteTimetableRaptorPlanner.RealtimeOverlay.empty(),
				RouteTimetableRaptorPlannerDepartureProfileTest.policy().profilePlanningLimits(), observations);
			String actualCanonical = canonical(profile, observations);
			assertThat(profile).as("fixture %s must produce profile points", fixture.name()).isNotEmpty();
			if (writeMode) {
				Files.writeString(goldenPath, actualCanonical, StandardCharsets.UTF_8);
			} else {
				assertThat(Files.exists(goldenPath)).as("Golden file must exist: %s", goldenPath).isTrue();
				assertThat(actualCanonical)
					.as("Profile output must match golden for %s", fixture.name())
					.isEqualTo(Files.readString(goldenPath, StandardCharsets.UTF_8));
			}
		}
	}

	private static JourneyRaptorQuery boundaryQuery(
		String requestId, String origin, String destination, Instant from, Instant to,
		JourneyRequest.WalkingPace pace, JourneyRequest.MobilityProfile profile, int maxTransfers, int alternatives
	) {
		return new JourneyRaptorQuery(
			requestId, origin, destination, new JourneyRaptorQuery.DepartBetween(from, to),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED, pace, profile, JourneyRequest.ConstraintMode.NONE,
			maxTransfers, alternatives, () -> false);
	}

	static String canonical(
		List<RouteTimetableRaptorPlanner.JourneyDepartureProfilePoint> profile,
		JourneyProfilePruningObservationAccumulator observations
	) {
		StringBuilder sb = new StringBuilder();
		for (var point : profile) {
			sb.append("POINT readyAt=").append(point.readyAtSeconds()).append("\n");
			for (var it : point.itineraries()) {
				sb.append("  ITINERARY\n");
				for (var leg : it.legs()) {
					if (leg instanceof RouteTimetableRaptorPlanner.JourneyAccessProjection access) {
						sb.append("    LEG ACCESS kind=").append(access.kind())
							.append(" from=").append(access.fromStationId())
							.append(" to=").append(access.toStationId())
							.append(" dur=").append(access.durationSeconds())
							.append(" dist=").append(access.distanceMeters())
							.append(" stairs=").append(access.includesStairs())
							.append("\n");
					} else if (leg instanceof RouteTimetableRaptorPlanner.JourneyRideProjection ride) {
						sb.append("    LEG RIDE trip=").append(ride.tripId())
							.append(" line=").append(ride.lineId())
							.append(" from=").append(ride.fromStationId())
							.append(" to=").append(ride.toStationId())
							.append(" dep=").append(ride.plannedDepartureTime())
							.append(" arr=").append(ride.plannedArrivalTime())
							.append("\n");
					}
				}
				var m = it.metrics();
				sb.append("    METRICS transfers=").append(m.transfersUsed())
					.append(" moveSec=").append(m.accessMovementSeconds())
					.append(" dist=").append(m.accessDistanceMeters())
					.append(" burden=").append(m.accessibilityBurden())
					.append(" slack=").append(
						m.connectionSlack() instanceof JourneyProfileRaptorPort.MinimumTransferSeconds min
							? min.seconds()
							: "NoTransfer")
					.append("\n");
			}
		}
		if (observations != null) {
			sb.append("OBSERVATIONS\n");
			var counts = new TreeMap<>(observations.snapshot().countsByRuleId());
			counts.forEach((k, v) -> sb.append("  ").append(k).append("=").append(v).append("\n"));
		}
		return sb.toString();
	}

	static RouteTimetable generateGridTimetable(long seed) {
		Random rng = new Random(seed);
		var calendar = new ServiceCalendar(
			"daily", true, true, true, true, true, true, true,
			SERVICE_DATE, SERVICE_DATE, "Asia/Seoul");

		List<TransitRoute> routes = new ArrayList<>();
		List<TransitTrip> trips = new ArrayList<>();
		List<TransitStopTime> stopTimes = new ArrayList<>();

		// 6 horizontal lines (r: 0..5)
		for (int r = 0; r < 6; r++) {
			String routeId = "route-h-" + r;
			String lineId = "line-h-" + r;
			routes.add(new TransitRoute(routeId, lineId, "H" + r, "Line H" + r, "Terminal", "Asia/Seoul"));

			int tripIndex = 0;
			int curTime = 21_600; // 06:00
			while (curTime <= 32_400) { // 09:00
				String tripId = lineId + "-trip-" + tripIndex++;
				trips.add(new TransitTrip(tripId, routeId, "daily", "Terminal", "0", "LOCAL", 0));
				for (int c = 0; c < 6; c++) {
					String stationId = stationId(r, c);
					int time = curTime + c * 120;
					stopTimes.add(new TransitStopTime(tripId, c + 1, stationId, lineId, time, time, 0, 0));
				}
				curTime += (300 + rng.nextInt(601)); // 5~15 minutes
			}
		}

		// 6 vertical lines (c: 0..5)
		for (int c = 0; c < 6; c++) {
			String routeId = "route-v-" + c;
			String lineId = "line-v-" + c;
			routes.add(new TransitRoute(routeId, lineId, "V" + c, "Line V" + c, "Terminal", "Asia/Seoul"));

			int tripIndex = 0;
			int curTime = 21_600;
			while (curTime <= 32_400) {
				String tripId = lineId + "-trip-" + tripIndex++;
				trips.add(new TransitTrip(tripId, routeId, "daily", "Terminal", "0", "LOCAL", 0));
				for (int r = 0; r < 6; r++) {
					String stationId = stationId(r, c);
					int time = curTime + r * 120;
					stopTimes.add(new TransitStopTime(tripId, r + 1, stationId, lineId, time, time, 0, 0));
				}
				curTime += (300 + rng.nextInt(601));
			}
		}

		// Pathway nodes, edges, transfer rules, evidences
		List<PathwayNode> nodes = new ArrayList<>();
		List<PathwayEdge> edges = new ArrayList<>();
		List<TransferRule> transferRules = new ArrayList<>();
		List<RouteEdgeEvidence> evidences = new ArrayList<>();

		for (int r = 0; r < 6; r++) {
			for (int c = 0; c < 6; c++) {
				String stId = stationId(r, c);
				String lineH = "line-h-" + r;
				String lineV = "line-v-" + c;

				String entryNode = "entry-" + r + "-" + c;
				String exitNode = "exit-" + r + "-" + c;
				String platH = "plat-h-" + r + "-" + c;
				String platV = "plat-v-" + r + "-" + c;

				nodes.add(new PathwayNode(entryNode, stId, null, "ENTRANCE"));
				nodes.add(new PathwayNode(exitNode, stId, null, "EXIT"));
				nodes.add(new PathwayNode(platH, stId, lineH, "PLATFORM"));
				nodes.add(new PathwayNode(platV, stId, lineV, "PLATFORM"));

				// Entry edges
				String entryH = "entry-h-edge-" + r + "-" + c;
				String entryV = "entry-v-edge-" + r + "-" + c;
				edges.add(new PathwayEdge(entryH, entryNode, platH, 180, 100, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
				edges.add(new PathwayEdge(entryV, entryNode, platV, 180, 100, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
				evidences.add(new RouteEdgeEvidence("ev-entry-h-" + r + "-" + c, stId, lineH, entryH, "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));
				evidences.add(new RouteEdgeEvidence("ev-entry-v-" + r + "-" + c, stId, lineV, entryV, "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));

				// Exit edges
				String exitH = "exit-h-edge-" + r + "-" + c;
				String exitV = "exit-v-edge-" + r + "-" + c;
				edges.add(new PathwayEdge(exitH, platH, exitNode, 120, 80, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
				edges.add(new PathwayEdge(exitV, platV, exitNode, 120, 80, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
				evidences.add(new RouteEdgeEvidence("ev-exit-h-" + r + "-" + c, stId, lineH, exitH, "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));
				evidences.add(new RouteEdgeEvidence("ev-exit-v-" + r + "-" + c, stId, lineV, exitV, "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));

				// Transfer between H and V
				int walkSec = 60 + rng.nextInt(241); // 60~300초
				int walkMeters = 50 + rng.nextInt(351); // 50~400m
				boolean hasStairs = rng.nextInt(3) == 0; // 일부 계단

				String transHV = "trans-hv-edge-" + r + "-" + c;
				String transVH = "trans-vh-edge-" + r + "-" + c;
				edges.add(new PathwayEdge(transHV, platH, platV, walkSec, walkMeters, hasStairs, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));
				edges.add(new PathwayEdge(transVH, platV, platH, walkSec, walkMeters, hasStairs, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED"));

				transferRules.add(new TransferRule(
					"rule-hv-" + r + "-" + c, stId, lineH, stId, lineV, "IN_STATION", walkSec, transHV, transHV, "VERIFIED"));
				transferRules.add(new TransferRule(
					"rule-vh-" + r + "-" + c, stId, lineV, stId, lineH, "IN_STATION", walkSec, transVH, transVH, "VERIFIED"));

				evidences.add(new RouteEdgeEvidence("ev-hv-" + r + "-" + c, stId, lineV, transHV, "TRANSFER", "OFFICIAL_SOURCE", "VERIFIED", true, null));
				evidences.add(new RouteEdgeEvidence("ev-vh-" + r + "-" + c, stId, lineH, transVH, "TRANSFER", "OFFICIAL_SOURCE", "VERIFIED", true, null));
			}
		}

		var accessData = new RouteAccessData(nodes, edges, transferRules, evidences);
		return new RouteTimetable(
			List.of(calendar),
			List.of(),
			routes,
			trips,
			stopTimes,
			List.of(),
			List.of(),
			null,
			accessData);
	}

	private static String stationId(int r, int c) {
		return "station-" + r + "-" + c;
	}

	static Instant instantAt(long secondsOfDay) {
		return instantAt(SERVICE_DATE, secondsOfDay);
	}

	static Instant instantAt(LocalDate date, long secondsOfDay) {
		return date.atStartOfDay().plusSeconds(secondsOfDay)
			.atOffset(ZoneOffset.ofHours(9)).toInstant();
	}

	static JourneyProfileResourcePolicy testPolicy() {
		return new JourneyProfileResourcePolicy(
			new JourneyProfileResourcePolicy.Identity("golden-profile", "1.0.0", "b".repeat(64)),
			Duration.ofHours(4), 3, 2_000_000L, 1024, 1024, 128,
			Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
			1, 1, 1, 1, 4);
	}
}
