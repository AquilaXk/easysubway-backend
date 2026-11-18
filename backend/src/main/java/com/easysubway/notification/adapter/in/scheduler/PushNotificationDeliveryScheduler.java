package com.easysubway.notification.adapter.in.scheduler;

import com.easysubway.notification.application.port.in.DeliverPushNotificationsCommand;
import com.easysubway.notification.application.port.in.PushNotificationDeliveryUseCase;
import com.easysubway.notification.application.port.out.LoadPendingPushNotificationOutboxPort;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
	prefix = "easysubway.notifications.push.delivery",
	name = "enabled",
	havingValue = "true"
)
public class PushNotificationDeliveryScheduler {

	private static final Logger log = LoggerFactory.getLogger(PushNotificationDeliveryScheduler.class);

	private final LoadPendingPushNotificationOutboxPort pendingPushNotificationOutboxPort;
	private final PushNotificationDeliveryUseCase deliveryUseCase;
	private final AtomicBoolean deliveryInProgress;

	@Autowired
	public PushNotificationDeliveryScheduler(
		LoadPendingPushNotificationOutboxPort pendingPushNotificationOutboxPort,
		PushNotificationDeliveryUseCase deliveryUseCase
	) {
		this(pendingPushNotificationOutboxPort, deliveryUseCase, new AtomicBoolean(false));
	}

	PushNotificationDeliveryScheduler(
		LoadPendingPushNotificationOutboxPort pendingPushNotificationOutboxPort,
		PushNotificationDeliveryUseCase deliveryUseCase,
		AtomicBoolean deliveryInProgress
	) {
		this.pendingPushNotificationOutboxPort = pendingPushNotificationOutboxPort;
		this.deliveryUseCase = deliveryUseCase;
		this.deliveryInProgress = deliveryInProgress;
	}

	@Scheduled(
		initialDelayString = "${easysubway.notifications.push.delivery.initial-delay-ms:10000}",
		fixedDelayString = "${easysubway.notifications.push.delivery.fixed-delay-ms:60000}"
	)
	void deliverPendingNotifications() {
		if (!deliveryInProgress.compareAndSet(false, true)) {
			return;
		}
		try {
			for (String userId : pendingPushNotificationOutboxPort.loadPendingPushNotificationUserIds()) {
				deliverPendingNotifications(userId);
			}
		} finally {
			deliveryInProgress.set(false);
		}
	}

	private void deliverPendingNotifications(String userId) {
		try {
			var result = deliveryUseCase.deliverPending(new DeliverPushNotificationsCommand(userId));
			if (result.processedCount() > 0) {
				log.info(
					"Pending push notifications delivered. userId={}, sent={}, failed={}",
					userId,
					result.sentCount(),
					result.failedCount()
				);
			}
		} catch (RuntimeException exception) {
			log.warn("Pending push notification delivery failed. userId={}", userId, exception);
		}
	}
}
