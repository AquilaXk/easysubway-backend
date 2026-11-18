package com.easysubway.notification.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 발송 이력 행의 상태 표시")
class PushNotificationHistoryRowTest {

	@Test
	@DisplayName("발송 가능하면 PENDING은 대기 중으로 표시한다")
	void pendingIsWaitingWhenDeliveryAvailable() {
		assertThat(PushNotificationHistoryRow.from(notification(PushNotificationStatus.PENDING), true).statusLabel())
			.isEqualTo("대기 중");
	}

	@Test
	@DisplayName("발송 불가일 때만 PENDING을 발송 불가로 표시한다")
	void pendingIsUnavailableWhenDeliveryUnavailable() {
		PushNotificationHistoryRow row =
			PushNotificationHistoryRow.from(notification(PushNotificationStatus.PENDING), false);

		assertThat(row.statusLabel()).isEqualTo("발송 불가");
		assertThat(row.statusTone()).isEqualTo("pending");
	}

	@Test
	@DisplayName("SENT와 FAILED는 발송 가능 여부와 무관하게 실제 상태로 표시한다")
	void settledStatusesKeepTheirOwnLabel() {
		for (boolean available : new boolean[] {true, false}) {
			assertThat(PushNotificationHistoryRow.from(notification(PushNotificationStatus.SENT), available).statusLabel())
				.isEqualTo("발송 완료");
			assertThat(PushNotificationHistoryRow.from(notification(PushNotificationStatus.FAILED), available).statusLabel())
				.isEqualTo("발송 실패");
			assertThat(PushNotificationHistoryRow.from(notification(PushNotificationStatus.PROCESSING), available).statusLabel())
				.isEqualTo("처리 중");
		}
	}

	private static PushNotification notification(PushNotificationStatus status) {
		return new PushNotification(
			"n-1",
			"user-1",
			DevicePlatform.ANDROID,
			"device-token",
			PushNotificationType.REPORT_STATUS,
			"제목",
			"본문",
			status,
			status == PushNotificationStatus.FAILED ? "실패 사유" : null,
			LocalDateTime.of(2026, 10, 5, 9, 0)
		);
	}
}
