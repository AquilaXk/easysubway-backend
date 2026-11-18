package com.easysubway.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.realtime.domain.RealtimeArrival;
import com.easysubway.realtime.domain.RealtimeStatus;
import com.easysubway.realtime.domain.RealtimeTrainPosition;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Realtime result immutable list boundary")
class RealtimeResultDefensiveCopyTest {

	@Test
	@DisplayName("arrival result snapshots constructor input and exposes an immutable list")
	void arrivalResultDefensivelyCopiesArrivals() {
		var input = new ArrayList<RealtimeArrival>();
		input.add(new RealtimeArrival(
			"seoul-4",
			"상록수",
			"사당",
			"상행",
			"T1001",
			120,
			"2분 후",
			"전역 출발",
			"2026-08-12T07:00:00Z"
		));
		var result = new RealtimeArrivalResult(
			RealtimeStatus.FRESH,
			null,
			null,
			"2026-08-12T07:00:00Z",
			"seoul-topis",
			input
		);

		input.clear();

		assertThat(result.arrivals()).hasSize(1);
		assertThatThrownBy(() -> result.arrivals().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("train-position result snapshots constructor input and exposes an immutable list")
	void trainPositionResultDefensivelyCopiesPositions() {
		var input = new ArrayList<RealtimeTrainPosition>();
		input.add(new RealtimeTrainPosition(
			"seoul-4",
			"상록수",
			"T1001",
			"운행 중",
			"상행",
			"사당",
			"2026-08-12T07:00:00Z"
		));
		var result = new RealtimeTrainPositionResult(
			RealtimeStatus.FRESH,
			null,
			null,
			"2026-08-12T07:00:00Z",
			"seoul-topis",
			"열차 위치는 GPS가 아니라 운행 정보 기준 위치입니다.",
			input
		);

		input.clear();

		assertThat(result.trainPositions()).hasSize(1);
		assertThatThrownBy(() -> result.trainPositions().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
