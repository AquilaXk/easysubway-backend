package com.easysubway.route.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Route V2 임시 상태 만료 정책")
class RouteV2EphemeralStateServiceTest {

	private static final Instant CREATED_AT = Instant.parse("2026-07-16T09:00:00Z");

	@Test
	@DisplayName("도착이 빠르면 생성 후 최소 30분을 보존한다")
	void keepsStateForAtLeastThirtyMinutes() {
		assertThat(RouteV2EphemeralStateService.expiresAt(CREATED_AT, CREATED_AT.minusSeconds(60)))
			.isEqualTo(CREATED_AT.plusSeconds(30 * 60));
	}

	@Test
	@DisplayName("일반 경로는 도착 후 30분에 만료한다")
	void expiresThirtyMinutesAfterArrival() {
		assertThat(RouteV2EphemeralStateService.expiresAt(CREATED_AT, CREATED_AT.plusSeconds(2 * 60 * 60)))
			.isEqualTo(CREATED_AT.plusSeconds(150 * 60));
	}

	@Test
	@DisplayName("장거리 경로도 생성 후 6시간을 넘기지 않는다")
	void capsStateAtSixHours() {
		assertThat(RouteV2EphemeralStateService.expiresAt(CREATED_AT, CREATED_AT.plusSeconds(8 * 60 * 60)))
			.isEqualTo(CREATED_AT.plusSeconds(6 * 60 * 60));
	}
}
