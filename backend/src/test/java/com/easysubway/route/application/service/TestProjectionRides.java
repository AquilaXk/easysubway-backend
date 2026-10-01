package com.easysubway.route.application.service;

import java.time.Instant;
import java.util.List;

/** 테스트 전용 탑승 구간 투영 생성기. 운영 코드에는 운행 유형·정차역 기본값 생성자가 없다. */
final class TestProjectionRides {

	private TestProjectionRides() {
	}

	static RouteTimetableRaptorPlanner.JourneyRideProjection projectionRide(
		String lineId, String tripId, String directionStationId, String fromStationId, String toStationId,
		Instant plannedDeparture, Instant plannedArrival, Instant realtimeDeparture, Instant realtimeArrival
	) {
		return projectionRide(lineId, tripId, directionStationId, fromStationId, toStationId,
			plannedDeparture, plannedArrival, realtimeDeparture, realtimeArrival, List.of());
	}

	static RouteTimetableRaptorPlanner.JourneyRideProjection projectionRide(
		String lineId, String tripId, String directionStationId, String fromStationId, String toStationId,
		Instant plannedDeparture, Instant plannedArrival, Instant realtimeDeparture, Instant realtimeArrival,
		List<RouteTimetableRaptorPlanner.AlightingCarDoor> alightingCarDoors
	) {
		return new RouteTimetableRaptorPlanner.JourneyRideProjection(
			lineId, tripId, directionStationId, fromStationId, toStationId, "LOCAL",
			plannedDeparture, plannedArrival, realtimeDeparture, realtimeArrival,
			List.of(
				new RouteTimetableRaptorPlanner.JourneyStopProjection(
					fromStationId, null, plannedDeparture, null, realtimeDeparture),
				new RouteTimetableRaptorPlanner.JourneyStopProjection(
					toStationId, plannedArrival, null, realtimeArrival, null)),
			alightingCarDoors, List.of(), List.of());
	}
}
