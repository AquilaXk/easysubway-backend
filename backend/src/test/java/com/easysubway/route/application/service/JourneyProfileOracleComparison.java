package com.easysubway.route.application.service;

import com.easysubway.journey.application.JourneyProfileRaptorPort;
import java.util.List;
import java.util.Objects;

/**
 * 응답에서 관측 가능한 시간표 trace만 비교한다. 물리 edge ID, stop position,
 * 방향 종착역과 노선 identity는 양쪽 모델의 공통 필드가 아니므로 이 비교로 입증하지 않는다.
 * 전체 frontier parity, canonical tie-selected ID, wire parity는 이 비교로 입증하지 않는다.
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

	/**
	 * 독립 oracle의 여섯 목적별 최적 경로가 실제 trace에 남아 있는지 센다.
	 * 동률 경로의 canonical ID 선택, 전체 frontier 또는 wire parity는 별도로 검증한다.
	 */
	static int requiredObjectiveLoss(
		List<JourneyProfileExactOracle.Candidate> expected, List<JourneyProfileRaptorPort.Itinerary> actual
	) {
		expected = List.copyOf(Objects.requireNonNull(expected, "expected"));
		actual = List.copyOf(Objects.requireNonNull(actual, "actual"));
		if (expected.isEmpty()) throw new IllegalArgumentException("expected candidates must not be empty");
		int loss = 0;
		for (Objective objective : Objective.values()) {
			JourneyProfileExactOracle.Candidate best = expected.getFirst();
			for (JourneyProfileExactOracle.Candidate candidate : expected) {
				if (objective.compare(candidate, best) > 0) best = candidate;
			}
			boolean preserved = false;
			for (JourneyProfileExactOracle.Candidate candidate : expected) {
				if (objective.compare(candidate, best) == 0 && actual.stream()
					.anyMatch(itinerary -> matchesObservableTimetableTrace(candidate, itinerary))) {
					preserved = true;
					break;
				}
			}
			if (!preserved) loss += 1;
		}
		return loss;
	}

	private enum Objective {
		EARLIEST_ARRIVAL {
			@Override int compare(JourneyProfileExactOracle.Candidate left, JourneyProfileExactOracle.Candidate right) {
				return right.arrivalAtDestination().compareTo(left.arrivalAtDestination());
			}
		},
		LATEST_READY {
			@Override int compare(JourneyProfileExactOracle.Candidate left, JourneyProfileExactOracle.Candidate right) {
				return left.readyAt().compareTo(right.readyAt());
			}
		},
		FEWEST_TRANSFERS {
			@Override int compare(JourneyProfileExactOracle.Candidate left, JourneyProfileExactOracle.Candidate right) {
				return Integer.compare(right.transfersUsed(), left.transfersUsed());
			}
		},
		LEAST_WALKING {
			@Override int compare(JourneyProfileExactOracle.Candidate left, JourneyProfileExactOracle.Candidate right) {
				int seconds = Long.compare(right.walkingSeconds(), left.walkingSeconds());
				return seconds != 0 ? seconds : Long.compare(right.walkingDistanceMeters(), left.walkingDistanceMeters());
			}
		},
		LEAST_ACCESSIBILITY_BURDEN {
			@Override int compare(JourneyProfileExactOracle.Candidate left, JourneyProfileExactOracle.Candidate right) {
				return Long.compare(right.accessibilityBurden(), left.accessibilityBurden());
			}
		},
		GREATEST_CONNECTION_SLACK {
			@Override int compare(JourneyProfileExactOracle.Candidate left, JourneyProfileExactOracle.Candidate right) {
				return compareSlack(left.minimumConnectionSlack(), right.minimumConnectionSlack());
			}
		};

		abstract int compare(JourneyProfileExactOracle.Candidate left, JourneyProfileExactOracle.Candidate right);
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

	private static int compareSlack(
		JourneyProfileExactOracle.ConnectionSlack left, JourneyProfileExactOracle.ConnectionSlack right
	) {
		if (left instanceof JourneyProfileExactOracle.ConnectionSlack.NoTransfer) {
			return right instanceof JourneyProfileExactOracle.ConnectionSlack.NoTransfer ? 0 : 1;
		}
		if (right instanceof JourneyProfileExactOracle.ConnectionSlack.NoTransfer) return -1;
		return Long.compare(((JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds) left).seconds(),
			((JourneyProfileExactOracle.ConnectionSlack.MinimumTransferSeconds) right).seconds());
	}
}
