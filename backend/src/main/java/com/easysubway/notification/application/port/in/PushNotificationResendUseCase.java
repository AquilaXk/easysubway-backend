package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.PushNotificationResendResult;

/**
 * 실패 푸시 재발송(#1746). 멱등(성공 건 제외)·상한(대량 오발송 방지)을 보장한다.
 */
public interface PushNotificationResendUseCase {

	PushNotificationResendResult resend(ResendPushNotificationsCommand command);
}
