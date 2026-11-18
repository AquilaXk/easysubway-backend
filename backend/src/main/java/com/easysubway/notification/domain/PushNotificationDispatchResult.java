package com.easysubway.notification.domain;

import java.util.List;

public record PushNotificationDispatchResult(
	String requestedUserId,
	PushNotificationType type,
	int createdCount,
	List<PushNotification> notifications
) {

	public PushNotificationDispatchResult {
		if (requestedUserId == null || requestedUserId.isBlank()) {
			throw new InvalidPushNotificationException("사용자 식별자가 필요합니다.");
		}
		if (type == null) {
			throw new InvalidPushNotificationException("알림 종류를 선택해야 합니다.");
		}
		if (createdCount < 0) {
			throw new InvalidPushNotificationException("생성된 알림 수는 0 이상이어야 합니다.");
		}
		if (notifications == null) {
			throw new InvalidPushNotificationException("알림 목록이 필요합니다.");
		}
		requestedUserId = requestedUserId.trim();
		notifications = List.copyOf(notifications);
	}
}
