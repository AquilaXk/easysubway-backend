package com.easysubway.field.adapter.out.persistence;

import com.easysubway.field.application.port.out.FieldVerificationChangeHistoryRepository;
import com.easysubway.field.domain.FieldVerificationChangeHistory;
import com.easysubway.field.domain.FieldVerificationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class JdbcFieldVerificationChangeHistoryRepository implements FieldVerificationChangeHistoryRepository {

	private final JdbcTemplate jdbcTemplate;

	public JdbcFieldVerificationChangeHistoryRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcFieldVerificationChangeHistoryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void save(FieldVerificationChangeHistory history) {
		jdbcTemplate.update(
			"""
				INSERT INTO field_verification_change_history (
					history_id,
					session_id,
					station_id,
					item_id,
					previous_status,
					new_status,
					previous_note,
					new_note,
					changed_by,
					changed_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
			history.id(),
			history.sessionId(),
			history.stationId(),
			history.itemId(),
			history.previousStatus().name(),
			history.newStatus().name(),
			history.previousNote(),
			history.newNote(),
			history.changedBy(),
			history.changedAt()
		);
	}

	@Override
	public List<FieldVerificationChangeHistory> listByStationId(String stationId) {
		return jdbcTemplate.query(
			"""
				SELECT history_id,
					session_id,
					station_id,
					item_id,
					previous_status,
					new_status,
					previous_note,
					new_note,
					changed_by,
					changed_at
				FROM field_verification_change_history
				WHERE station_id = ?
				ORDER BY changed_at DESC, history_id ASC
				""",
			this::mapHistory,
			stationId
		);
	}

	private FieldVerificationChangeHistory mapHistory(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FieldVerificationChangeHistory(
			resultSet.getString("history_id"),
			resultSet.getString("session_id"),
			resultSet.getString("station_id"),
			resultSet.getString("item_id"),
			FieldVerificationStatus.valueOf(resultSet.getString("previous_status")),
			FieldVerificationStatus.valueOf(resultSet.getString("new_status")),
			resultSet.getString("previous_note"),
			resultSet.getString("new_note"),
			resultSet.getString("changed_by"),
			resultSet.getTimestamp("changed_at").toLocalDateTime()
		);
	}
}
