package com.easysubway.usage.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public record UserActivityDashboardSummary(
	long totalActiveUsers,
	long totalApiRequests,
	long totalApiErrors,
	long totalApiResponseMillis,
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
		if (totalApiResponseMillis < 0) {
			throw new InvalidUserActivityException("전체 API 응답 시간은 0 이상이어야 합니다.");
		}
		if (totalApiRequests == 0 && totalApiResponseMillis > 0) {
			throw new InvalidUserActivityException("전체 API 요청이 없으면 응답 시간을 집계할 수 없습니다.");
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

	public long averageApiResponseMillis() {
		return averageMillis(totalApiResponseMillis, totalApiRequests);
	}

	public String averageApiResponseTimeLabel() {
		return formatMillis(averageApiResponseMillis());
	}

	public record DailyUserActivity(
		LocalDate date,
		long activeUserCount,
		long apiRequestCount,
		long apiErrorCount,
		long apiResponseMillis
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
			if (apiResponseMillis < 0) {
				throw new InvalidUserActivityException("일별 API 응답 시간은 0 이상이어야 합니다.");
			}
			if (apiRequestCount == 0 && apiResponseMillis > 0) {
				throw new InvalidUserActivityException("일별 API 요청이 없으면 응답 시간을 집계할 수 없습니다.");
			}
		}

		public String apiErrorRatePercent() {
			return formatPercent(apiErrorCount, apiRequestCount);
		}

		public long averageApiResponseMillis() {
			return averageMillis(apiResponseMillis, apiRequestCount);
		}

		public String averageApiResponseTimeLabel() {
			return formatMillis(averageApiResponseMillis());
		}
	}

	private static long averageMillis(long totalMillis, long count) {
		if (count == 0) {
			return 0;
		}
		return totalMillis / count;
	}

	private static String formatMillis(long millis) {
		return millis + "ms";
	}

	private static String formatPercent(long numerator, long denominator) {
		if (denominator == 0) {
			return "0.0%";
		}
		double percentage = (double) numerator * 100 / denominator;
		return String.format(Locale.ROOT, "%.1f%%", percentage);
	}
}
