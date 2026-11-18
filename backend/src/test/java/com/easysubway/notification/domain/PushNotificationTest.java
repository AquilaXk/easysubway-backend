package com.easysubway.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 알림")
class PushNotificationTest {

	@Test
	@DisplayName("실패 사유는 저장 가능한 최대 길이로 보존한다")
	void failureReasonIsTrimmedToPersistenceLimit() {
		String longFailureReason = "가".repeat(1001);

		var notification = new PushNotification(
			"push-1",
			"anonymous-user-1",
			DevicePlatform.ANDROID,
			"device-token",
			PushNotificationType.REPORT_STATUS,
			"신고 처리 알림",
			"제보한 내용이 확인되었습니다.",
			PushNotificationStatus.FAILED,
			longFailureReason,
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);

		assertThat(notification.failureReason()).hasSize(1000);
	}
}
