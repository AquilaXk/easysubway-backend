package com.easysubway.notification.adapter.out.persistence;

import com.easysubway.notification.application.port.out.LoadPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SavePushNotificationOutboxPort;
import com.easysubway.notification.domain.PushNotification;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryPushNotificationOutboxRepository implements
	LoadPushNotificationOutboxPort,
	SavePushNotificationOutboxPort {

	private final Map<String, List<PushNotification>> notificationsByUserId = new ConcurrentHashMap<>();

	@Override
	public PushNotification savePushNotification(PushNotification notification) {
		// outbox는 실제 발송 어댑터가 붙기 전까지 생성 순서를 보존해 운영자가 대기열을 확인할 수 있게 둔다.
		notificationsByUserId
			.computeIfAbsent(notification.userId(), ignored -> new CopyOnWriteArrayList<>())
			.add(notification);
		return notification;
	}

	@Override
	public List<PushNotification> loadPushNotifications(String userId) {
		return List.copyOf(notificationsByUserId.getOrDefault(userId, List.of()));
	}
}
