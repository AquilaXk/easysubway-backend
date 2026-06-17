package com.easysubway.usage.domain;

import java.time.LocalDate;
import java.util.List;

public record UserActivityDashboardSummary(
	long totalActiveUsers,
	List<DailyUserActivity> dailyActivities
) {

	public UserActivityDashboardSummary {
		if (totalActiveUsers < 0) {
			throw new InvalidUserActivityException("전체 활성 사용자 수는 0 이상이어야 합니다.");
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

	public record DailyUserActivity(LocalDate date, long activeUserCount) {

		public DailyUserActivity {
			if (date == null) {
				throw new InvalidUserActivityException("사용자 활동 집계 날짜가 필요합니다.");
			}
			if (activeUserCount < 0) {
				throw new InvalidUserActivityException("일별 활성 사용자 수는 0 이상이어야 합니다.");
			}
		}
	}
}
