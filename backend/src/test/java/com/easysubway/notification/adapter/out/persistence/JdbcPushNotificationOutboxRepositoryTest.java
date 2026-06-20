package com.easysubway.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 푸시 알림 outbox 저장소")
class JdbcPushNotificationOutboxRepositoryTest {

	private JdbcPushNotificationOutboxRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:push-notification-outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS push_notification_outbox");
		jdbcTemplate.execute("""
			CREATE TABLE push_notification_outbox (
				notification_id VARCHAR(120) NOT NULL PRIMARY KEY,
				user_id VARCHAR(120) NOT NULL,
				platform VARCHAR(20) NOT NULL,
				device_token VARCHAR(255) NOT NULL,
				notification_type VARCHAR(60) NOT NULL,
				title VARCHAR(120) NOT NULL,
				body VARCHAR(1000) NOT NULL,
				status VARCHAR(40) NOT NULL,
				failure_reason VARCHAR(1000),
				created_at TIMESTAMP NOT NULL,
				CONSTRAINT chk_push_notification_outbox_platform CHECK (platform IN ('ANDROID', 'IOS')),
				CONSTRAINT chk_push_notification_outbox_type CHECK (notification_type IN ('FAVORITE_STATION_FACILITY', 'FAVORITE_ROUTE_FACILITY', 'REPORT_STATUS', 'DATA_QUALITY')),
				CONSTRAINT chk_push_notification_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
				CONSTRAINT chk_push_notification_outbox_failure_reason CHECK (failure_reason IS NULL OR status = 'FAILED')
			)
			""");
		repository = new JdbcPushNotificationOutboxRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("푸시 알림을 저장하고 사용자 식별자로 생성 순서대로 조회한다")
	void savePushNotificationAndLoadByUserId() {
		var secondNotification = notification("push-2", "anonymous-user-1", PushNotificationType.REPORT_STATUS, 10);
		var firstNotification = notification("push-1", "anonymous-user-1", PushNotificationType.FAVORITE_STATION_FACILITY, 9);
		repository.savePushNotification(secondNotification);
		repository.savePushNotification(firstNotification);
		repository.savePushNotification(notification("push-3", "anonymous-user-2", PushNotificationType.DATA_QUALITY, 8));

		assertThat(repository.loadPushNotifications("anonymous-user-1"))
			.containsExactly(firstNotification, secondNotification);
	}

	@Test
	@DisplayName("같은 푸시 알림 식별자는 한 행만 갱신한다")
	void savePushNotificationUpdatesExistingNotification() {
		repository.savePushNotification(notification("push-1", "anonymous-user-1", PushNotificationType.REPORT_STATUS, 9));
		var updatedNotification = notification(
			"push-1",
			"anonymous-user-1",
			PushNotificationType.DATA_QUALITY,
			PushNotificationStatus.SENT,
			10
		);

		repository.savePushNotification(updatedNotification);

		assertThat(repository.loadPushNotifications("anonymous-user-1")).containsExactly(updatedNotification);
	}

	@Test
	@DisplayName("idempotent 대기 알림 저장은 이미 처리된 알림 상태를 되돌리지 않는다")
	void savePendingPushNotificationIfAbsentKeepsProcessedNotification() {
		var pendingNotification = notification("push-1", "anonymous-user-1", PushNotificationType.REPORT_STATUS, 9);
		var sentNotification = notification(
			"push-1",
			"anonymous-user-1",
			PushNotificationType.REPORT_STATUS,
			PushNotificationStatus.SENT,
			10
		);
		repository.savePushNotification(pendingNotification);
		repository.savePushNotification(sentNotification);

		var savedNotification = repository.savePendingPushNotificationIfAbsent(pendingNotification);

		assertThat(savedNotification).isEqualTo(sentNotification);
		assertThat(repository.loadPushNotifications("anonymous-user-1")).containsExactly(sentNotification);
	}

	@Test
	@DisplayName("실패한 푸시 알림은 실패 사유를 저장하고 조회한다")
	void savePushNotificationStoresFailureReasonForFailedNotification() {
		var failedNotification = failedNotification(
			"push-1",
			"anonymous-user-1",
			PushNotificationType.REPORT_STATUS,
			"외부 발송 어댑터가 설정되지 않았습니다.",
			9
		);

		repository.savePushNotification(failedNotification);

		assertThat(repository.loadPushNotifications("anonymous-user-1"))
			.containsExactly(failedNotification);
	}

	@Test
	@DisplayName("대기 중인 푸시 알림만 발송 대상으로 조회한다")
	void loadPendingPushNotificationsReturnsPendingOnly() {
		var pendingNotification = notification("push-1", "anonymous-user-1", PushNotificationType.REPORT_STATUS, 9);
		repository.savePushNotification(pendingNotification);
		repository.savePushNotification(notification(
			"push-2",
			"anonymous-user-1",
			PushNotificationType.DATA_QUALITY,
			PushNotificationStatus.SENT,
			10
		));
		repository.savePushNotification(notification(
			"push-3",
			"anonymous-user-1",
			PushNotificationType.FAVORITE_ROUTE_FACILITY,
			PushNotificationStatus.FAILED,
			11
		));

		assertThat(repository.loadPendingPushNotifications("anonymous-user-1"))
			.containsExactly(pendingNotification);
	}

	@Test
	@DisplayName("대기 알림 선점은 pending 행만 처리 중으로 전환한다")
	void claimPendingPushNotificationUpdatesPendingOnly() {
		var pendingNotification = notification("push-1", "anonymous-user-1", PushNotificationType.REPORT_STATUS, 9);
		repository.savePushNotification(pendingNotification);

		assertThat(repository.claimPendingPushNotification(pendingNotification)).isTrue();
		assertThat(repository.claimPendingPushNotification(pendingNotification)).isFalse();
		assertThat(repository.loadPushNotifications("anonymous-user-1"))
			.extracting("notificationId", "status")
			.containsExactly(tuple("push-1", PushNotificationStatus.PROCESSING));
	}

	@Test
	@DisplayName("대기 중인 알림이 있는 사용자만 가장 오래된 대기 알림 순서로 조회한다")
	void loadPendingPushNotificationUserIdsReturnsUsersByOldestPendingNotification() {
		repository.savePushNotification(notification("push-1", "anonymous-user-2", PushNotificationType.REPORT_STATUS, 10));
		repository.savePushNotification(notification("push-2", "anonymous-user-1", PushNotificationType.DATA_QUALITY, 9));
		repository.savePushNotification(notification(
			"push-3",
			"anonymous-user-3",
			PushNotificationType.FAVORITE_ROUTE_FACILITY,
			PushNotificationStatus.SENT,
			8
		));
		repository.savePushNotification(notification("push-4", "anonymous-user-2", PushNotificationType.DATA_QUALITY, 11));

		assertThat(repository.loadPendingPushNotificationUserIds())
			.containsExactly("anonymous-user-1", "anonymous-user-2");
	}

	private static org.assertj.core.groups.Tuple tuple(Object... values) {
		return org.assertj.core.api.Assertions.tuple(values);
	}

	@Test
	@DisplayName("전체 outbox를 상태별로 집계한다")
	void summarizePushNotificationOutboxByStatus() {
		repository.savePushNotification(notification("push-1", "anonymous-user-1", PushNotificationType.REPORT_STATUS, 9));
		repository.savePushNotification(notification("push-2", "anonymous-user-1", PushNotificationType.DATA_QUALITY, 10));
		repository.savePushNotification(notification(
			"push-3",
			"anonymous-user-2",
			PushNotificationType.FAVORITE_ROUTE_FACILITY,
			PushNotificationStatus.SENT,
			11
		));
		repository.savePushNotification(failedNotification(
			"push-4",
			"anonymous-user-3",
			PushNotificationType.FAVORITE_STATION_FACILITY,
			"외부 발송 어댑터가 설정되지 않았습니다.",
			12
		));

		var summary = repository.summarizePushNotificationOutbox();

		assertThat(summary.totalCount()).isEqualTo(4);
		assertThat(summary.pendingCount()).isEqualTo(2);
		assertThat(summary.sentCount()).isEqualTo(1);
		assertThat(summary.failedCount()).isEqualTo(1);
		assertThat(summary.latestFailureReason()).isEqualTo("외부 발송 어댑터가 설정되지 않았습니다.");
	}

	@Test
	@DisplayName("사용자 데이터 삭제 요청은 해당 사용자의 푸시 알림 개수를 반환한다")
	void deletePushNotificationsByUserIdReturnsDeletedCount() {
		repository.savePushNotification(notification("push-1", "anonymous-user-1", PushNotificationType.REPORT_STATUS, 9));
		repository.savePushNotification(notification("push-2", "anonymous-user-1", PushNotificationType.DATA_QUALITY, 10));
		repository.savePushNotification(notification("push-3", "anonymous-user-2", PushNotificationType.FAVORITE_ROUTE_FACILITY, 11));

		int deletedCount = repository.deletePushNotifications("anonymous-user-1");
		int deletedAgainCount = repository.deletePushNotifications("anonymous-user-1");

		assertThat(deletedCount).isEqualTo(2);
		assertThat(deletedAgainCount).isZero();
		assertThat(repository.loadPushNotifications("anonymous-user-1")).isEmpty();
		assertThat(repository.loadPushNotifications("anonymous-user-2"))
			.containsExactly(notification("push-3", "anonymous-user-2", PushNotificationType.FAVORITE_ROUTE_FACILITY, 11));
	}

	private PushNotification notification(
		String notificationId,
		String userId,
		PushNotificationType type,
		int hour
	) {
		return notification(notificationId, userId, type, PushNotificationStatus.PENDING, hour);
	}

	private PushNotification notification(
		String notificationId,
		String userId,
		PushNotificationType type,
		PushNotificationStatus status,
		int hour
	) {
		return new PushNotification(
			notificationId,
			userId,
			DevicePlatform.ANDROID,
			"device-token-" + notificationId,
			type,
			"알림 제목 " + notificationId,
			"알림 본문 " + notificationId,
			status,
			LocalDateTime.of(2026, 6, 17, hour, 0)
		);
	}

	private PushNotification failedNotification(
		String notificationId,
		String userId,
		PushNotificationType type,
		String failureReason,
		int hour
	) {
		return new PushNotification(
			notificationId,
			userId,
			DevicePlatform.ANDROID,
			"device-token-" + notificationId,
			type,
			"알림 제목 " + notificationId,
			"알림 본문 " + notificationId,
			PushNotificationStatus.FAILED,
			failureReason,
			LocalDateTime.of(2026, 6, 17, hour, 0)
		);
	}
}
