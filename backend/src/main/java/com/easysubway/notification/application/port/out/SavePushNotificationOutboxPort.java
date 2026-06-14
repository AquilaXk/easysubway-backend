package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.PushNotification;

public interface SavePushNotificationOutboxPort {

	PushNotification savePushNotification(PushNotification notification);
}
