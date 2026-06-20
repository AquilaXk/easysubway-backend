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

	@Test
	@DisplayName("대기 중인 알림이 있는 사용자만 가장 오래된 대기 알림 순서로 조회한다")
	void loadPendingPushNotificationUserIdsReturnsUsersByOldestPendingNotification() {
		repository.savePushNotification(notification(
			"push-1",
			"anonymous-user-2",
			PushNotificationStatus.PENDING,
			LocalDateTime.of(2026, 6, 17, 10, 0)
		));
		repository.savePushNotification(notification(
			"push-2",
			"anonymous-user-1",
			PushNotificationStatus.PENDING,
			LocalDateTime.of(2026, 6, 17, 9, 0)
		));
		repository.savePushNotification(notification(
			"push-3",
			"anonymous-user-3",
			PushNotificationStatus.SENT,
			LocalDateTime.of(2026, 6, 17, 8, 0)
		));

		assertThat(repository.loadPendingPushNotificationUserIds())
			.containsExactly("anonymous-user-1", "anonymous-user-2");
	}

	@Test
	@DisplayName("대기 알림 선점은 pending 행만 처리 중으로 전환한다")
	void claimPendingPushNotificationUpdatesPendingOnly() {
		var pendingNotification = notification("push-1", "anonymous-user-1", PushNotificationStatus.PENDING);
		repository.savePushNotification(pendingNotification);

		assertThat(repository.claimPendingPushNotification(pendingNotification)).isTrue();
		assertThat(repository.claimPendingPushNotification(pendingNotification)).isFalse();
		assertThat(repository.loadPushNotifications("anonymous-user-1"))
			.extracting("notificationId", "status")
			.containsExactly(tuple("push-1", PushNotificationStatus.PROCESSING));
	}

	private static org.assertj.core.groups.Tuple tuple(Object... values) {
		return org.assertj.core.api.Assertions.tuple(values);
	}

	private PushNotification notification(String notificationId, String userId, PushNotificationStatus status) {
		return notification(notificationId, userId, status, LocalDateTime.of(2026, 6, 17, 10, 0));
	}

	private PushNotification notification(
		String notificationId,
		String userId,
		PushNotificationStatus status,
		LocalDateTime createdAt
	) {
		return new PushNotification(
			notificationId,
			userId,
			DevicePlatform.ANDROID,
			"device-token-" + notificationId,
			PushNotificationType.REPORT_STATUS,
			"신고 처리 알림",
			"제보한 내용이 확인되었습니다.",
			status,
			createdAt
		);
	}
}
