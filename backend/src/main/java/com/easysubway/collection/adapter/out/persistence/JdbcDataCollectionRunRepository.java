package com.easysubway.collection.adapter.out.persistence;

import com.easysubway.collection.application.port.out.LoadDataCollectionRunPort;
import com.easysubway.collection.application.port.out.SaveDataCollectionRunPort;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.collection.domain.DataCollectionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcDataCollectionRunRepository implements
	LoadDataCollectionRunPort,
	SaveDataCollectionRunPort {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcDataCollectionRunRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcDataCollectionRunRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public DataCollectionRun saveRun(DataCollectionRun run) {
		int updated = jdbcTemplate.update("""
			UPDATE data_collection_runs
			SET source = ?,
				status = ?,
				requested_by = ?,
				started_at = ?,
				completed_at = ?,
				collected_count = ?,
				failure_message = ?,
				retryable = ?,
				operator_action = ?
			WHERE run_id = ?
			""",
			run.source().name(),
			run.status().name(),
			run.requestedBy(),
			run.startedAt(),
			run.completedAt(),
			run.collectedCount(),
			run.failureMessage(),
			run.retryable(),
			run.operatorAction(),
			run.runId()
		);
		if (updated == 0) {
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
		}
		jdbcTemplate.update("DELETE FROM data_collection_run_steps WHERE run_id = ?", run.runId());
		for (int index = 0; index < run.steps().size(); index++) {
			DataCollectionRunStep step = run.steps().get(index);
			jdbcTemplate.update("""
				INSERT INTO data_collection_run_steps (
					run_id,
					step_order,
					step_name,
					status,
					input_source,
					artifact_reference,
					checksum,
					record_count,
					failure_message
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				run.runId(),
				index,
				step.name(),
				step.status().name(),
				step.inputSource(),
				step.artifactReference(),
				step.checksum(),
				step.recordCount(),
				step.failureMessage()
			);
		}
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
	public Optional<DataCollectionRun> loadLatestCompletedRun(DataCollectionSource source) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(
				"""
					SELECT run_id, source, status, requested_by, started_at, completed_at, collected_count,
						failure_message, retryable, operator_action
					FROM data_collection_runs
					WHERE source = ?
						AND status = ?
						AND completed_at IS NOT NULL
					ORDER BY completed_at DESC, run_id DESC
					LIMIT 1
					""",
				this::mapRun,
				source.name(),
				DataCollectionStatus.COMPLETED.name()
			));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	@Override
	public List<DataCollectionRun> loadRecentRuns(int limit) {
		return loadRecentRuns(limit, 0);
	}

	@Override
	public List<DataCollectionRun> loadRecentRuns(int limit, int offset) {
		if (limit <= 0) {
			return List.of();
		}
		return jdbcTemplate.query(
			"""
				SELECT run_id, source, status, requested_by, started_at, completed_at, collected_count,
					failure_message, retryable, operator_action
				FROM data_collection_runs
				ORDER BY started_at DESC, run_id DESC
				LIMIT ? OFFSET ?
				""",
			this::mapRun,
			limit,
			Math.max(offset, 0)
		);
	}

	private DataCollectionRun mapRun(ResultSet resultSet, int rowNumber) throws SQLException {
		var completedAt = resultSet.getTimestamp("completed_at");
		String runId = resultSet.getString("run_id");
		return new DataCollectionRun(
			runId,
			DataCollectionSource.valueOf(resultSet.getString("source")),
			DataCollectionStatus.valueOf(resultSet.getString("status")),
			resultSet.getString("requested_by"),
			resultSet.getTimestamp("started_at").toLocalDateTime(),
			completedAt == null ? null : completedAt.toLocalDateTime(),
			resultSet.getInt("collected_count"),
			resultSet.getString("failure_message"),
			resultSet.getBoolean("retryable"),
			resultSet.getString("operator_action"),
			loadSteps(runId)
		);
	}

	private List<DataCollectionRunStep> loadSteps(String runId) {
		return jdbcTemplate.query(
			"""
				SELECT step_name, status, input_source, artifact_reference, checksum, record_count, failure_message
				FROM data_collection_run_steps
				WHERE run_id = ?
				ORDER BY step_order ASC
				""",
			(resultSet, rowNumber) -> new DataCollectionRunStep(
				resultSet.getString("step_name"),
				DataCollectionStepStatus.valueOf(resultSet.getString("status")),
				resultSet.getString("input_source"),
				resultSet.getString("artifact_reference"),
				resultSet.getString("checksum"),
				resultSet.getInt("record_count"),
				resultSet.getString("failure_message")
			),
			runId
		);
	}
}
