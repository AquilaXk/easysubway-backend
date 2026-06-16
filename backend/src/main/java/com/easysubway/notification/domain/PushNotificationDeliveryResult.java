package com.easysubway.notification.domain;

import java.util.List;

public record PushNotificationDeliveryResult(
	String requestedUserId,
	int sentCount,
	int failedCount,
	List<PushNotification> notifications
) {

	public PushNotificationDeliveryResult {
		if (requestedUserId == null || requestedUserId.isBlank()) {
			throw new InvalidPushNotificationException("사용자 식별자가 필요합니다.");
		}
		if (sentCount < 0) {
			throw new InvalidPushNotificationException("발송 완료 알림 수는 0 이상이어야 합니다.");
		}
		if (failedCount < 0) {
			throw new InvalidPushNotificationException("발송 실패 알림 수는 0 이상이어야 합니다.");
		}
		if (notifications == null) {
			throw new InvalidPushNotificationException("알림 목록이 필요합니다.");
		}
		if (sentCount + failedCount != notifications.size()) {
			throw new InvalidPushNotificationException("처리 건수와 알림 목록 크기가 일치해야 합니다.");
		}
		requestedUserId = requestedUserId.trim();
		notifications = List.copyOf(notifications);
	}

	public int processedCount() {
		return sentCount + failedCount;
	}
}
