package com.easysubway.datapack.application.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatapackNormalizationRunCommandService {

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public DatapackNormalizationRunCommandService(DataSource dataSource, ObjectProvider<Clock> clockProvider) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.clock = clockProvider.getIfAvailable(Clock::systemDefaultZone);
	}

	@Transactional
	public void requestRun(String snapshotId, NormalizationRunCommand command) {
		command.validate();
		String sourceId = jdbcTemplate.queryForObject("""
			SELECT source_id
			FROM data_source_snapshots
			WHERE snapshot_id = ? AND snapshot_status = 'LOCKED'
			""", String.class, snapshotId);
		jdbcTemplate.update("""
			INSERT INTO datapack_normalization_runs (
				id, source_id, source_snapshot_id, normalized_count,
				accepted_count, quarantine_count, alias_review_count,
				schema_diff_sha256, schema_diff_summary, status, started_at, completed_at
			)
			VALUES (?, ?, ?, 0, 0, 0, 0, ?, ?, 'RUNNING', ?, NULL)
			""",
			command.runId(),
			sourceId,
			snapshotId,
			command.schemaDiffSha256(),
			command.schemaDiffSummary(),
			LocalDateTime.now(clock)
		);
	}

	public record NormalizationRunCommand(
		String runId,
		String schemaDiffSha256,
		String schemaDiffSummary,
		String reason,
		String idempotencyKey
	) {

		private void validate() {
			requireText(runId, "runId");
			requireSha(schemaDiffSha256, "schemaDiffSha256");
			requireText(schemaDiffSummary, "schemaDiffSummary");
			requireText(reason, "reason");
			requireText(idempotencyKey, "idempotencyKey");
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}

	private static void requireSha(String value, String field) {
		requireText(value, field);
		if (!SHA256_HEX.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a sha256 hex string");
		}
	}
}
