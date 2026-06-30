package com.easysubway.datapack.adapter.out.persistence;

import com.easysubway.datapack.domain.DataSourceSnapshot;
import com.easysubway.datapack.domain.InvalidDataSourceSnapshotException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDataSourceSnapshotRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcDataSourceSnapshotRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcDataSourceSnapshotRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public DataSourceSnapshot saveSnapshot(DataSourceSnapshot snapshot) {
		snapshot.requireRawEvidenceWritePolicy();
		try {
			insert(snapshot);
			return snapshot;
		} catch (DuplicateKeyException exception) {
			DataSourceSnapshot existing = loadSnapshot(snapshot.snapshotId())
				.orElseThrow(() -> exception);
			if (existing.equals(snapshot)) {
				return snapshot;
			}
			throw new InvalidDataSourceSnapshotException("LOCKED source snapshot is append-only; create a new snapshot instead.");
		}
	}

	public Optional<DataSourceSnapshot> loadSnapshot(String snapshotId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject("""
				SELECT snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
					raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
					snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
					credential_redacted, previous_snapshot_id, diff_summary, freshness_expires_at,
					raw_retention_expires_at
				FROM data_source_snapshots
				WHERE snapshot_id = ?
				""", this::mapSnapshot, snapshotId));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	public List<DataSourceSnapshot> listRecentSnapshots(int limit, int offset) {
		return jdbcTemplate.query("""
			SELECT snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, freshness_expires_at,
				raw_retention_expires_at
			FROM data_source_snapshots
			ORDER BY retrieved_at DESC, snapshot_id ASC
			LIMIT ? OFFSET ?
			""", this::mapSnapshot, limit, offset);
	}

	public Optional<SourceSnapshotEventRow> findEventByIdempotencyKey(String sourceId, String idempotencyKey) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject("""
				SELECT id, source_id, snapshot_id, operation_type, operation_status,
					requested_by, reason, idempotency_key, created_at
				FROM datapack_source_snapshot_events
				WHERE source_id = ? AND idempotency_key = ?
				""", this::mapEvent, sourceId, idempotencyKey));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	public void insertEvent(
		String id,
		String sourceId,
		String snapshotId,
		String operationType,
		String operationStatus,
		String requestedBy,
		String reason,
		String idempotencyKey,
		LocalDateTime createdAt
	) {
		jdbcTemplate.update("""
			INSERT INTO datapack_source_snapshot_events (
				id, source_id, snapshot_id, operation_type, operation_status,
				requested_by, reason, idempotency_key, created_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			id,
			sourceId,
			snapshotId,
			operationType,
			operationStatus,
			requestedBy,
			reason,
			idempotencyKey,
			createdAt
		);
	}

	private void insert(DataSourceSnapshot snapshot) {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, freshness_expires_at,
				raw_retention_expires_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			snapshot.snapshotId(),
			snapshot.sourceId(),
			snapshot.provider(),
			snapshot.retrievedAt(),
			snapshot.sourceUpdatedAt(),
			snapshot.rowCount(),
			snapshot.rawSha256(),
			snapshot.rawObjectUri(),
			snapshot.redactedRequestFingerprint(),
			snapshot.schemaFingerprint(),
			snapshot.snapshotStatus(),
			snapshot.schemaStatus(),
			snapshot.licenseStatus(),
			snapshot.fetchStatus(),
			snapshot.redistributionAllowed(),
			snapshot.credentialRedacted(),
			snapshot.previousSnapshotId(),
			snapshot.diffSummary(),
			snapshot.freshnessExpiresAt(),
			snapshot.rawRetentionExpiresAt()
		);
	}

	private DataSourceSnapshot mapSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
		var sourceUpdatedAt = resultSet.getTimestamp("source_updated_at");
		return new DataSourceSnapshot(
			resultSet.getString("snapshot_id"),
			resultSet.getString("source_id"),
			resultSet.getString("provider"),
			resultSet.getTimestamp("retrieved_at").toLocalDateTime(),
			sourceUpdatedAt == null ? null : sourceUpdatedAt.toLocalDateTime(),
			resultSet.getInt("row_count"),
			resultSet.getString("raw_sha256"),
			resultSet.getString("raw_object_uri"),
			resultSet.getString("redacted_request_fingerprint"),
			resultSet.getString("schema_fingerprint"),
			resultSet.getString("snapshot_status"),
			resultSet.getString("schema_status"),
			resultSet.getString("license_status"),
			resultSet.getString("fetch_status"),
			resultSet.getBoolean("redistribution_allowed"),
			resultSet.getBoolean("credential_redacted"),
			resultSet.getString("previous_snapshot_id"),
			resultSet.getString("diff_summary"),
			resultSet.getTimestamp("freshness_expires_at").toLocalDateTime(),
			resultSet.getTimestamp("raw_retention_expires_at").toLocalDateTime()
		);
	}

	private SourceSnapshotEventRow mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
		return new SourceSnapshotEventRow(
			resultSet.getString("id"),
			resultSet.getString("source_id"),
			resultSet.getString("snapshot_id"),
			resultSet.getString("operation_type"),
			resultSet.getString("operation_status"),
			resultSet.getString("requested_by"),
			resultSet.getString("reason"),
			resultSet.getString("idempotency_key"),
			resultSet.getTimestamp("created_at").toLocalDateTime()
		);
	}

	public record SourceSnapshotEventRow(
		String id,
		String sourceId,
		String snapshotId,
		String operationType,
		String operationStatus,
		String requestedBy,
		String reason,
		String idempotencyKey,
		LocalDateTime createdAt
	) {
	}
}
