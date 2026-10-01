package com.easysubway.journey.application;

import java.time.Instant;
import java.util.List;

/**
 * 테스트 전용 여정 후보 생성기. 운임이 본질이 아닌 테스트에서만 공식 운임을 명시적으로 UNAVAILABLE로 채운다.
 * 운영 코드에는 운임 기본값 생성자가 없다.
 */
public final class TestJourneyCandidates {

	private TestJourneyCandidates() {
	}

	public static JourneyCandidate unavailableFare(
		String journeyId,
		Instant plannedDepartureTime,
		Instant plannedArrivalTime,
		Instant realtimeDepartureTime,
		Instant realtimeArrivalTime,
		long durationSeconds,
		int transferCount,
		long walkingDistanceMeters,
		JourneyCandidate.TimeSource timeSource,
		JourneyCandidate.Accessibility accessibility,
		List<JourneyCandidate.Leg> legs
	) {
		return new JourneyCandidate(journeyId, plannedDepartureTime, plannedArrivalTime, realtimeDepartureTime,
			realtimeArrivalTime, durationSeconds, transferCount, walkingDistanceMeters, timeSource, accessibility,
			JourneyCandidate.Fare.unavailable(), legs);
	}
}
