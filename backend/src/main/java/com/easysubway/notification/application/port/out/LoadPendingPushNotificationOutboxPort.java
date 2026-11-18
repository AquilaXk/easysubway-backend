package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.PushNotification;
import java.util.List;

public interface LoadPendingPushNotificationOutboxPort {

	List<PushNotification> loadPendingPushNotifications(String userId);

	List<String> loadPendingPushNotificationUserIds();
}
