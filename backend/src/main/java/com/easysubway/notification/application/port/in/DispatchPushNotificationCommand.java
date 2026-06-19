package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.InvalidPushNotificationException;
import com.easysubway.notification.domain.PushNotificationType;

public record DispatchPushNotificationCommand(
	String userId,
	PushNotificationType type,
	String title,
	String body,
	String idempotencyKey
) {

	public DispatchPushNotificationCommand(
		String userId,
		PushNotificationType type,
		String title,
		String body
	) {
		this(userId, type, title, body, null);
	}

	public DispatchPushNotificationCommand {
		if (userId == null || userId.isBlank()) {
			throw new InvalidPushNotificationException("사용자 식별자가 필요합니다.");
		}
		if (type == null) {
			throw new InvalidPushNotificationException("알림 종류를 선택해야 합니다.");
		}
		if (title == null || title.isBlank()) {
			throw new InvalidPushNotificationException("알림 제목이 필요합니다.");
		}
		if (body == null || body.isBlank()) {
			throw new InvalidPushNotificationException("알림 본문이 필요합니다.");
		}
		userId = userId.trim();
		title = title.trim();
		body = body.trim();
		idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
			? null
			: idempotencyKey.trim();
	}
}
