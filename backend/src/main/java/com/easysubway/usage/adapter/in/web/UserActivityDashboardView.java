package com.easysubway.usage.adapter.in.web;

import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.util.List;

record UserActivityDashboardView(
	long totalActiveUsers,
	long totalApiRequests,
	long totalApiErrors,
	String apiErrorRatePercent,
	String apiErrorAlertLabel,
	String apiErrorAlertDescription,
	String apiErrorAlertClass,
	long averageApiResponseMillis,
	String averageApiResponseTimeLabel,
	List<DailyUserActivityRow> dailyActivityRows
) {

	private static final int API_ERROR_ALERT_THRESHOLD_PERCENT = 5;

	static UserActivityDashboardView from(UserActivityDashboardSummary summary) {
		boolean apiErrorAlert = isApiErrorAlert(summary);
		return new UserActivityDashboardView(
			summary.totalActiveUsers(),
			summary.totalApiRequests(),
			summary.totalApiErrors(),
			summary.apiErrorRatePercent(),
			apiErrorAlert ? "점검 필요" : "정상",
			apiErrorAlert ? "최근 7일 API 오류율이 5% 이상입니다." : "최근 7일 API 오류율이 기준치 미만입니다.",
			apiErrorAlert ? "warning" : "ok",
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

	private static boolean isApiErrorAlert(UserActivityDashboardSummary summary) {
		if (summary.totalApiRequests() == 0) {
			return false;
		}
		return summary.totalApiErrors() * 100 >= summary.totalApiRequests() * API_ERROR_ALERT_THRESHOLD_PERCENT;
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
