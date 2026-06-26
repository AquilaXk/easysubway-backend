package com.easysubway.realtime.application;

import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import java.time.Instant;
import java.util.List;

final class FixtureRealtimeProvider implements RealtimeProvider {

	@Override
	public List<RealtimeArrival> arrivals(RealtimeQuery query) {
		return List.of(new RealtimeArrival(
			"4",
			"상록수",
			"당고개",
			"상행",
			"4123",
			180,
			"3분 후",
			"전역 출발",
			Instant.now().toString()
		));
	}

	@Override
	public List<RealtimeTrainPosition> trainPositions(RealtimeQuery query) {
		return List.of(new RealtimeTrainPosition(
			"4",
			"상록수",
			"4123",
			"운행중",
			"상행",
			"당고개",
			Instant.now().toString()
		));
	}
}
