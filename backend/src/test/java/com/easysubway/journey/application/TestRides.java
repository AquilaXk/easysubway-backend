package com.easysubway.journey.application;

import java.time.Instant;
import java.util.List;

/**
 * 테스트 전용 탑승 구간 생성기. 운행 유형과 정차역이 본질이 아닌 테스트에서만 LOCAL과 출발·도착 두 정차역을 명시적으로 채운다.
 * 운영 코드에는 이런 기본값 생성자가 없다.
 */
public final class TestRides {

	private TestRides() {
	}

	public static JourneyCandidate.Ride candidateRide(
		String lineId, String tripId, String directionStationId, String fromStationId, String toStationId,
		Instant plannedDeparture, Instant plannedArrival, Instant realtimeDeparture, Instant realtimeArrival
	) {
		return candidateRide(lineId, tripId, directionStationId, fromStationId, toStationId,
			plannedDeparture, plannedArrival, realtimeDeparture, realtimeArrival, List.of());
	}

	public static JourneyCandidate.Ride candidateRide(
		String lineId, String tripId, String directionStationId, String fromStationId, String toStationId,
		Instant plannedDeparture, Instant plannedArrival, Instant realtimeDeparture, Instant realtimeArrival,
		List<JourneyCandidate.AlightingCarDoor> alightingCarDoors
	) {
		return candidateRide(lineId, tripId, directionStationId, fromStationId, toStationId,
			plannedDeparture, plannedArrival, realtimeDeparture, realtimeArrival, alightingCarDoors,
			List.of(), List.of());
	}

	public static JourneyCandidate.Ride candidateRide(
		String lineId, String tripId, String directionStationId, String fromStationId, String toStationId,
		Instant plannedDeparture, Instant plannedArrival, Instant realtimeDeparture, Instant realtimeArrival,
		List<JourneyCandidate.AlightingCarDoor> alightingCarDoors,
		List<com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap> boardingGaps,
		List<com.easysubway.route.application.port.out.LoadRouteTimetablePort.PlatformGap> alightingGaps
	) {
		return new JourneyCandidate.Ride(lineId, tripId, directionStationId, fromStationId, toStationId, "LOCAL",
			plannedDeparture, plannedArrival, realtimeDeparture, realtimeArrival,
			List.of(
				new JourneyCandidate.Stop(fromStationId, null, plannedDeparture, null, realtimeDeparture),
				new JourneyCandidate.Stop(toStationId, plannedArrival, null, realtimeArrival, null)),
			alightingCarDoors, boardingGaps, alightingGaps);
	}

	public static JourneyProfileRaptorPort.RideLeg profileRide(
		String lineId, String tripId, String directionStationId, String fromStationId, String toStationId,
		Instant plannedDeparture, Instant plannedArrival, Instant realtimeDeparture, Instant realtimeArrival
	) {
		return new JourneyProfileRaptorPort.RideLeg(lineId, tripId, directionStationId, fromStationId, toStationId,
			"LOCAL", plannedDeparture, plannedArrival, realtimeDeparture, realtimeArrival,
			List.of(
				new JourneyCandidate.Stop(fromStationId, null, plannedDeparture, null, realtimeDeparture),
				new JourneyCandidate.Stop(toStationId, plannedArrival, null, realtimeArrival, null)));
	}
}
