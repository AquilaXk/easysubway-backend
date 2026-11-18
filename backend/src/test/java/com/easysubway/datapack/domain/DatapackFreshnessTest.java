package com.easysubway.datapack.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("데이터팩 freshness 경계")
class DatapackFreshnessTest {

	@Test
	@DisplayName("평가 시각이 만료 시각과 같으면 stale이다")
	void sameInstantIsStale() {
		LocalDateTime at = LocalDateTime.parse("2026-09-29T00:00:00");

		assertThat(DatapackFreshness.isStale(at, at)).isTrue();
	}

	@Test
	@DisplayName("평가 시각이 만료 시각 이전이면 fresh이다")
	void beforeExpiryIsFresh() {
		LocalDateTime expiresAt = LocalDateTime.parse("2026-09-29T00:00:00");

		assertThat(DatapackFreshness.isStale(expiresAt.minusSeconds(1), expiresAt)).isFalse();
	}

	@Test
	@DisplayName("평가 시각이 만료 시각 이후이면 stale이다")
	void afterExpiryIsStale() {
		LocalDateTime expiresAt = LocalDateTime.parse("2026-09-29T00:00:00");

		assertThat(DatapackFreshness.isStale(expiresAt.plusSeconds(1), expiresAt)).isTrue();
	}
}
