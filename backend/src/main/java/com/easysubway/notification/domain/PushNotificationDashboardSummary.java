package com.easysubway.notification.domain;

public record PushNotificationDashboardSummary(
	long totalCount,
	long pendingCount,
	long sentCount,
	long failedCount
) {

	public PushNotificationDashboardSummary {
		if (totalCount < 0 || pendingCount < 0 || sentCount < 0 || failedCount < 0) {
			throw new InvalidPushNotificationException("알림 집계 수는 0 이상이어야 합니다.");
		}
		if (totalCount != pendingCount + sentCount + failedCount) {
			throw new InvalidPushNotificationException("전체 알림 수와 상태별 알림 수가 일치하지 않습니다.");
		}
	}
}
