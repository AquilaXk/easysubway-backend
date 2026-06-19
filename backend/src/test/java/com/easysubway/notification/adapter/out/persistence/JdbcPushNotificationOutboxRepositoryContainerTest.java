package com.easysubway.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("PostgreSQL 푸시 알림 outbox 저장소")
class JdbcPushNotificationOutboxRepositoryContainerTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Test
	@DisplayName("idempotent 대기 알림 저장은 같은 트랜잭션 안에서 중복 키 예외 없이 기존 알림을 반환한다")
	void savePendingPushNotificationIfAbsentDoesNotAbortPostgresqlTransaction() {
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/postgresql")
			.load()
			.migrate();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		var repository = new JdbcPushNotificationOutboxRepository(jdbcTemplate);
		var transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		var pendingNotification = notification("push-postgres-idempotent", PushNotificationStatus.PENDING);
		var sentNotification = notification("push-postgres-idempotent", PushNotificationStatus.SENT);
		repository.savePushNotification(sentNotification);

		PushNotification savedNotification = transactionTemplate.execute(status -> {
			PushNotification existingNotification =
				repository.savePendingPushNotificationIfAbsent(pendingNotification);
			Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM push_notification_outbox WHERE notification_id = ?",
				Integer.class,
				pendingNotification.notificationId()
			);
			assertThat(count).isEqualTo(1);
			return existingNotification;
		});

		assertThat(savedNotification).isEqualTo(sentNotification);
		assertThat(repository.loadPushNotifications("anonymous-user-postgres"))
			.containsExactly(sentNotification);
	}

	private PushNotification notification(String notificationId, PushNotificationStatus status) {
		return new PushNotification(
			notificationId,
			"anonymous-user-postgres",
			DevicePlatform.ANDROID,
			"device-token-" + notificationId,
			PushNotificationType.REPORT_STATUS,
			"신고 처리 결과",
			"제보해 주신 신고가 확인되어 시설 정보에 반영되었습니다.",
			status,
			LocalDateTime.of(2026, 6, 19, 19, 40)
		);
	}
}
