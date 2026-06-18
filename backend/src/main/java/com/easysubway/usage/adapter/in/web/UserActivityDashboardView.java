package com.easysubway.usage.adapter.in.web;

import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.util.List;

record UserActivityDashboardView(
	long totalActiveUsers,
	long totalApiRequests,
	long totalApiErrors,
	String apiErrorRatePercent,
	long averageApiResponseMillis,
	String averageApiResponseTimeLabel,
	List<DailyUserActivityRow> dailyActivityRows
) {

	static UserActivityDashboardView from(UserActivityDashboardSummary summary) {
		return new UserActivityDashboardView(
			summary.totalActiveUsers(),
			summary.totalApiRequests(),
			summary.totalApiErrors(),
			summary.apiErrorRatePercent(),
			summary.averageApiResponseMillis(),
			summary.averageApiResponseTimeLabel(),
			summary.dailyActivities()
				.stream()
				.map(row -> new DailyUserActivityRow(
					row.date().toString(),
					row.activeUserCount(),
					row.apiRequestCount(),
					row.apiErrorCount(),
					row.apiErrorRatePercent(),
					row.averageApiResponseMillis(),
					row.averageApiResponseTimeLabel()
				))
				.toList()
		);
	}

	record DailyUserActivityRow(
		String dateLabel,
		long activeUserCount,
		long apiRequestCount,
		long apiErrorCount,
		String apiErrorRatePercent,
		long averageApiResponseMillis,
		String averageApiResponseTimeLabel
	) {
	}
}
