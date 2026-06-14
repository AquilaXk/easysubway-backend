package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.PushNotification;
import java.util.List;

public interface LoadPushNotificationOutboxPort {

	List<PushNotification> loadPushNotifications(String userId);
}
