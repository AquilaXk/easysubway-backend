package com.easysubway.notification.adapter.in.scheduler;

import com.easysubway.notification.application.port.in.DeliverPushNotificationsCommand;
import com.easysubway.notification.application.port.in.PushNotificationDeliveryUseCase;
import com.easysubway.notification.application.port.out.LoadPendingPushNotificationOutboxPort;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
	private static final String DELIVERY_LOCK_KEY = "easysubway:notifications:push:delivery:scheduler-lock";
	private static final RedisScript<Long> ACQUIRE_LOCK_SCRIPT = RedisScript.of("""
		if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
			return 1
		end
		return 0
		""", Long.class);
	private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = RedisScript.of("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
			return redis.call('DEL', KEYS[1])
		end
		return 0
		""", Long.class);

	private final LoadPendingPushNotificationOutboxPort pendingPushNotificationOutboxPort;
	private final PushNotificationDeliveryUseCase deliveryUseCase;
	private final StringRedisTemplate redisTemplate;
	private final long lockTtlMillis;

	public PushNotificationDeliveryScheduler(
		LoadPendingPushNotificationOutboxPort pendingPushNotificationOutboxPort,
		PushNotificationDeliveryUseCase deliveryUseCase,
		StringRedisTemplate redisTemplate,
		@Value("${easysubway.notifications.push.delivery.lock-ttl-ms:300000}") long lockTtlMillis
	) {
		this.pendingPushNotificationOutboxPort = pendingPushNotificationOutboxPort;
		this.deliveryUseCase = deliveryUseCase;
		this.redisTemplate = redisTemplate;
		this.lockTtlMillis = lockTtlMillis;
	}

	@Scheduled(
		initialDelayString = "${easysubway.notifications.push.delivery.initial-delay-ms:10000}",
		fixedDelayString = "${easysubway.notifications.push.delivery.fixed-delay-ms:60000}"
	)
	void deliverPendingNotifications() {
		String lockToken = UUID.randomUUID().toString();
		if (!acquireDeliveryLock(lockToken)) {
			return;
		}
		try {
			for (String userId : pendingPushNotificationOutboxPort.loadPendingPushNotificationUserIds()) {
				deliverPendingNotifications(userId);
			}
		} finally {
			releaseDeliveryLock(lockToken);
		}
	}

	private boolean acquireDeliveryLock(String lockToken) {
		try {
			Long acquired = redisTemplate.execute(
				ACQUIRE_LOCK_SCRIPT,
				List.of(DELIVERY_LOCK_KEY),
				lockToken,
				String.valueOf(lockTtlMillis)
			);
			return Long.valueOf(1).equals(acquired);
		} catch (RuntimeException exception) {
			log.warn("Pending push notification scheduler lock acquisition failed.", exception);
			return false;
		}
	}

	private void releaseDeliveryLock(String lockToken) {
		try {
			redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(DELIVERY_LOCK_KEY), lockToken);
		} catch (RuntimeException exception) {
			log.warn("Pending push notification scheduler lock release failed.", exception);
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
