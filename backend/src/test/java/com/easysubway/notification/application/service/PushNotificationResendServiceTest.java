package com.easysubway.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.adapter.out.persistence.InMemoryPushNotificationOutboxRepository;
import com.easysubway.notification.application.port.in.DeliverPushNotificationsCommand;
import com.easysubway.notification.application.port.in.PushNotificationDeliveryUseCase;
import com.easysubway.notification.application.port.in.ResendPushNotificationsCommand;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDeliveryResult;
import com.easysubway.notification.domain.PushNotificationResendResult;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("실패 푸시 재발송 서비스")
class PushNotificationResendServiceTest {

	private final InMemoryPushNotificationOutboxRepository repository = new InMemoryPushNotificationOutboxRepository();
	private final List<String> deliveredUserIds = new ArrayList<>();
	private final PushNotificationDeliveryUseCase deliveryUseCase = new RecordingDeliveryUseCase(deliveredUserIds);
	private final PushNotificationResendService service =
		new PushNotificationResendService(repository, repository, deliveryUseCase);

	@Test
	@DisplayName("실패 건만 대기로 되돌리고 성공 건은 멱등하게 제외한다")
	void resendResetsFailedOnlyAndSkipsSent() {
		repository.savePushNotification(failed("push-1", "user-1", "provider timeout"));
		repository.savePushNotification(sent("push-2", "user-1"));

		PushNotificationResendResult result = service.resend(
			new ResendPushNotificationsCommand(List.of("push-1", "push-2"), 5));

		assertThat(result.blocked()).isFalse();
		assertThat(result.requestedCount()).isEqualTo(2);
		assertThat(result.resentCount()).isEqualTo(1);
		assertThat(result.skippedCount()).isEqualTo(1);
		assertThat(repository.loadPushNotificationsByIds(List.of("push-1")))
			.singleElement()
			.extracting(PushNotification::status)
			.isEqualTo(PushNotificationStatus.PENDING);
		assertThat(repository.loadPushNotificationsByIds(List.of("push-2")))
			.singleElement()
			.extracting(PushNotification::status)
			.isEqualTo(PushNotificationStatus.SENT);
		assertThat(deliveredUserIds).containsExactly("user-1");
	}

	@Test
	@DisplayName("선택 건수가 1회 상한을 넘으면 아무것도 재발송하지 않고 차단한다")
	void resendBlocksWhenOverLimit() {
		repository.savePushNotification(failed("push-1", "user-1", "provider timeout"));
		repository.savePushNotification(failed("push-2", "user-2", "provider timeout"));
		repository.savePushNotification(failed("push-3", "user-3", "provider timeout"));

		PushNotificationResendResult result = service.resend(
			new ResendPushNotificationsCommand(List.of("push-1", "push-2", "push-3"), 2));

		assertThat(result.blocked()).isTrue();
		assertThat(result.resentCount()).isZero();
		assertThat(deliveredUserIds).isEmpty();
		assertThat(repository.loadPushNotificationsByIds(List.of("push-1")))
			.singleElement()
			.extracting(PushNotification::status)
			.isEqualTo(PushNotificationStatus.FAILED);
	}

	@Test
	@DisplayName("이미 성공한 건만 재발송 시도하면 멱등하게 제외한다")
	void resendAlreadySentIsIdempotentNoop() {
		repository.savePushNotification(sent("push-1", "user-1"));

		PushNotificationResendResult result = service.resend(
			new ResendPushNotificationsCommand(List.of("push-1"), 5));

		assertThat(result.resentCount()).isZero();
		assertThat(result.skippedCount()).isEqualTo(1);
		assertThat(deliveredUserIds).isEmpty();
	}

	private static PushNotification failed(String id, String userId, String reason) {
		return new PushNotification(id, userId, DevicePlatform.ANDROID, "device-token-" + id,
			PushNotificationType.REPORT_STATUS, "제목", "본문", PushNotificationStatus.FAILED, reason,
			LocalDateTime.of(2026, 6, 17, 9, 0));
	}

	private static PushNotification sent(String id, String userId) {
		return new PushNotification(id, userId, DevicePlatform.ANDROID, "device-token-" + id,
			PushNotificationType.REPORT_STATUS, "제목", "본문", PushNotificationStatus.SENT,
			LocalDateTime.of(2026, 6, 17, 9, 0));
	}

	private record RecordingDeliveryUseCase(List<String> deliveredUserIds) implements PushNotificationDeliveryUseCase {
		@Override
		public PushNotificationDeliveryResult deliverPending(DeliverPushNotificationsCommand command) {
			deliveredUserIds.add(command.userId());
			return new PushNotificationDeliveryResult(command.userId(), 0, 0, List.of());
		}
	}
}
