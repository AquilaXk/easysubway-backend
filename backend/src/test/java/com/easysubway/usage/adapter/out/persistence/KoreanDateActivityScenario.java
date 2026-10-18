package com.easysubway.usage.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.usage.application.port.out.RecordApiTrafficPort;
import com.easysubway.usage.application.port.out.RecordUserActivityPort;
import com.easysubway.usage.application.port.out.SummarizeUserActivityPort;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 저장소 구현이 달라도 UTC로 저장된 활동을 한국 날짜로 묶는다는 같은 약속을 지키는지 확인하는 공용 시나리오.
 *
 * <p>한국 6월 17일은 UTC 6월 16일 15:00부터 6월 17일 14:59:59까지다. 경계 양쪽 1초씩을 심는다.
 */
final class KoreanDateActivityScenario {

	static final LocalDate TODAY = LocalDate.of(2026, 6, 17);
	static final int DAYS = 2;

	private KoreanDateActivityScenario() {
	}

	static <T extends RecordUserActivityPort & RecordApiTrafficPort & SummarizeUserActivityPort>
		UserActivityDashboardSummary seedAndSummarize(T repository) {
		// 한국 6월 16일 23:59:59.
		repository.recordUserActivity("user-kst-16", LocalDateTime.of(2026, 6, 16, 14, 59, 59));
		repository.recordApiTraffic(200, 100, LocalDateTime.of(2026, 6, 16, 14, 59, 59));
		// 한국 6월 17일 00:00:00, 새벽 01:30, 23:59:59. UTC로는 모두 6월 16일 또는 17일 낮이다.
		repository.recordUserActivity("user-kst-17-a", LocalDateTime.of(2026, 6, 16, 15, 0));
		repository.recordUserActivity("user-kst-17-b", LocalDateTime.of(2026, 6, 16, 16, 30));
		repository.recordUserActivity("user-kst-17-c", LocalDateTime.of(2026, 6, 17, 14, 59, 59));
		repository.recordApiTraffic(500, 300, LocalDateTime.of(2026, 6, 16, 16, 30));
		repository.recordApiTraffic(200, 50, LocalDateTime.of(2026, 6, 17, 14, 59, 59));
		// 한국 6월 18일 00:00:00은 기준일 6월 17일 집계에 들어가지 않는다.
		repository.recordUserActivity("user-kst-18", LocalDateTime.of(2026, 6, 17, 15, 0));
		repository.recordApiTraffic(200, 700, LocalDateTime.of(2026, 6, 17, 15, 0));
		// 한국 6월 15일 23:59:59는 두 날짜 창 밖이다.
		repository.recordUserActivity("user-kst-15", LocalDateTime.of(2026, 6, 15, 14, 59, 59));
		repository.recordApiTraffic(200, 900, LocalDateTime.of(2026, 6, 15, 14, 59, 59));

		return repository.summarizeUserActivity(TODAY, DAYS);
	}

	static void assertGroupedByKoreanDate(UserActivityDashboardSummary summary) {
		assertThat(summary.totalActiveUsers()).isEqualTo(4);
		assertThat(summary.totalApiRequests()).isEqualTo(3);
		assertThat(summary.totalApiErrors()).isEqualTo(1);
		assertThat(summary.dailyActivities())
			.extracting(row -> row.date() + ":" + row.activeUserCount() + ":" + row.apiRequestCount()
				+ ":" + row.apiErrorCount() + ":" + row.apiResponseMillis())
			.containsExactly("2026-06-17:3:2:1:350", "2026-06-16:1:1:0:100");
	}
}
