package com.easysubway.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.application.port.in.PushNotificationHistoryQuery;
import com.easysubway.notification.application.port.out.SearchPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SavePushNotificationOutboxPort;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationFailureReasonCount;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 발송 기간 필터가 한국 하루를 UTC 범위로 바꿔 비교하는지 저장소 구현마다 같은 시나리오로 확인한다.
 * 한국 6월 17일은 UTC 6월 16일 15:00부터 6월 17일 14:59:59까지다.
 */
final class KoreanDayPushHistoryScenario {

	private static final LocalDate DAY = LocalDate.of(2026, 6, 17);
	private static final String FAILURE_REASON = "provider timeout";

	private KoreanDayPushHistoryScenario() {
	}

	static <T extends SavePushNotificationOutboxPort & SearchPushNotificationOutboxPort> void assertFiltersByKoreanDay(
		T repository
	) {
		repository.savePushNotification(at("push-before", LocalDateTime.of(2026, 6, 16, 14, 59, 59)));
		repository.savePushNotification(at("push-start", LocalDateTime.of(2026, 6, 16, 15, 0)));
		repository.savePushNotification(at("push-end", LocalDateTime.of(2026, 6, 17, 14, 59, 59)));
		repository.savePushNotification(at("push-after", LocalDateTime.of(2026, 6, 17, 15, 0)));
		var query = PushNotificationHistoryQuery.of(null, null, null, null, DAY, DAY, null, null);

		assertThat(repository.searchPushNotifications(query))
			.extracting(PushNotification::notificationId)
			.containsExactly("push-end", "push-start");
		assertThat(repository.countPushNotifications(query)).isEqualTo(2);
		assertThat(repository.countFailureReasons(query))
			.extracting(PushNotificationFailureReasonCount::reason, PushNotificationFailureReasonCount::count)
			.containsExactly(org.assertj.core.api.Assertions.tuple(FAILURE_REASON, 2L));
	}

	private static PushNotification at(String notificationId, LocalDateTime createdAtUtc) {
		return new PushNotification(
			notificationId, "user-1", DevicePlatform.ANDROID, "device-token-" + notificationId,
			PushNotificationType.REPORT_STATUS, "제목 " + notificationId, "본문",
			PushNotificationStatus.FAILED, FAILURE_REASON, createdAtUtc);
	}
}
