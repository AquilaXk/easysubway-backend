package com.easysubway.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.notification.adapter.out.persistence.InMemoryNotificationPreferenceRepository;
import com.easysubway.notification.adapter.out.persistence.InMemoryPushNotificationOutboxRepository;
import com.easysubway.notification.application.port.in.DispatchPushNotificationCommand;
import com.easysubway.notification.application.port.in.RegisterDeviceCommand;
import com.easysubway.notification.application.port.in.SaveNotificationSettingsCommand;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.InvalidPushNotificationException;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 알림 발송 서비스")
class PushNotificationDispatchServiceTest {

	private static final Clock CLOCK = Clock.fixed(
		Instant.parse("2026-06-14T01:00:00Z"),
		ZoneId.of("Asia/Seoul")
	);

	private final InMemoryNotificationPreferenceRepository preferenceRepository =
		new InMemoryNotificationPreferenceRepository();
	private final InMemoryPushNotificationOutboxRepository outboxRepository =
		new InMemoryPushNotificationOutboxRepository();
	private final NotificationPreferenceService preferenceService = new NotificationPreferenceService(
		preferenceRepository,
		preferenceRepository,
		preferenceRepository,
		CLOCK
	);
	private final PushNotificationDispatchService dispatchService = new PushNotificationDispatchService(
		preferenceRepository,
		outboxRepository,
		CLOCK
	);

	@Test
	@DisplayName("수신 설정이 켜진 사용자의 등록 기기마다 발송 후보를 만든다")
	void dispatchCreatesOutboxMessagesForEveryRegisteredDevice() {
		registerDevice("anonymous-user-1", DevicePlatform.ANDROID, "android-token-1");
		registerDevice("anonymous-user-1", DevicePlatform.IOS, "ios-token-1");

		var result = dispatchService.dispatch(new DispatchPushNotificationCommand(
			"anonymous-user-1",
			PushNotificationType.FAVORITE_STATION_FACILITY,
			"엘리베이터 운행 변경",
			"상록수역 엘리베이터 상태를 확인하세요."
		));

		assertThat(result.requestedUserId()).isEqualTo("anonymous-user-1");
		assertThat(result.type()).isEqualTo(PushNotificationType.FAVORITE_STATION_FACILITY);
		assertThat(result.createdCount()).isEqualTo(2);
		assertThat(result.notifications())
			.extracting("platform")
			.containsExactly(DevicePlatform.ANDROID, DevicePlatform.IOS);
		assertThat(outboxRepository.loadPushNotifications("anonymous-user-1"))
			.extracting("status")
			.containsExactly(PushNotificationStatus.PENDING, PushNotificationStatus.PENDING);
	}

	@Test
	@DisplayName("사용자가 꺼둔 알림 종류는 발송 후보를 만들지 않는다")
	void dispatchSkipsDisabledNotificationType() {
		registerDevice("anonymous-user-2", DevicePlatform.ANDROID, "android-token-2");
		preferenceService.saveNotificationSettings(new SaveNotificationSettingsCommand(
			"anonymous-user-2",
			false,
			true,
			true,
			true
		));

		var result = dispatchService.dispatch(new DispatchPushNotificationCommand(
			"anonymous-user-2",
			PushNotificationType.FAVORITE_STATION_FACILITY,
			"엘리베이터 운행 변경",
			"상록수역 엘리베이터 상태를 확인하세요."
		));

		assertThat(result.createdCount()).isZero();
		assertThat(result.notifications()).isEmpty();
		assertThat(outboxRepository.loadPushNotifications("anonymous-user-2")).isEmpty();
	}

	@Test
	@DisplayName("등록된 기기가 없으면 빈 발송 결과를 반환한다")
	void dispatchReturnsEmptyResultWhenUserHasNoDevice() {
		var result = dispatchService.dispatch(new DispatchPushNotificationCommand(
			"anonymous-user-3",
			PushNotificationType.REPORT_STATUS,
			"신고 처리 알림",
			"제보한 내용이 확인되었습니다."
		));

		assertThat(result.createdCount()).isZero();
		assertThat(result.notifications()).isEmpty();
	}

	@Test
	@DisplayName("같은 idempotency key 발송은 outbox 후보를 중복 생성하지 않는다")
	void dispatchWithSameIdempotencyKeyDoesNotDuplicateOutboxMessage() {
		registerDevice("anonymous-user-idempotent", DevicePlatform.ANDROID, "android-token-idempotent");

		dispatchService.dispatch(new DispatchPushNotificationCommand(
			"anonymous-user-idempotent",
			PushNotificationType.REPORT_STATUS,
			"신고 처리 결과",
			"제보해 주신 신고가 확인되어 시설 정보에 반영되었습니다.",
			"report-status:report-1:ACCEPTED"
		));
		var sentNotification = outboxRepository.loadPushNotifications("anonymous-user-idempotent").getFirst()
			.withStatus(PushNotificationStatus.SENT);
		outboxRepository.savePushNotification(sentNotification);
		dispatchService.dispatch(new DispatchPushNotificationCommand(
			"anonymous-user-idempotent",
			PushNotificationType.REPORT_STATUS,
			"신고 처리 결과",
			"제보해 주신 신고가 확인되어 시설 정보에 반영되었습니다.",
			"report-status:report-1:ACCEPTED"
		));

		assertThat(outboxRepository.loadPushNotifications("anonymous-user-idempotent"))
			.hasSize(1)
			.extracting("type", "status")
			.containsExactly(tuple(PushNotificationType.REPORT_STATUS, PushNotificationStatus.SENT));
	}

	@Test
	@DisplayName("발송 명령은 사용자, 알림 종류, 제목, 본문을 요구한다")
	void dispatchCommandRequiresUserTypeTitleAndBody() {
		assertThatThrownBy(() -> new DispatchPushNotificationCommand(
			"",
			PushNotificationType.DATA_QUALITY,
			"정보 갱신",
			"역 정보가 갱신되었습니다."
		))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("사용자 식별자가 필요합니다.");

		assertThatThrownBy(() -> new DispatchPushNotificationCommand(
			"anonymous-user-1",
			null,
			"정보 갱신",
			"역 정보가 갱신되었습니다."
		))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("알림 종류를 선택해야 합니다.");

		assertThatThrownBy(() -> new DispatchPushNotificationCommand(
			"anonymous-user-1",
			PushNotificationType.DATA_QUALITY,
			"",
			"역 정보가 갱신되었습니다."
		))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("알림 제목이 필요합니다.");

		assertThatThrownBy(() -> new DispatchPushNotificationCommand(
			"anonymous-user-1",
			PushNotificationType.DATA_QUALITY,
			"정보 갱신",
			""
		))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("알림 본문이 필요합니다.");
	}

	private void registerDevice(String userId, DevicePlatform platform, String deviceToken) {
		preferenceService.registerDevice(new RegisterDeviceCommand(userId, platform, deviceToken));
	}

	private static org.assertj.core.groups.Tuple tuple(Object... values) {
		return org.assertj.core.api.Assertions.tuple(values);
	}
}
