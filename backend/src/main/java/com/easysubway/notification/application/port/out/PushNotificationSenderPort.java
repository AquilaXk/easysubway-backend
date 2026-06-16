package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationSendResult;

public interface PushNotificationSenderPort {

	PushNotificationSendResult send(PushNotification notification);
}
