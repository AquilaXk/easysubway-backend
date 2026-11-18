package com.easysubway.notification.application.service;

import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.application.port.out.SummarizePushNotificationOutboxPort;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import org.springframework.stereotype.Service;

@Service
public class PushNotificationDashboardService implements PushNotificationDashboardUseCase {

	private final SummarizePushNotificationOutboxPort summarizePushNotificationOutboxPort;

	public PushNotificationDashboardService(SummarizePushNotificationOutboxPort summarizePushNotificationOutboxPort) {
		this.summarizePushNotificationOutboxPort = summarizePushNotificationOutboxPort;
	}

	@Override
	public PushNotificationDashboardSummary summarizePushNotifications() {
		return summarizePushNotificationOutboxPort.summarizePushNotificationOutbox();
	}
}
