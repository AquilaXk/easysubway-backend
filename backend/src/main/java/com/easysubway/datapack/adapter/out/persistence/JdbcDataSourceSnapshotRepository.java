package com.easysubway.datapack.adapter.out.persistence;

import com.easysubway.datapack.domain.DataSourceSnapshot;
import com.easysubway.datapack.domain.InvalidDataSourceSnapshotException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDataSourceSnapshotRepository {

	private final JdbcTemplate jdbcTemplate;
	private final DatabaseDialect databaseDialect;

	@Autowired
	public JdbcDataSourceSnapshotRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcDataSourceSnapshotRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.databaseDialect = detectDatabaseDialect(jdbcTemplate);
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
				SELECT snapshot_id, source_id, provider, retrieved_at, source_updated_at,
					freshness_basis_at, provider_valid_until, row_count, coverage_count,
					raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
					snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
					credential_redacted, previous_snapshot_id, diff_summary, diff_summary_json, freshness_expires_at,
					raw_retention_expires_at, governance_policy_version, governance_policy_sha256
				FROM data_source_snapshots
				WHERE snapshot_id = ?
				""", this::mapSnapshot, snapshotId));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	public void lockSourceLineage(String sourceId) {
		if (databaseDialect == DatabaseDialect.H2) {
			jdbcTemplate.update("MERGE INTO datapack_source_lineage_locks (source_id) KEY (source_id) VALUES (?)", sourceId);
		} else {
			jdbcTemplate.update("""
				INSERT INTO datapack_source_lineage_locks (source_id)
				VALUES (?)
				ON CONFLICT (source_id) DO NOTHING
				""", sourceId);
		}
		jdbcTemplate.queryForObject("""
			SELECT source_id
			FROM datapack_source_lineage_locks
			WHERE source_id = ?
			FOR UPDATE
			""", String.class, sourceId);
	}

	private static DatabaseDialect detectDatabaseDialect(JdbcTemplate jdbcTemplate) {
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

	public Optional<DataSourceSnapshot> findHeadBySourceIdForUpdate(String sourceId) {
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject("""
				SELECT snapshot_id, source_id, provider, retrieved_at, source_updated_at,
					freshness_basis_at, provider_valid_until, row_count, coverage_count,
					raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
					snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
					credential_redacted, previous_snapshot_id, diff_summary, diff_summary_json, freshness_expires_at,
					raw_retention_expires_at, governance_policy_version, governance_policy_sha256
				FROM data_source_snapshots
				WHERE source_id = ?
				ORDER BY retrieved_at DESC, snapshot_id DESC
				LIMIT 1
				FOR UPDATE
				""", this::mapSnapshot, sourceId));
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	public List<DataSourceSnapshot> findChildren(String previousSnapshotId) {
		return jdbcTemplate.query("""
			SELECT snapshot_id, source_id, provider, retrieved_at, source_updated_at,
				freshness_basis_at, provider_valid_until, row_count, coverage_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, diff_summary_json, freshness_expires_at,
				raw_retention_expires_at, governance_policy_version, governance_policy_sha256
			FROM data_source_snapshots
			WHERE previous_snapshot_id = ?
			ORDER BY snapshot_id ASC
			""", this::mapSnapshot, previousSnapshotId);
	}

	public List<DataSourceSnapshot> listRecentSnapshots(int limit, int offset) {
		return jdbcTemplate.query("""
			SELECT snapshot_id, source_id, provider, retrieved_at, source_updated_at,
				freshness_basis_at, provider_valid_until, row_count, coverage_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, diff_summary_json, freshness_expires_at,
				raw_retention_expires_at, governance_policy_version, governance_policy_sha256
			FROM data_source_snapshots
			ORDER BY retrieved_at DESC, snapshot_id ASC
			LIMIT ? OFFSET ?
			""", this::mapSnapshot, limit, offset);
	}

	public List<DataSourceSnapshot> listSnapshotsForAdmin(
		String query,
		String status,
		List<String> sourceSnapshotIds,
		String sort,
		int limit,
		int offset
	) {
		StringBuilder sql = new StringBuilder("""
			SELECT snapshot_id, source_id, provider, retrieved_at, source_updated_at,
				freshness_basis_at, provider_valid_until, row_count, coverage_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, diff_summary_json, freshness_expires_at,
				raw_retention_expires_at, governance_policy_version, governance_policy_sha256
			FROM data_source_snapshots
			""");
		List<String> predicates = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		addSnapshotIdPredicate(predicates, params, sourceSnapshotIds);
		addQueryPredicate(predicates, params, query);
		addStatusPredicate(predicates, params, status);
		if (!predicates.isEmpty()) {
			sql.append(" WHERE ").append(String.join(" AND ", predicates)).append("\n");
		}
		sql.append(orderBy(sort));
		sql.append(" LIMIT ? OFFSET ?");
		params.add(limit);
		params.add(offset);
		return jdbcTemplate.query(sql.toString(), this::mapSnapshot, params.toArray());
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
				snapshot_id, source_id, provider, retrieved_at, source_updated_at,
				freshness_basis_at, provider_valid_until, row_count, coverage_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, diff_summary_json, freshness_expires_at,
				raw_retention_expires_at, governance_policy_version, governance_policy_sha256
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			snapshot.snapshotId(),
			snapshot.sourceId(),
			snapshot.provider(),
			snapshot.retrievedAt(),
			snapshot.sourceUpdatedAt(),
			snapshot.freshnessBasisAt(),
			snapshot.providerValidUntil(),
			snapshot.rowCount(),
			snapshot.coverageCount(),
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
			jsonParameter(snapshot.diffSummaryJson()),
			snapshot.freshnessExpiresAt(),
			snapshot.rawRetentionExpiresAt(),
			snapshot.governancePolicyVersion(),
			snapshot.governancePolicySha256()
		);
	}

	private Object jsonParameter(String value) {
		return databaseDialect == DatabaseDialect.POSTGRESQL
			? new SqlParameterValue(Types.OTHER, value)
			: value;
	}

	private DataSourceSnapshot mapSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
		var sourceUpdatedAt = resultSet.getTimestamp("source_updated_at");
		var freshnessBasisAt = resultSet.getTimestamp("freshness_basis_at");
		var providerValidUntil = resultSet.getTimestamp("provider_valid_until");
		return new DataSourceSnapshot(
			resultSet.getString("snapshot_id"),
			resultSet.getString("source_id"),
			resultSet.getString("provider"),
			resultSet.getTimestamp("retrieved_at").toLocalDateTime(),
			sourceUpdatedAt == null ? null : sourceUpdatedAt.toLocalDateTime(),
			freshnessBasisAt == null ? null : freshnessBasisAt.toLocalDateTime(),
			providerValidUntil == null ? null : providerValidUntil.toLocalDateTime(),
			resultSet.getInt("row_count"),
			resultSet.getInt("coverage_count"),
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
			resultSet.getString("diff_summary_json"),
			resultSet.getTimestamp("freshness_expires_at").toLocalDateTime(),
			resultSet.getTimestamp("raw_retention_expires_at").toLocalDateTime(),
			resultSet.getString("governance_policy_version"),
			resultSet.getString("governance_policy_sha256")
		);
	}

	private static void addSnapshotIdPredicate(
		List<String> predicates,
		List<Object> params,
		List<String> sourceSnapshotIds
	) {
		if (sourceSnapshotIds == null || sourceSnapshotIds.isEmpty()) {
			return;
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(sourceSnapshotIds.size(), "?"));
		predicates.add("snapshot_id IN (" + placeholders + ")");
		params.addAll(sourceSnapshotIds);
	}

	private static void addQueryPredicate(List<String> predicates, List<Object> params, String query) {
		if (!hasText(query)) {
			return;
		}
		predicates.add("""
			(LOWER(snapshot_id) LIKE ?
				OR LOWER(source_id) LIKE ?
				OR LOWER(provider) LIKE ?
				OR LOWER(snapshot_status) LIKE ?
				OR LOWER(schema_status) LIKE ?
				OR LOWER(license_status) LIKE ?
				OR LOWER(fetch_status) LIKE ?
				OR LOWER(COALESCE(diff_summary, '')) LIKE ?)
			""");
		String needle = "%" + query.toLowerCase(java.util.Locale.ROOT) + "%";
		for (int index = 0; index < 8; index++) {
			params.add(needle);
		}
	}

	private static void addStatusPredicate(List<String> predicates, List<Object> params, String status) {
		if (!hasText(status) || "ALL".equals(status)) {
			return;
		}
		if ("BLOCKER".equals(status)) {
			predicates.add("""
				NOT (
					snapshot_status = 'LOCKED'
					AND schema_status = 'PASS'
					AND license_status = 'PASS'
					AND fetch_status = 'SUCCESS'
					AND redistribution_allowed = TRUE
					AND credential_redacted = TRUE
				)
				""");
			return;
		}
		predicates.add("(snapshot_status = ? OR schema_status = ? OR license_status = ? OR fetch_status = ?)");
		for (int index = 0; index < 4; index++) {
			params.add(status);
		}
	}

	private static String orderBy(String sort) {
		return switch (sort == null ? "" : sort) {
			case "source" -> " ORDER BY source_id ASC, retrieved_at DESC, snapshot_id ASC\n";
			case "retrieved_asc" -> " ORDER BY retrieved_at ASC, snapshot_id ASC\n";
			default -> " ORDER BY retrieved_at DESC, snapshot_id ASC\n";
		};
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
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
