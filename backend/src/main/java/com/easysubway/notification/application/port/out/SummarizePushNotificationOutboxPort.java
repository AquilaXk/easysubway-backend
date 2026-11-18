package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.PushNotificationDashboardSummary;

public interface SummarizePushNotificationOutboxPort {

	PushNotificationDashboardSummary summarizePushNotificationOutbox();
}
