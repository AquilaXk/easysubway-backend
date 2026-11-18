package com.easysubway.usage.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("사용자 활동 대시보드 서비스")
class UserActivityDashboardServiceTest {

	@Test
	@DisplayName("UTC로는 전날인 한국 새벽에도 한국 날짜를 집계 기준일로 쓴다")
	void usesKoreanDateAsSummaryBaseDateDuringKoreanEarlyMorning() {
		assertThat(requestedBaseDateAt("2026-10-17T16:00:00Z")).isEqualTo(LocalDate.of(2026, 10, 18));
	}

	@Test
	@DisplayName("한국 자정 직전 순간은 아직 전날 기준일이다")
	void staysOnThePreviousKoreanDateJustBeforeKoreanMidnight() {
		assertThat(requestedBaseDateAt("2026-10-17T14:59:59Z")).isEqualTo(LocalDate.of(2026, 10, 17));
	}

	private static LocalDate requestedBaseDateAt(String utcInstant) {
		List<LocalDate> requestedDates = new ArrayList<>();
		var service = new UserActivityDashboardService(
			(today, days) -> {
				requestedDates.add(today);
				return new UserActivityDashboardSummary(0, 0, 0, 0, List.of());
			},
			Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC)
		);

		service.summarizeUserActivity();

		return requestedDates.getFirst();
	}
}
