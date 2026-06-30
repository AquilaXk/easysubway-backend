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
public class JdbcManualOverrideLedgerRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcManualOverrideLedgerRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	public List<ManualOverrideRow> listRecentOverrides(int limit) {
		return jdbcTemplate.query("""
			SELECT id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by,
				approved_by, approved_at, route_safety_approved_by, approval_status,
				conflict_status, strict_route_eligible, effective_from, expires_at,
				superseded_by, created_at
			FROM manual_overrides
			ORDER BY entity_type ASC, entity_id ASC, approval_status ASC, created_at DESC, id ASC
			LIMIT ?
			""", this::mapRow, limit);
	}

	public void insertRequest(
		String id,
		String entityType,
		String entityId,
		String fieldName,
		String beforeValue,
		String afterValue,
		String reasonCode,
		String reason,
		String evidenceUri,
		String evidenceHash,
		String requestedBy,
		boolean strictRouteEligible,
		LocalDateTime effectiveFrom,
		LocalDateTime expiresAt,
		LocalDateTime createdAt
	) {
		jdbcTemplate.update("""
			INSERT INTO manual_overrides (
				id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by,
				approved_by, approved_at, route_safety_approved_by, approval_status,
				conflict_status, strict_route_eligible, effective_from, expires_at,
				superseded_by, created_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL,
				'PENDING', 'NONE', ?, ?, ?, NULL, ?)
			""",
			id,
			entityType,
			entityId,
			fieldName,
			beforeValue,
			afterValue,
			reasonCode,
			reason,
			evidenceUri,
			evidenceHash,
			requestedBy,
			strictRouteEligible,
			effectiveFrom,
			expiresAt,
			createdAt
		);
	}

	public int approve(String id, String approvedBy, LocalDateTime approvedAt) {
		return jdbcTemplate.update("""
			UPDATE manual_overrides
			SET approval_status = 'APPROVED',
				approved_by = ?,
				approved_at = ?,
				route_safety_approved_by = CASE
					WHEN strict_route_eligible THEN ?
					ELSE route_safety_approved_by
				END
			WHERE id = ?
				AND approval_status = 'PENDING'
				AND requested_by <> ?
				AND conflict_status <> 'UNRESOLVED'
			""", approvedBy, approvedAt, approvedBy, id, approvedBy);
	}

	public int expire(String id) {
		return jdbcTemplate.update("""
			UPDATE manual_overrides
			SET approval_status = 'EXPIRED'
			WHERE id = ? AND approval_status IN ('PENDING', 'APPROVED')
			""", id);
	}

	private ManualOverrideRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ManualOverrideRow(
			resultSet.getString("id"),
			resultSet.getString("entity_type"),
			resultSet.getString("entity_id"),
			resultSet.getString("field_name"),
			resultSet.getString("before_value"),
			resultSet.getString("after_value"),
			resultSet.getString("reason_code"),
			resultSet.getString("reason"),
			resultSet.getString("evidence_uri"),
			resultSet.getString("evidence_hash"),
			resultSet.getString("requested_by"),
			resultSet.getString("approved_by"),
			toLocalDateTime(resultSet, "approved_at"),
			resultSet.getString("route_safety_approved_by"),
			resultSet.getString("approval_status"),
			resultSet.getString("conflict_status"),
			resultSet.getBoolean("strict_route_eligible"),
			toLocalDateTime(resultSet, "effective_from"),
			toLocalDateTime(resultSet, "expires_at"),
			resultSet.getString("superseded_by"),
			toLocalDateTime(resultSet, "created_at")
		);
	}

	private LocalDateTime toLocalDateTime(ResultSet resultSet, String column) throws SQLException {
		var timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	public record ManualOverrideRow(
		String id,
		String entityType,
		String entityId,
		String fieldName,
		String beforeValue,
		String afterValue,
		String reasonCode,
		String reason,
		String evidenceUri,
		String evidenceHash,
		String requestedBy,
		String approvedBy,
		LocalDateTime approvedAt,
		String routeSafetyApprovedBy,
		String approvalStatus,
		String conflictStatus,
		boolean strictRouteEligible,
		LocalDateTime effectiveFrom,
		LocalDateTime expiresAt,
		String supersededBy,
		LocalDateTime createdAt
	) {
	}
}
