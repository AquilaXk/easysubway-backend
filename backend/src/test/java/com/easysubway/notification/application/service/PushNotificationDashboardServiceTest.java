package com.easysubway.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.adapter.out.persistence.InMemoryPushNotificationOutboxRepository;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 알림 현황 서비스")
class PushNotificationDashboardServiceTest {

	@Test
	@DisplayName("전체 outbox를 상태별로 집계한다")
	void summarizePushNotificationOutboxByStatus() {
		var repository = new InMemoryPushNotificationOutboxRepository();
		repository.savePushNotification(notification("push-1", "anonymous-user-1", PushNotificationStatus.PENDING));
		repository.savePushNotification(notification("push-2", "anonymous-user-1", PushNotificationStatus.SENT));
		repository.savePushNotification(notification("push-3", "anonymous-user-2", PushNotificationStatus.FAILED));
		var service = new PushNotificationDashboardService(repository);

		var summary = service.summarizePushNotifications();

		assertThat(summary.totalCount()).isEqualTo(3);
		assertThat(summary.pendingCount()).isEqualTo(1);
		assertThat(summary.sentCount()).isEqualTo(1);
		assertThat(summary.failedCount()).isEqualTo(1);
	}

	private PushNotification notification(
		String notificationId,
		String userId,
		PushNotificationStatus status
	) {
		return new PushNotification(
			notificationId,
			userId,
			DevicePlatform.ANDROID,
			"device-token-" + notificationId,
			PushNotificationType.REPORT_STATUS,
			"알림 제목 " + notificationId,
			"알림 본문 " + notificationId,
			status,
			LocalDateTime.of(2026, 6, 17, 9, 0)
		);
	}
}
