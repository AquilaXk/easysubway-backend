package com.easysubway.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.notification.adapter.out.persistence.InMemoryPushNotificationOutboxRepository;
import com.easysubway.notification.application.port.in.DeliverPushNotificationsCommand;
import com.easysubway.notification.application.port.out.PushNotificationSenderPort;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.InvalidPushNotificationException;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationSendResult;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 알림 발송 처리 서비스")
class PushNotificationDeliveryServiceTest {

	private final InMemoryPushNotificationOutboxRepository outboxRepository =
		new InMemoryPushNotificationOutboxRepository();
	private final RecordingPushNotificationSender sender = new RecordingPushNotificationSender();
	private final PushNotificationDeliveryService deliveryService = new PushNotificationDeliveryService(
		outboxRepository,
		outboxRepository,
		sender
	);

	@Test
	@DisplayName("대기 중인 알림은 sender 성공 결과에 따라 발송 완료로 저장한다")
	void deliverPendingNotificationsMarksSentWhenSenderSucceeds() {
		outboxRepository.savePushNotification(notification("push-1", PushNotificationStatus.PENDING));
		sender.nextResult = PushNotificationSendResult.sent();

		var result = deliveryService.deliverPending(new DeliverPushNotificationsCommand("anonymous-user-1"));

		assertThat(result.requestedUserId()).isEqualTo("anonymous-user-1");
		assertThat(result.sentCount()).isEqualTo(1);
		assertThat(result.failedCount()).isZero();
		assertThat(result.processedCount()).isEqualTo(1);
		assertThat(sender.sentNotifications).extracting("notificationId").containsExactly("push-1");
		assertThat(outboxRepository.loadPushNotifications("anonymous-user-1"))
			.extracting("notificationId", "status")
			.containsExactly(tuple("push-1", PushNotificationStatus.SENT));
	}

	@Test
	@DisplayName("sender가 실패한 알림은 실패 상태로 저장한다")
	void deliverPendingNotificationsMarksFailedWhenSenderFails() {
		outboxRepository.savePushNotification(notification("push-1", PushNotificationStatus.PENDING));
		sender.nextResult = PushNotificationSendResult.failed("외부 발송 어댑터가 설정되지 않았습니다.");

		var result = deliveryService.deliverPending(new DeliverPushNotificationsCommand("anonymous-user-1"));

		assertThat(result.sentCount()).isZero();
		assertThat(result.failedCount()).isEqualTo(1);
		assertThat(result.processedCount()).isEqualTo(1);
		assertThat(outboxRepository.loadPushNotifications("anonymous-user-1"))
			.extracting("notificationId", "status")
			.containsExactly(tuple("push-1", PushNotificationStatus.FAILED));
	}

	@Test
	@DisplayName("이미 처리된 알림은 다시 발송하지 않는다")
	void deliverPendingNotificationsSkipsAlreadyProcessedNotifications() {
		outboxRepository.savePushNotification(notification("push-1", PushNotificationStatus.SENT));
		outboxRepository.savePushNotification(notification("push-2", PushNotificationStatus.FAILED));

		var result = deliveryService.deliverPending(new DeliverPushNotificationsCommand("anonymous-user-1"));

		assertThat(result.sentCount()).isZero();
		assertThat(result.failedCount()).isZero();
		assertThat(result.processedCount()).isZero();
		assertThat(sender.sentNotifications).isEmpty();
		assertThat(outboxRepository.loadPushNotifications("anonymous-user-1"))
			.extracting("notificationId", "status")
			.containsExactly(
				tuple("push-1", PushNotificationStatus.SENT),
				tuple("push-2", PushNotificationStatus.FAILED)
			);
	}

	@Test
	@DisplayName("발송 처리 명령은 사용자 식별자를 요구한다")
	void deliverCommandRequiresUserId() {
		assertThatThrownBy(() -> new DeliverPushNotificationsCommand(" "))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("사용자 식별자가 필요합니다.");
	}

	private PushNotification notification(String notificationId, PushNotificationStatus status) {
		return new PushNotification(
			notificationId,
			"anonymous-user-1",
			DevicePlatform.ANDROID,
			"device-token-" + notificationId,
			PushNotificationType.REPORT_STATUS,
			"신고 처리 알림",
			"제보한 내용이 확인되었습니다.",
			status,
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);
	}

	private static org.assertj.core.groups.Tuple tuple(String notificationId, PushNotificationStatus status) {
		return org.assertj.core.api.Assertions.tuple(notificationId, status);
	}

	private static class RecordingPushNotificationSender implements PushNotificationSenderPort {

		private final List<PushNotification> sentNotifications = new ArrayList<>();
		private PushNotificationSendResult nextResult = PushNotificationSendResult.sent();

		@Override
		public PushNotificationSendResult send(PushNotification notification) {
			sentNotifications.add(notification);
			return nextResult;
		}
	}
}
