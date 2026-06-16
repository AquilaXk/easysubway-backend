package com.easysubway.notification.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("푸시 알림 발송 결과")
class PushNotificationSendResultTest {

	@Test
	@DisplayName("성공 결과에는 공백 실패 사유도 남길 수 없다")
	void successfulResultRejectsBlankFailureReason() {
		assertThatThrownBy(() -> new PushNotificationSendResult(true, " "))
			.isInstanceOf(InvalidPushNotificationException.class)
			.hasMessage("발송 성공 결과에는 실패 사유를 둘 수 없습니다.");
	}
}
