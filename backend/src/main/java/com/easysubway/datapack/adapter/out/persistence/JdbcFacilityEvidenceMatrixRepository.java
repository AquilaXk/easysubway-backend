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
public class JdbcFacilityEvidenceMatrixRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcFacilityEvidenceMatrixRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	public List<FacilityEvidenceRow> listRecentEvidence(int limit) {
		return jdbcTemplate.query("""
			SELECT id, station_id, line_id, facility_type, evidence_kind, source_id,
				source_snapshot_id, provider_record_hash, status_meaning,
				installation_status, operational_status, verified_at, retrieved_at,
				freshness_expires_at, confidence, strict_route_eligible,
				strict_route_eligible_reason, conflict_status, manual_override_id,
				created_at
			FROM facility_evidence
			ORDER BY station_id ASC, line_id ASC, facility_type ASC, created_at DESC, id ASC
			LIMIT ?
			""", this::mapRow, limit);
	}

	private FacilityEvidenceRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new FacilityEvidenceRow(
			resultSet.getString("id"),
			resultSet.getString("station_id"),
			resultSet.getString("line_id"),
			resultSet.getString("facility_type"),
			resultSet.getString("evidence_kind"),
			resultSet.getString("source_id"),
			resultSet.getString("source_snapshot_id"),
			resultSet.getString("provider_record_hash"),
			resultSet.getString("status_meaning"),
			resultSet.getString("installation_status"),
			resultSet.getString("operational_status"),
			toLocalDateTime(resultSet, "verified_at"),
			toLocalDateTime(resultSet, "retrieved_at"),
			toLocalDateTime(resultSet, "freshness_expires_at"),
			resultSet.getInt("confidence"),
			resultSet.getBoolean("strict_route_eligible"),
			resultSet.getString("strict_route_eligible_reason"),
			resultSet.getString("conflict_status"),
			resultSet.getString("manual_override_id"),
			toLocalDateTime(resultSet, "created_at")
		);
	}

	private LocalDateTime toLocalDateTime(ResultSet resultSet, String column) throws SQLException {
		var timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	public record FacilityEvidenceRow(
		String id,
		String stationId,
		String lineId,
		String facilityType,
		String evidenceKind,
		String sourceId,
		String sourceSnapshotId,
		String providerRecordHash,
		String statusMeaning,
		String installationStatus,
		String operationalStatus,
		LocalDateTime verifiedAt,
		LocalDateTime retrievedAt,
		LocalDateTime freshnessExpiresAt,
		int confidence,
		boolean strictRouteEligible,
		String strictRouteEligibleReason,
		String conflictStatus,
		String manualOverrideId,
		LocalDateTime createdAt
	) {
	}
}
