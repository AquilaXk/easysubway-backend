package com.easysubway.collection.adapter.out.persistence;

import com.easysubway.collection.application.port.out.LoadDataCollectionRunPort;
import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod")
public class JdbcDataCollectionRunRepository implements
	LoadDataCollectionRunPort,
	SaveDataCollectionRunPort {

	private final JdbcTemplate jdbcTemplate;

	public JdbcDataCollectionRunRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcDataCollectionRunRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public DataCollectionRun saveRun(DataCollectionRun run) {
		jdbcTemplate.update("""
			INSERT INTO data_collection_runs (
				run_id,
				source,
				status,
				requested_by,
				started_at,
				completed_at,
				collected_count,
				failure_message,
				retryable,
				operator_action
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			run.runId(),
			run.source().name(),
			run.status().name(),
			run.requestedBy(),
			run.startedAt(),
			run.completedAt(),
			run.collectedCount(),
			run.failureMessage(),
			run.retryable(),
			run.operatorAction()
		);
		return run;
	}

	@Override
	public Optional<DataCollectionRun> loadRun(String runId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT run_id, source, status, requested_by, started_at, completed_at, collected_count,
						failure_message, retryable, operator_action
					FROM data_collection_runs
					WHERE run_id = ?
					""",
				this::mapRun,
				runId
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<DataCollectionRun> loadRecentRuns(int limit) {
		if (limit <= 0) {
			return List.of();
		}
		return jdbcTemplate.query(
			"""
				SELECT run_id, source, status, requested_by, started_at, completed_at, collected_count,
					failure_message, retryable, operator_action
				FROM data_collection_runs
				ORDER BY started_at DESC, run_id DESC
				LIMIT ?
				""",
			this::mapRun,
			limit
		);
	}

	private DataCollectionRun mapRun(ResultSet resultSet, int rowNumber) throws SQLException {
		var completedAt = resultSet.getTimestamp("completed_at");
		return new DataCollectionRun(
			resultSet.getString("run_id"),
			DataCollectionSource.valueOf(resultSet.getString("source")),
			DataCollectionStatus.valueOf(resultSet.getString("status")),
			resultSet.getString("requested_by"),
			resultSet.getTimestamp("started_at").toLocalDateTime(),
			completedAt == null ? null : completedAt.toLocalDateTime(),
			resultSet.getInt("collected_count"),
			resultSet.getString("failure_message"),
			resultSet.getBoolean("retryable"),
			resultSet.getString("operator_action")
		);
	}
}
