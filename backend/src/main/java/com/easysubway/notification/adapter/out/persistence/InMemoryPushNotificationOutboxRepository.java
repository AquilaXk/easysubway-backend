package com.easysubway.notification.adapter.out.persistence;

import com.easysubway.notification.application.port.out.LoadPendingPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.LoadPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SavePushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SummarizePushNotificationOutboxPort;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.user.application.port.out.DeleteUserPushNotificationPort;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
public class InMemoryPushNotificationOutboxRepository implements
	LoadPushNotificationOutboxPort,
	LoadPendingPushNotificationOutboxPort,
	SavePushNotificationOutboxPort,
	SummarizePushNotificationOutboxPort,
	DeleteUserPushNotificationPort {

	private static final Duration DEFAULT_PROCESSING_CLAIM_TIMEOUT = Duration.ofMinutes(5);

	private final Map<String, List<PushNotification>> notificationsByUserId = new ConcurrentHashMap<>();
	private final Map<String, LocalDateTime> processingClaimedAtByNotificationId = new ConcurrentHashMap<>();
	private final Clock clock;
	private final Duration processingClaimTimeout;

	public InMemoryPushNotificationOutboxRepository() {
		this(Clock.systemUTC(), DEFAULT_PROCESSING_CLAIM_TIMEOUT);
	}

	InMemoryPushNotificationOutboxRepository(Clock clock, Duration processingClaimTimeout) {
		this.clock = clock;
		this.processingClaimTimeout = processingClaimTimeout;
	}

	@Override
	public PushNotification savePushNotification(PushNotification notification) {
		// outbox는 실제 발송 어댑터가 붙기 전까지 생성 순서를 보존해 운영자가 대기열을 확인할 수 있게 둔다.
		for (Map.Entry<String, List<PushNotification>> entry : notificationsByUserId.entrySet()) {
			List<PushNotification> notifications = entry.getValue();
			for (int index = 0; index < notifications.size(); index++) {
				if (!notifications.get(index).notificationId().equals(notification.notificationId())) {
					continue;
				}
				if (entry.getKey().equals(notification.userId())) {
					notifications.set(index, notification);
					recordProcessingClaim(notification);
					return notification;
				}
				notifications.remove(index);
				break;
			}
		}
		List<PushNotification> targetNotifications = notificationsByUserId.computeIfAbsent(
			notification.userId(),
			ignored -> new CopyOnWriteArrayList<>()
		);
		targetNotifications.add(notification);
		recordProcessingClaim(notification);
		return notification;
	}

	@Override
	public PushNotification savePendingPushNotificationIfAbsent(PushNotification notification) {
		return findNotification(notification.notificationId())
			.orElseGet(() -> savePushNotification(notification));
	}

	@Override
	public synchronized boolean claimPendingPushNotification(PushNotification notification) {
		List<PushNotification> notifications = notificationsByUserId.get(notification.userId());
		if (notifications == null) {
			return false;
		}
		for (int index = 0; index < notifications.size(); index++) {
			PushNotification storedNotification = notifications.get(index);
			if (!storedNotification.notificationId().equals(notification.notificationId())) {
				continue;
			}
			if (!canClaim(storedNotification)) {
				return false;
			}
			notifications.set(index, storedNotification.withStatus(PushNotificationStatus.PROCESSING));
			processingClaimedAtByNotificationId.put(storedNotification.notificationId(), now());
			return true;
		}
		return false;
	}

	@Override
	public List<PushNotification> loadPushNotifications(String userId) {
		return List.copyOf(notificationsByUserId.getOrDefault(userId, List.of()));
	}

	@Override
	public List<PushNotification> loadPendingPushNotifications(String userId) {
		return notificationsByUserId.getOrDefault(userId, List.of()).stream()
			.filter(this::canClaim)
			.toList();
	}

	@Override
	public List<String> loadPendingPushNotificationUserIds() {
		return notificationsByUserId.entrySet().stream()
			.flatMap(entry -> oldestPendingCreatedAt(entry.getValue())
				.map(createdAt -> new PendingUser(entry.getKey(), createdAt))
				.stream())
			.sorted(Comparator
				.comparing(PendingUser::oldestPendingCreatedAt)
				.thenComparing(PendingUser::userId))
			.map(PendingUser::userId)
			.toList();
	}

	@Override
	public PushNotificationDashboardSummary summarizePushNotificationOutbox() {
		long pendingCount = 0;
		long sentCount = 0;
		long failedCount = 0;
		PushNotification latestFailedNotification = null;
		for (List<PushNotification> notifications : notificationsByUserId.values()) {
			for (PushNotification notification : notifications) {
				switch (notification.status()) {
					case PENDING, PROCESSING -> pendingCount++;
					case SENT -> sentCount++;
					case FAILED -> {
						failedCount++;
						if (latestFailedNotification == null ||
							notification.createdAt().isAfter(latestFailedNotification.createdAt()) ||
							(notification.createdAt().isEqual(latestFailedNotification.createdAt()) &&
								notification.notificationId().compareTo(latestFailedNotification.notificationId()) > 0)) {
							latestFailedNotification = notification;
						}
					}
				}
			}
		}
		return new PushNotificationDashboardSummary(
			pendingCount + sentCount + failedCount,
			pendingCount,
			sentCount,
			failedCount,
			latestFailedNotification == null ? null : latestFailedNotification.failureReason()
		);
	}

	@Override
	public int deletePushNotifications(String userId) {
		List<PushNotification> removed = notificationsByUserId.remove(userId);
		if (removed != null) {
			for (PushNotification notification : removed) {
				processingClaimedAtByNotificationId.remove(notification.notificationId());
			}
		}
		return removed == null ? 0 : removed.size();
	}

	private Optional<LocalDateTime> oldestPendingCreatedAt(List<PushNotification> notifications) {
		return notifications.stream()
			.filter(this::canClaim)
			.map(PushNotification::createdAt)
			.min(Comparator.naturalOrder());
	}

	private boolean canClaim(PushNotification notification) {
		return notification.status() == PushNotificationStatus.PENDING ||
			(notification.status() == PushNotificationStatus.PROCESSING && isStaleProcessingClaim(notification));
	}

	private boolean isStaleProcessingClaim(PushNotification notification) {
		LocalDateTime claimedAt = processingClaimedAtByNotificationId.get(notification.notificationId());
		return claimedAt != null && claimedAt.isBefore(now().minus(processingClaimTimeout));
	}

	private void recordProcessingClaim(PushNotification notification) {
		if (notification.status() == PushNotificationStatus.PROCESSING) {
			processingClaimedAtByNotificationId.put(notification.notificationId(), now());
			return;
		}
		processingClaimedAtByNotificationId.remove(notification.notificationId());
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	private Optional<PushNotification> findNotification(String notificationId) {
		return notificationsByUserId.values()
			.stream()
			.flatMap(List::stream)
			.filter(notification -> notification.notificationId().equals(notificationId))
			.findFirst();
	}

	private record PendingUser(String userId, LocalDateTime oldestPendingCreatedAt) {
	}
}
