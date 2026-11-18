package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.journey.application.JourneyRaptorQuery;
import com.easysubway.journey.application.JourneyRequest;
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
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyAccessProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyRideProjection;
import com.easysubway.route.application.service.RouteTimetableRaptorPlanner.JourneyStopProjection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#429 Journey V3 ride leg stops & servicePattern")
class RouteTimetableRaptorPlannerRideStopsTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 6);
	static final ServiceCalendar DAILY = new ServiceCalendar(
		"daily", true, true, true, true, true, true, true, SERVICE_DATE, SERVICE_DATE.plusYears(1), "Asia/Seoul");

	@Test
	@DisplayName("(1) LOCAL 3개 구간 탑승 시 중간 정차역 포함 4개 역과 계획 시각이 제공된다")
	void localRideDeliversAllStopsWithCorrectTimes() {
		// A -> B -> C -> D
		List<TransitRoute> routes = List.of(route("r1", "l1"));
		List<TransitTrip> trips = List.of(trip("t1", "r1", "LOCAL"));
		List<TransitStopTime> stops = List.of(
			stop("t1", 1, "station-a", "l1", 25200, 25200, 0, 0), // 07:00
			stop("t1", 2, "station-b", "l1", 25380, 25440, 0, 0), // 07:03 arr, 07:04 dep
			stop("t1", 3, "station-c", "l1", 25620, 25680, 0, 0), // 07:07 arr, 07:08 dep
			stop("t1", 4, "station-d", "l1", 25860, 25860, 0, 0)  // 07:11
		);
		RouteTimetable timetable = new RouteTimetable(
			List.of(DAILY), List.of(), routes, trips, stops, List.of(), List.of(), null,
			accessData(List.of("station-a:l1", "station-b:l1", "station-c:l1", "station-d:l1"), List.of())
		);

		var planner = new RouteTimetableRaptorPlanner();
		var query = query("station-a", "station-d", Instant.parse("2026-07-05T21:50:00Z"), 0); // 06:50 KST
		var plan = planner.journeyItineraries(query, timetable);

		assertThat(plan.itineraries()).isNotEmpty();
		var best = plan.itineraries().getFirst();
		var ride = best.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.findFirst().orElseThrow();

		assertThat(ride.servicePattern()).isEqualTo("LOCAL");
		assertThat(ride.stops()).hasSize(4);

		JourneyStopProjection stopA = ride.stops().get(0);
		assertThat(stopA.stationId()).isEqualTo("station-a");
		assertThat(stopA.plannedArrivalTime()).isNull();
		assertThat(stopA.plannedDepartureTime()).isEqualTo(Instant.parse("2026-07-05T22:00:00Z")); // 07:00 KST
		assertThat(stopA.realtimeArrivalTime()).isNull();
		assertThat(stopA.realtimeDepartureTime()).isNull();

		JourneyStopProjection stopB = ride.stops().get(1);
		assertThat(stopB.stationId()).isEqualTo("station-b");
		assertThat(stopB.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-05T22:03:00Z")); // 07:03 KST
		assertThat(stopB.plannedDepartureTime()).isEqualTo(Instant.parse("2026-07-05T22:04:00Z")); // 07:04 KST

		JourneyStopProjection stopC = ride.stops().get(2);
		assertThat(stopC.stationId()).isEqualTo("station-c");
		assertThat(stopC.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-05T22:07:00Z")); // 07:07 KST
		assertThat(stopC.plannedDepartureTime()).isEqualTo(Instant.parse("2026-07-05T22:08:00Z")); // 07:08 KST

		JourneyStopProjection stopD = ride.stops().get(3);
		assertThat(stopD.stationId()).isEqualTo("station-d");
		assertThat(stopD.plannedArrivalTime()).isEqualTo(Instant.parse("2026-07-05T22:11:00Z")); // 07:11 KST
		assertThat(stopD.plannedDepartureTime()).isNull();
	}

	@Test
	@DisplayName("(2) EXPRESS 운행 시 통과역 2곳은 stops 목록에서 제외된다")
	void expressRideOmitsPassedThroughStations() {
		// A -> B(통과) -> C(통과) -> D(정차) -> E(정차)
		List<TransitRoute> routes = List.of(route("r1", "l1"));
		List<TransitTrip> trips = List.of(trip("t1", "r1", "EXPRESS"));
		List<TransitStopTime> stops = List.of(
			stop("t1", 1, "station-a", "l1", 25200, 25200, 0, 0),
			stop("t1", 2, "station-b", "l1", 25380, 25380, 1, 1), // pickup=1, dropoff=1 (통과)
			stop("t1", 3, "station-c", "l1", 25560, 25560, 1, 1), // pickup=1, dropoff=1 (통과)
			stop("t1", 4, "station-d", "l1", 25740, 25800, 0, 0),
			stop("t1", 5, "station-e", "l1", 25980, 25980, 0, 0)
		);
		RouteTimetable timetable = new RouteTimetable(
			List.of(DAILY), List.of(), routes, trips, stops, List.of(), List.of(), null,
			accessData(List.of("station-a:l1", "station-b:l1", "station-c:l1", "station-d:l1", "station-e:l1"), List.of())
		);

		var planner = new RouteTimetableRaptorPlanner();
		var query = query("station-a", "station-e", Instant.parse("2026-07-05T21:50:00Z"), 0);
		var plan = planner.journeyItineraries(query, timetable);

		assertThat(plan.itineraries()).isNotEmpty();
		var best = plan.itineraries().getFirst();
		var ride = best.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.findFirst().orElseThrow();

		assertThat(ride.stops().stream().map(JourneyStopProjection::stationId).toList())
			.containsExactly("station-a", "station-d", "station-e");
	}

	@Test
	@DisplayName("(3) EXPRESS 운행 유형이 servicePattern 에 정확히 전달된다")
	void expressServicePatternDelivered() {
		List<TransitRoute> routes = List.of(route("r1", "l1"));
		List<TransitTrip> trips = List.of(trip("t1", "r1", "EXPRESS"));
		List<TransitStopTime> stops = List.of(
			stop("t1", 1, "station-a", "l1", 25200, 25200, 0, 0),
			stop("t1", 2, "station-b", "l1", 25500, 25500, 0, 0)
		);
		RouteTimetable timetable = new RouteTimetable(
			List.of(DAILY), List.of(), routes, trips, stops, List.of(), List.of(), null,
			accessData(List.of("station-a:l1", "station-b:l1"), List.of())
		);

		var planner = new RouteTimetableRaptorPlanner();
		var query = query("station-a", "station-b", Instant.parse("2026-07-05T21:50:00Z"), 0);
		var plan = planner.journeyItineraries(query, timetable);

		assertThat(plan.itineraries()).isNotEmpty();
		var ride = plan.itineraries().getFirst().legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.findFirst().orElseThrow();

		assertThat(ride.servicePattern()).isEqualTo("EXPRESS");
	}

	@Test
	@DisplayName("(4) 환승 여정에서 각 구간의 정차역 목록이 환승역을 통해 연결된다")
	void transferJourneyConnectsStopsAcrossLegs() {
		// Line 1: station-a -> station-a2 -> station-b
		// Line 2: station-b -> station-b2 -> station-c
		List<TransitRoute> routes = List.of(
			route("r1", "l1"),
			route("r2", "l2")
		);
		List<TransitTrip> trips = List.of(
			trip("t1", "r1", "LOCAL"),
			trip("t2", "r2", "LOCAL")
		);
		List<TransitStopTime> stops = List.of(
			stop("t1", 1, "station-a", "l1", 25200, 25200, 0, 0), // 07:00
			stop("t1", 2, "station-a2", "l1", 25350, 25350, 0, 0), // 07:02:30
			stop("t1", 3, "station-b", "l1", 25500, 25500, 0, 0), // 07:05
			stop("t2", 1, "station-b", "l2", 26100, 26100, 0, 0), // 07:15
			stop("t2", 2, "station-b2", "l2", 26250, 26250, 0, 0), // 07:17:30
			stop("t2", 3, "station-c", "l2", 26400, 26400, 0, 0)  // 07:20
		);
		RouteTimetable timetable = new RouteTimetable(
			List.of(DAILY), List.of(), routes, trips, stops, List.of(), List.of(), null,
			accessData(
				List.of("station-a:l1", "station-a2:l1", "station-b:l1", "station-b:l2", "station-b2:l2", "station-c:l2"),
				List.of("station-b:l1:l2")
			)
		);

		var planner = new RouteTimetableRaptorPlanner();
		var query = query("station-a", "station-c", Instant.parse("2026-07-05T21:50:00Z"), 1);
		var plan = planner.journeyItineraries(query, timetable);

		assertThat(plan.itineraries()).isNotEmpty();
		var itinerary = plan.itineraries().getFirst();
		List<JourneyRideProjection> rides = itinerary.legs().stream()
			.filter(JourneyRideProjection.class::isInstance)
			.map(JourneyRideProjection.class::cast)
			.toList();
		List<JourneyAccessProjection> transfers = itinerary.legs().stream()
			.filter(JourneyAccessProjection.class::isInstance)
			.map(JourneyAccessProjection.class::cast)
			.filter(a -> a.kind() == RouteTimetableRaptorPlanner.JourneyAccessKind.TRANSFER)
			.toList();

		assertThat(rides).hasSize(2);
		assertThat(transfers).hasSize(1);

		JourneyRideProjection leg1 = rides.get(0);
		JourneyAccessProjection transfer = transfers.get(0);
		JourneyRideProjection leg2 = rides.get(1);

		assertThat(leg1.stops().stream().map(JourneyStopProjection::stationId).toList())
			.containsExactly("station-a", "station-a2", "station-b");
		assertThat(leg1.stops().getLast().stationId()).isEqualTo("station-b");
		assertThat(transfer.fromStationId()).isEqualTo("station-b");
		assertThat(transfer.toStationId()).isEqualTo("station-b");
		assertThat(leg2.stops().getFirst().stationId()).isEqualTo("station-b");
		assertThat(leg2.stops().stream().map(JourneyStopProjection::stationId).toList())
			.containsExactly("station-b", "station-b2", "station-c");
	}

	static JourneyRaptorQuery query(String origin, String destination, Instant departure, int maxTransfers) {
		return new JourneyRaptorQuery(
			"01ARZ3NDEKTSV4RRFFQ69G5FAV",
			origin,
			destination,
			new JourneyRaptorQuery.DepartAt(departure),
			JourneyRequest.TimePolicy.TIMETABLE_REQUIRED,
			JourneyRequest.WalkingPace.STANDARD,
			JourneyRequest.MobilityProfile.STANDARD,
			JourneyRequest.ConstraintMode.NONE,
			maxTransfers,
			1,
			() -> false
		);
	}

	static TransitRoute route(String id, String lineId) {
		return new TransitRoute(id, lineId, id, id, id, "Asia/Seoul");
	}

	static TransitTrip trip(String id, String routeId, String servicePattern) {
		return new TransitTrip(id, routeId, "daily", id, "0", servicePattern, 0);
	}

	static TransitStopTime stop(
		String tripId, int sequence, String stationId, String lineId,
		int arrivalSeconds, int departureSeconds, int pickupType, int dropOffType
	) {
		return new TransitStopTime(
			tripId, sequence, stationId, lineId, arrivalSeconds, departureSeconds, pickupType, dropOffType);
	}

	static RouteAccessData accessData(List<String> stationLines, List<String> transferSpecs) {
		List<PathwayNode> nodes = new ArrayList<>();
		List<PathwayEdge> edges = new ArrayList<>();
		List<RouteEdgeEvidence> evidence = new ArrayList<>();
		for (String stationLine : stationLines) {
			String[] parts = stationLine.split(":");
			String station = parts[0];
			String line = parts[1];
			String key = station + "-" + line;
			var entry = new PathwayEdge(key + "-entry", key + "-entrance", key + "-platform", 240, 180, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE");
			var exit = new PathwayEdge(key + "-exit", key + "-platform", key + "-exit", 180, 120, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE");
			edges.add(entry);
			edges.add(exit);
			nodes.add(new PathwayNode(entry.fromNodeId(), station, null, "ENTRANCE"));
			nodes.add(new PathwayNode(entry.toNodeId(), station, line, "PLATFORM"));
			nodes.add(new PathwayNode(exit.toNodeId(), station, null, "EXIT"));
			evidence.add(new RouteEdgeEvidence(key + "-entry-ev", station, line, entry.id(), "ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null));
			evidence.add(new RouteEdgeEvidence(key + "-exit-ev", station, line, exit.id(), "EXIT", "OFFICIAL_SOURCE", "VERIFIED", true, null));
		}
		List<TransferRule> transfers = new ArrayList<>();
		for (String transferSpec : transferSpecs) {
			String[] parts = transferSpec.split(":");
			String station = parts[0];
			String fromLine = parts[1];
			String toLine = parts[2];
			String key = station + "-" + fromLine + "-" + toLine;
			var transferEdge = new PathwayEdge(key + "-edge", station + "-" + fromLine + "-platform", station + "-" + toLine + "-platform", 120, 80, false, false, 100, "AVAILABLE", "OFFICIAL_SOURCE", "VERIFIED").withStairAccessState("STEP_FREE");
			edges.add(transferEdge);
			evidence.add(new RouteEdgeEvidence(key + "-transfer-ev", station, toLine, transferEdge.id(), "TRANSFER", "OFFICIAL_SOURCE", "VERIFIED", true, null));
			transfers.add(new TransferRule(key + "-rule", station, fromLine, station, toLine, "IN_STATION", 120, transferEdge.id(), transferEdge.id(), "VERIFIED"));
		}
		return new RouteAccessData(nodes, edges, transfers, evidence);
	}
}
