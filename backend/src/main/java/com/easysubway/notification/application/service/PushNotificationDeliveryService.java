package com.easysubway.notification.application.service;

import com.easysubway.notification.application.port.in.DeliverPushNotificationsCommand;
import com.easysubway.notification.application.port.in.PushNotificationDeliveryUseCase;
import com.easysubway.notification.application.port.out.LoadPendingPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.PushNotificationSenderPort;
import com.easysubway.notification.application.port.out.SavePushNotificationOutboxPort;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDeliveryResult;
import com.easysubway.notification.domain.PushNotificationSendResult;
import com.easysubway.notification.domain.PushNotificationStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PushNotificationDeliveryService implements PushNotificationDeliveryUseCase {

	private static final String DEFAULT_SEND_EXCEPTION_FAILURE_REASON = "푸시 발송 중 예외가 발생했습니다.";

	private final LoadPendingPushNotificationOutboxPort loadPendingPushNotificationOutboxPort;
	private final SavePushNotificationOutboxPort savePushNotificationOutboxPort;
	private final PushNotificationSenderPort pushNotificationSenderPort;

	public PushNotificationDeliveryService(
		LoadPendingPushNotificationOutboxPort loadPendingPushNotificationOutboxPort,
		SavePushNotificationOutboxPort savePushNotificationOutboxPort,
		PushNotificationSenderPort pushNotificationSenderPort
	) {
		this.loadPendingPushNotificationOutboxPort = loadPendingPushNotificationOutboxPort;
		this.savePushNotificationOutboxPort = savePushNotificationOutboxPort;
		this.pushNotificationSenderPort = pushNotificationSenderPort;
	}

	@Override
	public PushNotificationDeliveryResult deliverPending(DeliverPushNotificationsCommand command) {
		List<PushNotification> deliveredNotifications = new ArrayList<>();
		int sentCount = 0;
		int failedCount = 0;

		for (PushNotification notification : loadPendingPushNotificationOutboxPort.loadPendingPushNotifications(
			command.userId()
		)) {
			if (!savePushNotificationOutboxPort.claimPendingPushNotification(notification)) {
				continue;
			}
			var claimedNotification = notification.withStatus(PushNotificationStatus.PROCESSING);
			var sendResult = safeSend(claimedNotification);
			PushNotification savedNotification = savePushNotificationOutboxPort.savePushNotification(
				claimedNotification.withSendResult(sendResult)
			);
			deliveredNotifications.add(savedNotification);
			if (sendResult.successful()) {
				sentCount++;
			} else {
				failedCount++;
			}
		}

		return new PushNotificationDeliveryResult(command.userId(), sentCount, failedCount, deliveredNotifications);
	}

	private PushNotificationSendResult safeSend(PushNotification notification) {
		try {
			return pushNotificationSenderPort.send(notification);
		} catch (RuntimeException exception) {
			return PushNotificationSendResult.failed(sendExceptionFailureReason(exception));
		}
	}

	private String sendExceptionFailureReason(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			return DEFAULT_SEND_EXCEPTION_FAILURE_REASON;
		}
		return message;
	}
}
