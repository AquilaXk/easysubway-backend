package com.easysubway.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 푸시 알림 outbox 저장소")
class InMemoryPushNotificationOutboxRepositoryTest {

	private final InMemoryPushNotificationOutboxRepository repository = new InMemoryPushNotificationOutboxRepository();

	@Test
	@DisplayName("같은 알림 식별자는 사용자 버킷을 옮겨도 한 번만 저장한다")
	void savePushNotificationKeepsNotificationIdUniqueAcrossUsers() {
		repository.savePushNotification(notification("push-1", "anonymous-user-1", PushNotificationStatus.PENDING));
		var movedNotification = notification("push-1", "anonymous-user-2", PushNotificationStatus.SENT);

		repository.savePushNotification(movedNotification);

		assertThat(repository.loadPushNotifications("anonymous-user-1")).isEmpty();
		assertThat(repository.loadPushNotifications("anonymous-user-2")).containsExactly(movedNotification);
	}

	private PushNotification notification(String notificationId, String userId, PushNotificationStatus status) {
		return new PushNotification(
			notificationId,
			userId,
			DevicePlatform.ANDROID,
			"device-token-" + notificationId,
			PushNotificationType.REPORT_STATUS,
			"신고 처리 알림",
			"제보한 내용이 확인되었습니다.",
			status,
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);
	}
}
