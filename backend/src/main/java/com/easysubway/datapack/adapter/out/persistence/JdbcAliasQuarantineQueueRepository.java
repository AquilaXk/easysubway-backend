package com.easysubway.datapack.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAliasQuarantineQueueRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcAliasQuarantineQueueRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	public List<AliasApprovalRow> listRecentAliasApprovals(int limit) {
		return jdbcTemplate.query("""
			SELECT id, source_id, source_snapshot_id, provider_entity_type, provider_entity_id,
				canonical_entity_type, canonical_entity_id, confidence, match_method,
				approval_status, requested_by, approved_by, approved_at, evidence_hash,
				superseded_by, created_at
			FROM external_alias_approvals
			ORDER BY
				CASE approval_status WHEN 'PENDING' THEN 0 ELSE 1 END,
				created_at DESC,
				id ASC
			LIMIT ?
			""", this::mapAliasApproval, limit);
	}

	public List<QuarantineRow> listRecentQuarantineRecords(int limit) {
		return jdbcTemplate.query("""
			SELECT q.id, q.source_id, q.source_snapshot_id, q.provider_record_hash,
				q.reason_code, q.severity, q.redacted_excerpt, q.resolution_status,
				q.resolved_by, q.resolved_at, q.created_at,
				r.resolution_status AS latest_resolution_status,
				r.canonical_entity_type AS latest_canonical_entity_type,
				r.canonical_entity_id AS latest_canonical_entity_id
			FROM source_quarantine_records q
			LEFT JOIN source_quarantine_resolutions r
				ON r.id = (
					SELECT r2.id
					FROM source_quarantine_resolutions r2
					WHERE r2.quarantine_record_id = q.id
					ORDER BY r2.resolved_at DESC, r2.id DESC
					LIMIT 1
				)
			ORDER BY
				CASE q.resolution_status WHEN 'OPEN' THEN 0 ELSE 1 END,
				q.created_at DESC,
				q.id ASC
			LIMIT ?
			""", this::mapQuarantine, limit);
	}

	public int reviewAlias(String aliasId, String approvalStatus, String reviewedBy, LocalDateTime reviewedAt) {
		return jdbcTemplate.update("""
			UPDATE external_alias_approvals
			SET approval_status = ?,
				approved_by = ?,
				approved_at = ?
			WHERE id = ? AND approval_status = 'PENDING'
			""", approvalStatus, reviewedBy, reviewedAt, aliasId);
	}

	public int resolveQuarantine(String recordId, String resolvedBy, LocalDateTime resolvedAt) {
		return jdbcTemplate.update("""
			UPDATE source_quarantine_records
			SET resolution_status = 'RESOLVED',
				resolved_by = ?,
				resolved_at = ?
			WHERE id = ? AND resolution_status = 'OPEN'
			""", resolvedBy, resolvedAt, recordId);
	}

	public void insertQuarantineResolution(
		String id,
		String recordId,
		String resolutionStatus,
		String resolutionReason,
		String resolvedBy,
		LocalDateTime resolvedAt,
		String canonicalEntityType,
		String canonicalEntityId,
		String evidenceHash
	) {
		jdbcTemplate.update("""
			INSERT INTO source_quarantine_resolutions (
				id, quarantine_record_id, resolution_status, resolution_reason,
				resolved_by, resolved_at, canonical_entity_type, canonical_entity_id,
				evidence_hash
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			id,
			recordId,
			resolutionStatus,
			resolutionReason,
			resolvedBy,
			resolvedAt,
			canonicalEntityType,
			canonicalEntityId,
			evidenceHash
		);
	}

	private AliasApprovalRow mapAliasApproval(ResultSet resultSet, int rowNumber) throws SQLException {
		return new AliasApprovalRow(
			resultSet.getString("id"),
			resultSet.getString("source_id"),
			resultSet.getString("source_snapshot_id"),
			resultSet.getString("provider_entity_type"),
			resultSet.getString("provider_entity_id"),
			resultSet.getString("canonical_entity_type"),
			resultSet.getString("canonical_entity_id"),
			resultSet.getInt("confidence"),
			resultSet.getString("match_method"),
			resultSet.getString("approval_status"),
			resultSet.getString("requested_by"),
			resultSet.getString("approved_by"),
			toLocalDateTime(resultSet, "approved_at"),
			resultSet.getString("evidence_hash"),
			resultSet.getString("superseded_by"),
			toLocalDateTime(resultSet, "created_at")
		);
	}

	private QuarantineRow mapQuarantine(ResultSet resultSet, int rowNumber) throws SQLException {
		return new QuarantineRow(
			resultSet.getString("id"),
			resultSet.getString("source_id"),
			resultSet.getString("source_snapshot_id"),
			resultSet.getString("provider_record_hash"),
			resultSet.getString("reason_code"),
			resultSet.getString("severity"),
			resultSet.getString("redacted_excerpt"),
			resultSet.getString("resolution_status"),
			resultSet.getString("resolved_by"),
			toLocalDateTime(resultSet, "resolved_at"),
			toLocalDateTime(resultSet, "created_at"),
			resultSet.getString("latest_resolution_status"),
			resultSet.getString("latest_canonical_entity_type"),
			resultSet.getString("latest_canonical_entity_id")
		);
	}

	private LocalDateTime toLocalDateTime(ResultSet resultSet, String column) throws SQLException {
		var timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	public record AliasApprovalRow(
		String id,
		String sourceId,
		String sourceSnapshotId,
		String providerEntityType,
		String providerEntityId,
		String canonicalEntityType,
		String canonicalEntityId,
		int confidence,
		String matchMethod,
		String approvalStatus,
		String requestedBy,
		String approvedBy,
		LocalDateTime approvedAt,
		String evidenceHash,
		String supersededBy,
		LocalDateTime createdAt
	) {
	}

	public record QuarantineRow(
		String id,
		String sourceId,
		String sourceSnapshotId,
		String providerRecordHash,
		String reasonCode,
		String severity,
		String redactedExcerpt,
		String resolutionStatus,
		String resolvedBy,
		LocalDateTime resolvedAt,
		LocalDateTime createdAt,
		String latestResolutionStatus,
		String latestCanonicalEntityType,
		String latestCanonicalEntityId
	) {
	}
}
