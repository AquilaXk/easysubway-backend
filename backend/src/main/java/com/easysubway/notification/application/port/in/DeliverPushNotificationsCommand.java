package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.InvalidPushNotificationException;

public record DeliverPushNotificationsCommand(String userId) {

	public DeliverPushNotificationsCommand {
		if (userId == null || userId.isBlank()) {
			throw new InvalidPushNotificationException("사용자 식별자가 필요합니다.");
		}
		userId = userId.trim();
	}
}
