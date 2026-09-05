package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import java.util.List;
import java.util.Objects;

/**
 * 응답에서 관측 가능한 시간표 trace만 비교한다. 물리 edge ID, stop position,
 * 방향 종착역과 노선 identity는 양쪽 모델의 공통 필드가 아니므로 이 비교로 입증하지 않는다.
 * 전체 frontier parity나 필수 representative 보존 여부는 호출자가 별도로 검증해야 한다.
 */
final class JourneyProfileOracleComparison {

	private JourneyProfileOracleComparison() { }

	static boolean matchesObservableTimetableTrace(
		JourneyProfileExactOracle.Candidate expected, JourneyProfileRaptorPort.Itinerary actual
	) {
		var metrics = actual.metrics();
		if (!expected.readyAt().equals(actual.plannedReadyAt())
			|| !expected.arrivalAtDestination().equals(actual.plannedArrivalAtDestination())
			|| actual.realtimeReadyAt() != null || actual.realtimeArrivalAtDestination() != null
			|| expected.transfersUsed() != metrics.transfersUsed()
			|| expected.walkingSeconds() != metrics.accessMovementSeconds()
			|| expected.walkingDistanceMeters() != metrics.accessDistanceMeters()
			|| expected.accessibilityBurden() != metrics.accessibilityBurden()
			|| !sameSlack(expected.minimumConnectionSlack(), metrics.connectionSlack())
			|| expected.accesses().size() != expected.rides().size() + 1
			|| actual.legs().size() != expected.rides().size() + expected.accesses().size()) return false;
		for (int index = 0; index < expected.accesses().size(); index++) {
			var access = expected.accesses().get(index);
			if (!(actual.legs().get(index * 2) instanceof JourneyProfileRaptorPort.AccessLeg observed)
				|| !access.kind().name().equals(observed.kind().name())
				|| !access.fromStationId().equals(observed.fromStationId())
				|| !access.toStationId().equals(observed.toStationId())
				|| access.durationSeconds() != observed.durationSeconds()
				|| access.walkingDistanceMeters() != observed.distanceMeters()
				|| !access.usable() || !observed.verified() || !"VERIFIED".equals(observed.verificationStatus())) return false;
			if (index == expected.rides().size()) continue;
			var ride = expected.rides().get(index);
			if (!(actual.legs().get(index * 2 + 1) instanceof JourneyProfileRaptorPort.RideLeg observedRide)
				|| !ride.tripId().equals(observedRide.tripId())
				|| !ride.fromStationId().equals(observedRide.fromStationId())
				|| !ride.toStationId().equals(observedRide.toStationId())
				|| !ride.departureAt().equals(observedRide.plannedDepartureTime())
				|| !ride.arrivalAt().equals(observedRide.plannedArrivalTime())
				|| observedRide.realtimeDepartureTime() != null || observedRide.realtimeArrivalTime() != null) return false;
		}
		return true;
	}

	/**
	 * 공급된 observable multiset만 일대일 비교한다. bounded representative 정책, typed failure,
	 * 또는 journey 성공을 입증하지 않으며 빈 집합 일치도 그 예외가 아니다.
	 */
	static boolean matchesObservableTimetableFrontier(
		List<JourneyProfileExactOracle.Candidate> expected, List<JourneyProfileRaptorPort.Itinerary> actual
	) {
		expected = List.copyOf(Objects.requireNonNull(expected, "expected"));
		actual = List.copyOf(Objects.requireNonNull(actual, "actual"));
		if (expected.size() != actual.size()) return false;
		boolean[] consumed = new boolean[actual.size()];
		// 비교는 동일한 observable 필드의 동등성이다. 일치한 항목을 한 번씩 소비하면 충분하다.
		for (var candidate : expected) {
			boolean found = false;
			for (int index = 0; index < actual.size(); index += 1) {
				if (!consumed[index] && matchesObservableTimetableTrace(candidate, actual.get(index))) {
					consumed[index] = true;
					found = true;
					break;
				}
			}
			if (!found) return false;
		}
		return true;
	}

	private static boolean sameSlack(
		JourneyProfileExactOracle.ConnectionSlack expected, JourneyProfileRaptorPort.ConnectionSlack actual
	) {
		if (expected instanceof JourneyProfileExactOracle.ConnectionSlack.NoTransfer) {
			return actual instanceof JourneyProfileRaptorPort.NoTransfer;
		}
		return actual instanceof JourneyProfileRaptorPort.MinimumTransferSeconds observed
			&& ((JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds) expected).seconds() == observed.seconds();
	}
}
