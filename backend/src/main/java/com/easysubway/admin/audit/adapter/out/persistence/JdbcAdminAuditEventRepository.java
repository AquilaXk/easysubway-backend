package com.easysubway.admin.audit.adapter.out.persistence;

import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcAdminAuditEventRepository implements AdminAuditEventRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcAdminAuditEventRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Override
	public void save(AdminAuditEvent event) {
		jdbcTemplate.update("""
			INSERT INTO admin_audit_events (
				event_type, actor, role_permission, request_id, client_ip, user_agent,
				target_type, target_id, action, outcome, reason, occurred_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			event.eventType().name(),
			event.actor(),
			event.rolePermission(),
			event.requestId(),
			event.clientIp(),
			event.userAgent(),
			event.targetType(),
			event.targetId(),
			event.action(),
			event.outcome().name(),
			event.reason(),
			event.occurredAt()
		);
	}

	@Override
	public List<AdminAuditEvent> findRecent(AdminAuditEventType eventType, int limit) {
		return findRecent(eventType, limit, 0);
	}

	@Override
	public List<AdminAuditEvent> findRecent(AdminAuditEventType eventType, int limit, int offset) {
		if (eventType == null) {
			return jdbcTemplate.query("""
				SELECT audit_id, event_type, actor, role_permission, request_id, client_ip, user_agent,
					target_type, target_id, action, outcome, reason, occurred_at
				FROM admin_audit_events
				ORDER BY occurred_at DESC, audit_id DESC
				LIMIT ? OFFSET ?
				""", this::mapEvent, limit, Math.max(offset, 0));
		}
		return jdbcTemplate.query("""
			SELECT audit_id, event_type, actor, role_permission, request_id, client_ip, user_agent,
				target_type, target_id, action, outcome, reason, occurred_at
			FROM admin_audit_events
			WHERE event_type = ?
			ORDER BY occurred_at DESC, audit_id DESC
			LIMIT ? OFFSET ?
			""", this::mapEvent, eventType.name(), limit, Math.max(offset, 0));
	}

	private AdminAuditEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
		return new AdminAuditEvent(
			resultSet.getLong("audit_id"),
			AdminAuditEventType.valueOf(resultSet.getString("event_type")),
			resultSet.getString("actor"),
			resultSet.getString("role_permission"),
			resultSet.getString("request_id"),
			resultSet.getString("client_ip"),
			resultSet.getString("user_agent"),
			resultSet.getString("target_type"),
			resultSet.getString("target_id"),
			resultSet.getString("action"),
			AdminAuditOutcome.valueOf(resultSet.getString("outcome")),
			resultSet.getString("reason"),
			resultSet.getTimestamp("occurred_at").toLocalDateTime()
		);
	}
}
