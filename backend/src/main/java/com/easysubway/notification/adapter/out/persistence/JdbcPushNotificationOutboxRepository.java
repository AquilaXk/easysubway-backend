package com.easysubway.notification.adapter.out.persistence;

import com.easysubway.notification.application.port.out.LoadPendingPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.LoadPushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SavePushNotificationOutboxPort;
import com.easysubway.notification.application.port.out.SummarizePushNotificationOutboxPort;
import com.easysubway.notification.domain.DevicePlatform;
import com.easysubway.notification.domain.PushNotification;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import com.easysubway.notification.domain.PushNotificationStatus;
import com.easysubway.notification.domain.PushNotificationType;
import com.easysubway.user.application.port.out.DeleteUserPushNotificationPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class JdbcPushNotificationOutboxRepository implements
	LoadPushNotificationOutboxPort,
	LoadPendingPushNotificationOutboxPort,
	SavePushNotificationOutboxPort,
	SummarizePushNotificationOutboxPort,
	DeleteUserPushNotificationPort {

	private final JdbcTemplate jdbcTemplate;

	public JdbcPushNotificationOutboxRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcPushNotificationOutboxRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public List<PushNotification> loadPushNotifications(String userId) {
		return jdbcTemplate.query(
			"""
				SELECT notification_id,
					user_id,
					platform,
					device_token,
					notification_type,
					title,
					body,
					status,
					failure_reason,
					created_at
				FROM push_notification_outbox
				WHERE user_id = ?
				ORDER BY created_at ASC, notification_id ASC
				""",
			this::mapPushNotification,
			userId
		);
	}

	@Override
	public List<PushNotification> loadPendingPushNotifications(String userId) {
		return jdbcTemplate.query(
			"""
				SELECT notification_id,
					user_id,
					platform,
					device_token,
					notification_type,
					title,
					body,
					status,
					failure_reason,
					created_at
				FROM push_notification_outbox
				WHERE user_id = ?
					AND status = ?
				ORDER BY created_at ASC, notification_id ASC
				""",
			this::mapPushNotification,
			userId,
			PushNotificationStatus.PENDING.name()
		);
	}

	@Override
	public List<String> loadPendingPushNotificationUserIds() {
		return jdbcTemplate.query(
			"""
				SELECT user_id
				FROM push_notification_outbox
				WHERE status = ?
				GROUP BY user_id
				ORDER BY MIN(created_at) ASC, user_id ASC
				""",
			(resultSet, rowNumber) -> resultSet.getString("user_id"),
			PushNotificationStatus.PENDING.name()
		);
	}

	@Override
	public PushNotification savePushNotification(PushNotification notification) {
		if (updatePushNotification(notification) == 0) {
			try {
				insertPushNotification(notification);
			} catch (DuplicateKeyException exception) {
				updatePushNotification(notification);
			}
		}
		return notification;
	}

	@Override
	public PushNotificationDashboardSummary summarizePushNotificationOutbox() {
		List<StatusCountRow> statusCounts = jdbcTemplate.query(
			"""
				SELECT status,
					COUNT(*) AS count
				FROM push_notification_outbox
				GROUP BY status
				""",
			(resultSet, rowNumber) -> new StatusCountRow(
				PushNotificationStatus.valueOf(resultSet.getString("status")),
				resultSet.getLong("count")
			)
		);
		long pendingCount = countByStatus(statusCounts, PushNotificationStatus.PENDING);
		long sentCount = countByStatus(statusCounts, PushNotificationStatus.SENT);
		long failedCount = countByStatus(statusCounts, PushNotificationStatus.FAILED);
		String latestFailureReason = latestFailureReason();
		return new PushNotificationDashboardSummary(
			pendingCount + sentCount + failedCount,
			pendingCount,
			sentCount,
			failedCount,
			latestFailureReason
		);
	}

	@Override
	public int deletePushNotifications(String userId) {
		return jdbcTemplate.update(
			"""
				DELETE FROM push_notification_outbox
				WHERE user_id = ?
				""",
			userId
		);
	}

	private long countByStatus(List<StatusCountRow> rows, PushNotificationStatus status) {
		return rows.stream()
			.filter(row -> row.status() == status)
			.mapToLong(StatusCountRow::count)
			.sum();
	}

	private int updatePushNotification(PushNotification notification) {
		return jdbcTemplate.update(
			"""
				UPDATE push_notification_outbox
				SET user_id = ?,
					platform = ?,
					device_token = ?,
					notification_type = ?,
					title = ?,
					body = ?,
					status = ?,
					failure_reason = ?,
					created_at = ?
				WHERE notification_id = ?
				""",
			notification.userId(),
			notification.platform().name(),
			notification.deviceToken(),
			notification.type().name(),
			notification.title(),
			notification.body(),
			notification.status().name(),
			notification.failureReason(),
			notification.createdAt(),
			notification.notificationId()
		);
	}

	private void insertPushNotification(PushNotification notification) {
		jdbcTemplate.update(
			"""
				INSERT INTO push_notification_outbox (
					notification_id,
					user_id,
					platform,
					device_token,
					notification_type,
					title,
					body,
					status,
					failure_reason,
					created_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			notification.notificationId(),
			notification.userId(),
			notification.platform().name(),
			notification.deviceToken(),
			notification.type().name(),
			notification.title(),
			notification.body(),
			notification.status().name(),
			notification.failureReason(),
			notification.createdAt()
		);
	}

	private PushNotification mapPushNotification(ResultSet resultSet, int rowNumber) throws SQLException {
		return new PushNotification(
			resultSet.getString("notification_id"),
			resultSet.getString("user_id"),
			DevicePlatform.valueOf(resultSet.getString("platform")),
			resultSet.getString("device_token"),
			PushNotificationType.valueOf(resultSet.getString("notification_type")),
			resultSet.getString("title"),
			resultSet.getString("body"),
			PushNotificationStatus.valueOf(resultSet.getString("status")),
			resultSet.getString("failure_reason"),
			resultSet.getTimestamp("created_at").toLocalDateTime()
		);
	}

	private String latestFailureReason() {
		return jdbcTemplate.query(
				"""
					SELECT failure_reason
					FROM push_notification_outbox
					WHERE status = ?
						AND failure_reason IS NOT NULL
					ORDER BY created_at DESC, notification_id DESC
					LIMIT 1
					""",
				(resultSet, rowNumber) -> resultSet.getString("failure_reason"),
				PushNotificationStatus.FAILED.name()
			)
			.stream()
			.findFirst()
			.orElse(null);
	}

	private record StatusCountRow(PushNotificationStatus status, long count) {
	}
}
