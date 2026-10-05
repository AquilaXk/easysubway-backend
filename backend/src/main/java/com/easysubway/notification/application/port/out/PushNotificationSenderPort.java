package com.easysubway.notification.application.port.out;

import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationSendResult;

public interface PushNotificationSenderPort {

	/**
	 * 외부 FCM/APNs 어댑터는 상태 저장 실패 후 재시도되어도 중복 발송되지 않도록
	 * notificationId를 멱등성 키로 사용해야 한다.
	 */
	PushNotificationSendResult send(PushNotification notification);

	/**
	 * 실제 발송 어댑터가 구성되어 있는지. 기본값은 false(fail-closed)이며, 실제 FCM/APNs 어댑터는
	 * 자격 증명까지 갖춰졌을 때만 명시적으로 true를 반환해야 한다.
	 */
	default boolean isConfigured() {
		return false;
	}
}
