package com.easysubway.usage.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public record UserActivityDashboardSummary(
	long totalActiveUsers,
	long totalApiRequests,
	long totalApiErrors,
	List<DailyUserActivity> dailyActivities
) {

	public UserActivityDashboardSummary {
		if (totalActiveUsers < 0) {
			throw new InvalidUserActivityException("전체 활성 사용자 수는 0 이상이어야 합니다.");
		}
		if (totalApiRequests < 0) {
			throw new InvalidUserActivityException("전체 API 요청 수는 0 이상이어야 합니다.");
		}
		if (totalApiErrors < 0) {
			throw new InvalidUserActivityException("전체 API 오류 수는 0 이상이어야 합니다.");
		}
		if (totalApiErrors > totalApiRequests) {
			throw new InvalidUserActivityException("전체 API 오류 수는 전체 API 요청 수보다 클 수 없습니다.");
		}
		dailyActivities = List.copyOf(dailyActivities);
		long maxDailyCount = dailyActivities.stream()
			.mapToLong(DailyUserActivity::activeUserCount)
			.max()
			.orElse(0);
		if (totalActiveUsers < maxDailyCount) {
			throw new InvalidUserActivityException("전체 활성 사용자 수는 일별 활성 사용자 수보다 작을 수 없습니다.");
		}
	}

	public String apiErrorRatePercent() {
		return formatPercent(totalApiErrors, totalApiRequests);
	}

	public record DailyUserActivity(
		LocalDate date,
		long activeUserCount,
		long apiRequestCount,
		long apiErrorCount
	) {

		public DailyUserActivity {
			if (date == null) {
				throw new InvalidUserActivityException("사용자 활동 집계 날짜가 필요합니다.");
			}
			if (activeUserCount < 0) {
				throw new InvalidUserActivityException("일별 활성 사용자 수는 0 이상이어야 합니다.");
			}
			if (apiRequestCount < 0) {
				throw new InvalidUserActivityException("일별 API 요청 수는 0 이상이어야 합니다.");
			}
			if (apiErrorCount < 0) {
				throw new InvalidUserActivityException("일별 API 오류 수는 0 이상이어야 합니다.");
			}
			if (apiErrorCount > apiRequestCount) {
				throw new InvalidUserActivityException("일별 API 오류 수는 일별 API 요청 수보다 클 수 없습니다.");
			}
		}

		public String apiErrorRatePercent() {
			return formatPercent(apiErrorCount, apiRequestCount);
		}
	}

	private static String formatPercent(long numerator, long denominator) {
		if (denominator == 0) {
			return "0.0%";
		}
		double percentage = (double) numerator * 100 / denominator;
		return String.format(Locale.ROOT, "%.1f%%", percentage);
	}
}
