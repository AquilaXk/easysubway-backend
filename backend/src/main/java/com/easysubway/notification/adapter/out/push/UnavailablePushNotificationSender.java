package com.easysubway.notification.adapter.out.push;

import com.easysubway.notification.application.port.out.PushNotificationSenderPort;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationSendResult;
import org.springframework.stereotype.Component;

@Component
class UnavailablePushNotificationSender implements PushNotificationSenderPort {

	@Override
	public PushNotificationSendResult send(PushNotification notification) {
		// 실제 FCM/APNs 어댑터가 붙기 전에는 성공으로 오인하지 않도록 명확히 실패로 기록한다.
		return PushNotificationSendResult.failed("외부 푸시 발송 어댑터가 설정되지 않았습니다.");
	}
}
