package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.PushNotificationDashboardSummary;

public interface PushNotificationDashboardUseCase {

	PushNotificationDashboardSummary summarizePushNotifications();
}
