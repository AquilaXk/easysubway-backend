package com.easysubway.notification.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 알림 발송 처리 결과")
class PushNotificationDeliveryResultTest {

	@Test
	@DisplayName("처리 건수와 알림 목록 크기가 다르면 생성할 수 없다")
	void deliveryResultRequiresCountsToMatchNotifications() {
		assertThatThrownBy(() -> new PushNotificationDeliveryResult("anonymous-user-1", 1, 0, List.of()))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("처리 건수와 알림 목록 크기가 일치해야 합니다.");
	}
}
