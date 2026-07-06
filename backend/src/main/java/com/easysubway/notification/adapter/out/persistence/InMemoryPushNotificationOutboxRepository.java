package com.easysubway.notification.adapter.out.persistence;

import com.easysubway.notification.application.port.in.PushNotificationHistoryQuery;
import com.easysubway.notification.application.port.out.LoadPendingPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.LoadPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SavePushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SearchPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SummarizePushNotificationOutboxPort;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import com.easysubway.notification.domain.PushNotificationFailureReasonCount;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.user.application.port.out.DeleteUserPushNotificationPort;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryPushNotificationOutboxRepository implements
	LoadPushNotificationOutboxPort,
	LoadPendingPushNotificationOutboxPort,
	SavePushNotificationOutboxPort,
	SearchPushNotificationOutboxPort,
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
	public List<PushNotification> searchPushNotifications(PushNotificationHistoryQuery query) {
		List<PushNotification> matched = matchingHistory(query)
			.sorted(Comparator
				.comparing(PushNotification::createdAt)
				.thenComparing(PushNotification::notificationId)
				.reversed())
			.toList();
		int fromIndex = Math.min(query.offset(), matched.size());
		int toIndex = Math.min(fromIndex + query.size(), matched.size());
		return List.copyOf(matched.subList(fromIndex, toIndex));
	}

	@Override
	public long countPushNotifications(PushNotificationHistoryQuery query) {
		return matchingHistory(query).count();
	}

	@Override
	public List<PushNotificationFailureReasonCount> countFailureReasons(PushNotificationHistoryQuery query) {
		Map<String, Long> countsByReason = new java.util.LinkedHashMap<>();
		notificationsByUserId.values().stream()
			.flatMap(List::stream)
			.filter(notification -> matchesFailureBreakdown(notification, query))
			.forEach(notification ->
				countsByReason.merge(notification.failureReason(), 1L, Long::sum));
		return countsByReason.entrySet().stream()
			.map(entry -> new PushNotificationFailureReasonCount(entry.getKey(), entry.getValue()))
			.sorted(Comparator
				.comparingLong(PushNotificationFailureReasonCount::count).reversed()
				.thenComparing(PushNotificationFailureReasonCount::reason))
			.toList();
	}

	// 실패 분해: status=FAILED 고정 + 유형·검색·기간(사유 드릴다운·상태 필터는 무시).
	private boolean matchesFailureBreakdown(PushNotification notification, PushNotificationHistoryQuery query) {
		if (notification.status() != PushNotificationStatus.FAILED || notification.failureReason() == null) {
			return false;
		}
		if (query.hasType() && notification.type() != query.type()) {
			return false;
		}
		if (query.hasKeyword() && !matchesKeyword(notification, query.keyword())) {
			return false;
		}
		LocalDateTime createdAt = notification.createdAt();
		if (query.createdFrom() != null && createdAt.isBefore(query.createdFrom().atStartOfDay())) {
			return false;
		}
		return query.createdTo() == null
			|| createdAt.isBefore(query.createdTo().plusDays(1).atStartOfDay());
	}

	@Override
	public List<PushNotification> loadPushNotificationsByIds(List<String> notificationIds) {
		if (notificationIds == null || notificationIds.isEmpty()) {
			return List.of();
		}
		java.util.Set<String> ids = new java.util.HashSet<>(notificationIds);
		return notificationsByUserId.values().stream()
			.flatMap(List::stream)
			.filter(notification -> ids.contains(notification.notificationId()))
			.toList();
	}

	private Stream<PushNotification> matchingHistory(PushNotificationHistoryQuery query) {
		return notificationsByUserId.values().stream()
			.flatMap(List::stream)
			.filter(notification -> matchesHistory(notification, query));
	}

	private boolean matchesHistory(PushNotification notification, PushNotificationHistoryQuery query) {
		if (query.hasStatus() && notification.status() != query.status()) {
			return false;
		}
		if (query.hasType() && notification.type() != query.type()) {
			return false;
		}
		if (query.hasKeyword() && !matchesKeyword(notification, query.keyword())) {
			return false;
		}
		if (query.hasFailureReason() && !query.failureReason().equals(notification.failureReason())) {
			return false;
		}
		LocalDateTime createdAt = notification.createdAt();
		if (query.createdFrom() != null && createdAt.isBefore(query.createdFrom().atStartOfDay())) {
			return false;
		}
		return query.createdTo() == null
			|| createdAt.isBefore(query.createdTo().plusDays(1).atStartOfDay());
	}

	private static boolean matchesKeyword(PushNotification notification, String keyword) {
		String needle = keyword.toLowerCase(Locale.ROOT);
		return notification.title().toLowerCase(Locale.ROOT).contains(needle)
			|| notification.body().toLowerCase(Locale.ROOT).contains(needle);
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
