package com.easysubway.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 푸시 알림 outbox 저장소")
class InMemoryPushNotificationOutboxRepositoryTest {

	private final InMemoryPushNotificationOutboxRepository repository = new InMemoryPushNotificationOutboxRepository();

	@Test
	@DisplayName("같은 알림 식별자는 사용자 버킷을 옮겨도 한 번만 저장한다")
	void savePushNotificationKeepsNotificationIdUniqueAcrossUsers() {
		repository.savePushNotification(notification("push-1", "anonymous-user-1", PushNotificationStatus.PENDING));
		var movedNotification = notification("push-1", "anonymous-user-2", PushNotificationStatus.SENT);

		repository.savePushNotification(movedNotification);

		assertThat(repository.loadPushNotifications("anonymous-user-1")).isEmpty();
		assertThat(repository.loadPushNotifications("anonymous-user-2")).containsExactly(movedNotification);
	}

	@Test
	@DisplayName("대기 중인 알림이 있는 사용자만 가장 오래된 대기 알림 순서로 조회한다")
	void loadPendingPushNotificationUserIdsReturnsUsersByOldestPendingNotification() {
		repository.savePushNotification(notification(
			"push-1",
			"anonymous-user-2",
			PushNotificationStatus.PENDING,
			LocalDateTime.of(2026, 6, 17, 10, 0)
		));
		repository.savePushNotification(notification(
			"push-2",
			"anonymous-user-1",
			PushNotificationStatus.PENDING,
			LocalDateTime.of(2026, 6, 17, 9, 0)
		));
		repository.savePushNotification(notification(
			"push-3",
			"anonymous-user-3",
			PushNotificationStatus.SENT,
			LocalDateTime.of(2026, 6, 17, 8, 0)
		));

		assertThat(repository.loadPendingPushNotificationUserIds())
			.containsExactly("anonymous-user-1", "anonymous-user-2");
	}

	@Test
	@DisplayName("대기 알림 선점은 pending 행만 처리 중으로 전환한다")
	void claimPendingPushNotificationUpdatesPendingOnly() {
		var pendingNotification = notification("push-1", "anonymous-user-1", PushNotificationStatus.PENDING);
		repository.savePushNotification(pendingNotification);

		assertThat(repository.claimPendingPushNotification(pendingNotification)).isTrue();
		assertThat(repository.claimPendingPushNotification(pendingNotification)).isFalse();
		assertThat(repository.loadPushNotifications("anonymous-user-1"))
			.extracting("notificationId", "status")
			.containsExactly(tuple("push-1", PushNotificationStatus.PROCESSING));
	}

	@Test
	@DisplayName("오래된 processing claim은 다시 발송 대상으로 조회하고 재선점한다")
	void staleProcessingClaimCanBeClaimedAgain() {
		var clock = new MutableClock(Instant.parse("2026-06-17T10:00:00Z"));
		var repository = new InMemoryPushNotificationOutboxRepository(clock, Duration.ofMinutes(5));
		var pendingNotification = notification("push-1", "anonymous-user-1", PushNotificationStatus.PENDING);
		repository.savePushNotification(pendingNotification);
		assertThat(repository.claimPendingPushNotification(pendingNotification)).isTrue();
		clock.advance(Duration.ofMinutes(6));

		assertThat(repository.loadPendingPushNotifications("anonymous-user-1"))
			.extracting("notificationId", "status")
			.containsExactly(tuple("push-1", PushNotificationStatus.PROCESSING));
		assertThat(repository.claimPendingPushNotification(pendingNotification.withStatus(PushNotificationStatus.PROCESSING)))
			.isTrue();
	}

	@Test
	@DisplayName("이력 검색은 상태·유형·키워드로 필터하고 최신순으로 정렬한다")
	void searchPushNotificationsFiltersAndOrders() {
		repository.savePushNotification(historyNotification(
			"push-1", "user-1", PushNotificationType.REPORT_STATUS, PushNotificationStatus.PENDING,
			"신고 처리 알림", LocalDateTime.of(2026, 6, 17, 9, 0)));
		repository.savePushNotification(new PushNotification(
			"push-2", "user-2", DevicePlatform.ANDROID, "device-token-2", PushNotificationType.DATA_QUALITY,
			"데이터 품질 알림", "본문", PushNotificationStatus.FAILED, "provider timeout",
			LocalDateTime.of(2026, 6, 17, 10, 0)));
		repository.savePushNotification(historyNotification(
			"push-3", "user-3", PushNotificationType.REPORT_STATUS, PushNotificationStatus.SENT,
			"신고 완료 알림", LocalDateTime.of(2026, 6, 17, 11, 0)));

		assertThat(repository.searchPushNotifications(query(PushNotificationStatus.FAILED, null, null)))
			.extracting(PushNotification::notificationId).containsExactly("push-2");
		assertThat(repository.searchPushNotifications(query(null, PushNotificationType.REPORT_STATUS, null)))
			.extracting(PushNotification::notificationId).containsExactly("push-3", "push-1");
		assertThat(repository.searchPushNotifications(query(null, null, "품질")))
			.extracting(PushNotification::notificationId).containsExactly("push-2");
	}

	@Test
	@DisplayName("이력 검색은 페이지 크기·오프셋으로 잘라내고 전체 건수를 센다")
	void searchPushNotificationsPaginatesAndCounts() {
		for (int index = 0; index < 5; index++) {
			repository.savePushNotification(historyNotification(
				"push-" + index, "user-1", PushNotificationType.REPORT_STATUS, PushNotificationStatus.PENDING,
				"신고 알림 " + index, LocalDateTime.of(2026, 6, 17, 9 + index, 0)));
		}

		assertThat(repository.searchPushNotifications(
			new com.easysubway.notification.application.port.in.PushNotificationHistoryQuery(
				null, null, null, null, null, null, 0, 2)))
			.extracting(PushNotification::notificationId).containsExactly("push-4", "push-3");
		assertThat(repository.searchPushNotifications(
			new com.easysubway.notification.application.port.in.PushNotificationHistoryQuery(
				null, null, null, null, null, null, 2, 2)))
			.extracting(PushNotification::notificationId).containsExactly("push-0");
		assertThat(repository.countPushNotifications(query(null, null, null))).isEqualTo(5);
	}

	@Test
	@DisplayName("실패 사유별 분해는 status=FAILED를 사유별로 GROUP BY 하고 목록 건수와 정합한다")
	void countFailureReasonsGroupsFailedByReason() {
		repository.savePushNotification(new PushNotification(
			"push-1", "user-1", DevicePlatform.ANDROID, "device-token-1", PushNotificationType.REPORT_STATUS,
			"제목", "본문", PushNotificationStatus.FAILED, "provider timeout", LocalDateTime.of(2026, 6, 17, 9, 0)));
		repository.savePushNotification(new PushNotification(
			"push-2", "user-2", DevicePlatform.ANDROID, "device-token-2", PushNotificationType.REPORT_STATUS,
			"제목", "본문", PushNotificationStatus.FAILED, "provider timeout", LocalDateTime.of(2026, 6, 17, 10, 0)));
		repository.savePushNotification(new PushNotification(
			"push-3", "user-3", DevicePlatform.ANDROID, "device-token-3", PushNotificationType.DATA_QUALITY,
			"제목", "본문", PushNotificationStatus.FAILED, "invalid token", LocalDateTime.of(2026, 6, 17, 11, 0)));
		repository.savePushNotification(historyNotification(
			"push-4", "user-4", PushNotificationType.REPORT_STATUS, PushNotificationStatus.SENT,
			"제목", LocalDateTime.of(2026, 6, 17, 12, 0)));

		assertThat(repository.countFailureReasons(query(null, null, null)))
			.extracting(
				com.easysubway.notification.domain.PushNotificationFailureReasonCount::reason,
				com.easysubway.notification.domain.PushNotificationFailureReasonCount::count)
			.containsExactly(tuple("provider timeout", 2L), tuple("invalid token", 1L));

		var drilldown = com.easysubway.notification.application.port.in.PushNotificationHistoryQuery.of(
			null, null, null, "provider timeout", null, null, null, null);
		assertThat(repository.countPushNotifications(drilldown)).isEqualTo(2);
	}

	private static com.easysubway.notification.application.port.in.PushNotificationHistoryQuery query(
		PushNotificationStatus status,
		PushNotificationType type,
		String keyword
	) {
		return com.easysubway.notification.application.port.in.PushNotificationHistoryQuery.of(
			status, type, keyword, null, null, null, null, null);
	}

	private PushNotification historyNotification(
		String notificationId,
		String userId,
		PushNotificationType type,
		PushNotificationStatus status,
		String title,
		LocalDateTime createdAt
	) {
		return new PushNotification(
			notificationId, userId, DevicePlatform.ANDROID, "device-token-" + notificationId, type,
			title, "본문 " + notificationId, status, createdAt);
	}

	private static org.assertj.core.groups.Tuple tuple(Object... values) {
		return org.assertj.core.api.Assertions.tuple(values);
	}

	private static class MutableClock extends Clock {

		private Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}

	private PushNotification notification(String notificationId, String userId, PushNotificationStatus status) {
		return notification(notificationId, userId, status, LocalDateTime.of(2026, 6, 17, 10, 0));
	}

	private PushNotification notification(
		String notificationId,
		String userId,
		PushNotificationStatus status,
		LocalDateTime createdAt
	) {
		return new PushNotification(
			notificationId,
			userId,
			DevicePlatform.ANDROID,
			"device-token-" + notificationId,
			PushNotificationType.REPORT_STATUS,
			"신고 처리 알림",
			"제보한 내용이 확인되었습니다.",
			status,
			createdAt
		);
	}
}
