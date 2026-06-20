package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;

public interface SavePushNotificationOutboxPort {

	PushNotification savePushNotification(PushNotification notification);

	default PushNotification savePendingPushNotificationIfAbsent(PushNotification notification) {
		return savePushNotification(notification);
	}

	default boolean claimPendingPushNotification(PushNotification notification) {
		if (notification.status() != PushNotificationStatus.PENDING) {
			return false;
		}
		savePushNotification(notification.withStatus(PushNotificationStatus.PROCESSING));
		return true;
	}
}
