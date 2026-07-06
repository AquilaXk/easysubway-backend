package com.easysubway.admin.audit.adapter.out.persistence;

import com.easysubway.admin.audit.application.AdminAuditActorContext;
import com.easysubway.admin.audit.application.AdminAuditQuery;
import com.easysubway.admin.audit.application.port.out.AdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

	@Override
	public List<AdminAuditEvent> search(AdminAuditQuery query) {
		List<Object> arguments = new ArrayList<>();
		String whereClause = buildWhere(query, arguments);
		arguments.add(query.size());
		arguments.add(query.offset());
		return jdbcTemplate.query("""
			SELECT audit_id, event_type, actor, role_permission, request_id, client_ip, user_agent,
				target_type, target_id, action, outcome, reason, occurred_at
			FROM admin_audit_events
			"""
			+ whereClause
			+ " ORDER BY occurred_at DESC, audit_id DESC LIMIT ? OFFSET ?",
			this::mapEvent,
			arguments.toArray());
	}

	@Override
	public List<AdminAuditEvent> findForExport(AdminAuditQuery query, int limit) {
		List<Object> arguments = new ArrayList<>();
		String whereClause = buildWhere(query, arguments);
		arguments.add(Math.max(limit, 0));
		return jdbcTemplate.query(SELECT_COLUMNS
			+ whereClause
			+ " ORDER BY occurred_at DESC, audit_id DESC LIMIT ?",
			this::mapEvent,
			arguments.toArray());
	}

	@Override
	public long count(AdminAuditQuery query) {
		List<Object> arguments = new ArrayList<>();
		String whereClause = buildWhere(query, arguments);
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM admin_audit_events" + whereClause,
			Long.class,
			arguments.toArray());
		return count == null ? 0L : count;
	}

	@Override
	public List<String> findDistinctActors(AdminAuditEventType scopeEventType) {
		if (scopeEventType == null) {
			return jdbcTemplate.queryForList(
				"SELECT DISTINCT actor FROM admin_audit_events ORDER BY actor ASC", String.class);
		}
		return jdbcTemplate.queryForList(
			"SELECT DISTINCT actor FROM admin_audit_events WHERE event_type = ? ORDER BY actor ASC",
			String.class, scopeEventType.name());
	}

	private static final String SELECT_COLUMNS = """
		SELECT audit_id, event_type, actor, role_permission, request_id, client_ip, user_agent,
			target_type, target_id, action, outcome, reason, occurred_at
		FROM admin_audit_events
		""";

	@Override
	public Optional<AdminAuditEvent> findById(long id, AdminAuditEventType scopeEventType, boolean excludePrivacyRead) {
		List<Object> arguments = new ArrayList<>();
		arguments.add(id);
		StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append("WHERE audit_id = ?");
		if (scopeEventType != null) {
			sql.append(" AND event_type = ?");
			arguments.add(scopeEventType.name());
		}
		if (excludePrivacyRead) {
			sql.append(" AND event_type <> ?");
			arguments.add(AdminAuditEventType.PRIVACY_READ.name());
		}
		return jdbcTemplate.query(sql.toString(), this::mapEvent, arguments.toArray()).stream().findFirst();
	}

	// before/after를 별도 조회로 가져오므로 읽기 트랜잭션으로 묶어 두 조회가 같은 스냅샷을 보게 한다.
	@Override
	@Transactional(readOnly = true)
	public AdminAuditActorContext findActorContext(
		AdminAuditEvent pivot, AdminAuditEventType scopeEventType, boolean excludePrivacyRead, int radius) {
		if (radius <= 0) {
			return AdminAuditActorContext.empty();
		}
		// pivot 직전(occurred_at·audit_id 튜플 비교) radius개를 DESC로 뽑아 시간 오름차순으로 뒤집는다.
		List<AdminAuditEvent> before =
			new ArrayList<>(actorNeighbors(pivot, scopeEventType, excludePrivacyRead, radius, true));
		java.util.Collections.reverse(before);
		List<AdminAuditEvent> after = actorNeighbors(pivot, scopeEventType, excludePrivacyRead, radius, false);
		return new AdminAuditActorContext(before, after);
	}

	private List<AdminAuditEvent> actorNeighbors(
		AdminAuditEvent pivot, AdminAuditEventType scopeEventType, boolean excludePrivacyRead,
		int radius, boolean before) {
		String comparator = before ? "<" : ">";
		String order = before ? "DESC" : "ASC";
		List<Object> arguments = new ArrayList<>();
		arguments.add(pivot.actor());
		StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append("WHERE actor = ?");
		if (scopeEventType != null) {
			sql.append(" AND event_type = ?");
			arguments.add(scopeEventType.name());
		}
		if (excludePrivacyRead) {
			sql.append(" AND event_type <> ?");
			arguments.add(AdminAuditEventType.PRIVACY_READ.name());
		}
		sql.append(" AND (occurred_at ").append(comparator).append(" ? OR (occurred_at = ? AND audit_id ")
			.append(comparator).append(" ?))");
		arguments.add(pivot.occurredAt());
		arguments.add(pivot.occurredAt());
		arguments.add(pivot.id());
		sql.append(" ORDER BY occurred_at ").append(order).append(", audit_id ").append(order).append(" LIMIT ?");
		arguments.add(radius);
		return jdbcTemplate.query(sql.toString(), this::mapEvent, arguments.toArray());
	}

	// 감사 필터를 화이트리스트 컬럼으로만 조립한다. target 키워드는 target_id·target_type만 LIKE(메타문자
	// 이스케이프). 기간은 occurred_at 기준(종료일 포함). 사유 없는 조회는 reason IS NULL.
	private String buildWhere(AdminAuditQuery query, List<Object> arguments) {
		List<String> conditions = new ArrayList<>();
		if (query.hasEventType()) {
			conditions.add("event_type = ?");
			arguments.add(query.eventType().name());
		}
		if (query.hasActor()) {
			conditions.add("actor = ?");
			arguments.add(query.actor());
		}
		if (query.hasOutcome()) {
			conditions.add("outcome = ?");
			arguments.add(query.outcome().name());
		}
		if (query.hasTargetKeyword()) {
			conditions.add("(LOWER(target_id) LIKE ? ESCAPE '\\' OR LOWER(target_type) LIKE ? ESCAPE '\\')");
			String pattern = "%" + escapeLike(query.targetKeyword().toLowerCase(Locale.ROOT)) + "%";
			arguments.add(pattern);
			arguments.add(pattern);
		}
		if (query.occurredFrom() != null) {
			conditions.add("occurred_at >= ?");
			arguments.add(query.occurredFrom().atStartOfDay());
		}
		if (query.occurredTo() != null) {
			conditions.add("occurred_at < ?");
			arguments.add(query.occurredTo().plusDays(1).atStartOfDay());
		}
		if (query.reasonMissing()) {
			conditions.add("reason IS NULL");
		}
		if (query.excludePrivacyRead()) {
			conditions.add("event_type <> ?");
			arguments.add(AdminAuditEventType.PRIVACY_READ.name());
		}
		if (conditions.isEmpty()) {
			return "";
		}
		return " WHERE " + String.join(" AND ", conditions);
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
