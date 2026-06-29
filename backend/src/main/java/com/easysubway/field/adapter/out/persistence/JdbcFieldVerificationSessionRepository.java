package com.easysubway.field.adapter.out.persistence;

import com.easysubway.field.application.port.out.FieldVerificationSessionRepository;
import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcFieldVerificationSessionRepository implements FieldVerificationSessionRepository {

	private final JdbcTemplate jdbcTemplate;
	private final DatabaseDialect databaseDialect;

	@Autowired
	public JdbcFieldVerificationSessionRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcFieldVerificationSessionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.databaseDialect = detectDatabaseDialect(jdbcTemplate);
	}

	@Override
	public List<FieldVerificationSession> listAll() {
		return jdbcTemplate.query(
			"""
				SELECT session_id,
					station_id,
					station_name,
					verified_at,
					verified_by,
					status,
					note
				FROM (
					SELECT session_id,
						station_id,
						station_name,
						verified_at,
						verified_by,
						status,
						note,
						ROW_NUMBER() OVER (
							PARTITION BY station_id
							ORDER BY verified_at DESC, session_id ASC
						) AS row_number
					FROM field_verification_sessions
				) ranked_sessions
				WHERE row_number = 1
				ORDER BY verified_at DESC, station_id DESC, session_id ASC
				""",
			this::mapSession
		);
	}

	@Override
	public Optional<FieldVerificationSession> findByStationId(String stationId) {
		List<FieldVerificationSession> sessions = jdbcTemplate.query(
			"""
				SELECT session_id,
					station_id,
					station_name,
					verified_at,
					verified_by,
					status,
					note
				FROM field_verification_sessions
				WHERE station_id = ?
				ORDER BY verified_at DESC, session_id ASC
				LIMIT 1
				""",
			this::mapSession,
			stationId
		);
		return sessions.stream().findFirst();
	}

	@Override
	@Transactional
	public void save(FieldVerificationSession session) {
		if (databaseDialect == DatabaseDialect.H2) {
			saveWithUpdateInsert(session);
			return;
		}
		upsertSession(session);
		session.items().forEach(item -> upsertItem(session, item));
	}

	private void saveWithUpdateInsert(FieldVerificationSession session) {
		if (updateSession(session) == 0) {
			insertSession(session);
		}
		session.items().forEach(item -> {
			if (updateItem(session, item) == 0) {
				insertItem(session, item);
			}
		});
	}

	private void upsertSession(FieldVerificationSession session) {
		// PostgreSQL upsert로 다중 인스턴스 기준선 부트스트랩 충돌을 원자적으로 수렴시킨다.
		jdbcTemplate.update(
			"""
				INSERT INTO field_verification_sessions (
					session_id,
					station_id,
					station_name,
					verified_at,
					verified_by,
					status,
					note
				)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (session_id) DO UPDATE
				SET station_id = EXCLUDED.station_id,
					station_name = EXCLUDED.station_name,
					verified_at = EXCLUDED.verified_at,
					verified_by = EXCLUDED.verified_by,
					status = EXCLUDED.status,
					note = EXCLUDED.note
				""",
			session.id(),
			session.stationId(),
			session.stationName(),
			session.verifiedAt(),
			session.verifiedBy(),
			session.status().name(),
			session.note()
		);
	}

	private int updateSession(FieldVerificationSession session) {
		return jdbcTemplate.update(
			"""
				UPDATE field_verification_sessions
				SET station_id = ?,
					station_name = ?,
					verified_at = ?,
					verified_by = ?,
					status = ?,
					note = ?
				WHERE session_id = ?
				""",
			session.stationId(),
			session.stationName(),
			session.verifiedAt(),
			session.verifiedBy(),
			session.status().name(),
			session.note(),
			session.id()
		);
	}

	private void insertSession(FieldVerificationSession session) {
		jdbcTemplate.update(
			"""
				INSERT INTO field_verification_sessions (
					session_id,
					station_id,
					station_name,
					verified_at,
					verified_by,
					status,
					note
				)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
			session.id(),
			session.stationId(),
			session.stationName(),
			session.verifiedAt(),
			session.verifiedBy(),
			session.status().name(),
			session.note()
		);
	}

	private void upsertItem(FieldVerificationSession session, FieldVerificationItem item) {
		jdbcTemplate.update(
			"""
				INSERT INTO field_verification_items (
					item_id,
					session_id,
					item_type,
					target_name,
					status,
					note
				)
				VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT (item_id) DO UPDATE
				SET session_id = EXCLUDED.session_id,
					item_type = EXCLUDED.item_type,
					target_name = EXCLUDED.target_name,
					status = EXCLUDED.status,
					note = EXCLUDED.note
				""",
			item.id(),
			session.id(),
			item.type().name(),
			item.targetName(),
			item.status().name(),
			item.note()
		);
	}

	private int updateItem(FieldVerificationSession session, FieldVerificationItem item) {
		return jdbcTemplate.update(
			"""
				UPDATE field_verification_items
				SET session_id = ?,
					item_type = ?,
					target_name = ?,
					status = ?,
					note = ?
				WHERE item_id = ?
				""",
			session.id(),
			item.type().name(),
			item.targetName(),
			item.status().name(),
			item.note(),
			item.id()
		);
	}

	private void insertItem(FieldVerificationSession session, FieldVerificationItem item) {
		jdbcTemplate.update(
			"""
				INSERT INTO field_verification_items (
					item_id,
					session_id,
					item_type,
					target_name,
					status,
					note
				)
				VALUES (?, ?, ?, ?, ?, ?)
				""",
			item.id(),
			session.id(),
			item.type().name(),
			item.targetName(),
			item.status().name(),
			item.note()
		);
	}

	private FieldVerificationSession mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
		String sessionId = resultSet.getString("session_id");
		return new FieldVerificationSession(
			sessionId,
			resultSet.getString("station_id"),
			resultSet.getString("station_name"),
			resultSet.getDate("verified_at").toLocalDate(),
			resultSet.getString("verified_by"),
			FieldVerificationStatus.valueOf(resultSet.getString("status")),
			resultSet.getString("note"),
			listItems(sessionId)
		);
	}

	private List<FieldVerificationItem> listItems(String sessionId) {
		return jdbcTemplate.query(
			"""
				SELECT item_id,
					item_type,
					target_name,
					status,
					note
				FROM field_verification_items
				WHERE session_id = ?
				ORDER BY CASE item_type
					WHEN 'EXIT' THEN 1
					WHEN 'ELEVATOR' THEN 2
					WHEN 'ESCALATOR' THEN 3
					WHEN 'RESTROOM' THEN 4
					WHEN 'PLATFORM_TRANSFER' THEN 5
					ELSE 99
				END ASC, item_id ASC
				""",
			this::mapItem,
			sessionId
		);
	}

	private FieldVerificationItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FieldVerificationItem(
			resultSet.getString("item_id"),
			FieldVerificationItemType.valueOf(resultSet.getString("item_type")),
			resultSet.getString("target_name"),
			FieldVerificationStatus.valueOf(resultSet.getString("status")),
			resultSet.getString("note")
		);
	}

	private DatabaseDialect detectDatabaseDialect(JdbcTemplate jdbcTemplate) {
		DatabaseDialect dialect = jdbcTemplate.execute((ConnectionCallback<DatabaseDialect>) connection -> {
			String productName = connection.getMetaData().getDatabaseProductName();
			return "H2".equalsIgnoreCase(productName) ? DatabaseDialect.H2 : DatabaseDialect.POSTGRESQL;
		});
		return dialect == null ? DatabaseDialect.POSTGRESQL : dialect;
	}

	private enum DatabaseDialect {
		POSTGRESQL,
		H2
	}
}
