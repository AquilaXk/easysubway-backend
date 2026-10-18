package com.easysubway.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("응답 시각 변환")
class UtcTimestampsTest {

	@Test
	@DisplayName("UTC 벽시계 시각은 같은 순간의 Instant가 된다")
	void convertsUtcWallClockToTheSameInstant() {
		assertThat(UtcTimestamps.toInstant(LocalDateTime.parse("2026-10-18T03:04:05.123456")))
			.isEqualTo(Instant.parse("2026-10-18T03:04:05.123456Z"));
	}

	@Test
	@DisplayName("값이 없으면 값이 없는 채로 둔다")
	void keepsMissingValueMissing() {
		assertThat(UtcTimestamps.toInstant(null)).isNull();
	}
}
