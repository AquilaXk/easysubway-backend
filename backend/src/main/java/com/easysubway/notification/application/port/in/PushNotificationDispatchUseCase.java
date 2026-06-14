package com.easysubway.notification.application.port.in;

import com.easysubway.notification.domain.PushNotificationDispatchResult;

public interface PushNotificationDispatchUseCase {

	PushNotificationDispatchResult dispatch(DispatchPushNotificationCommand command);
}
