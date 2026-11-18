package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.PushNotificationDeliveryResult;

public interface PushNotificationDeliveryUseCase {

	PushNotificationDeliveryResult deliverPending(DeliverPushNotificationsCommand command);
}
