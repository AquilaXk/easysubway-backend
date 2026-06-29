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
public class JdbcRouteGateMatrixRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcRouteGateMatrixRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	public List<RouteGateRow> listRecentEdges(int limit) {
		return jdbcTemplate.query("""
			SELECT id, station_id, line_id, edge_id, edge_type, source_id,
				source_snapshot_id, provenance_kind, verification_status, last_verified_at,
				evidence_hash, strict_route_eligible, blocker_reason, created_at
			FROM route_edge_evidence
			ORDER BY station_id ASC, line_id ASC, edge_type ASC, verification_status ASC,
				created_at DESC, id ASC
			LIMIT ?
			""", this::mapRow, limit);
	}

	private RouteGateRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RouteGateRow(
			resultSet.getString("id"),
			resultSet.getString("station_id"),
			resultSet.getString("line_id"),
			resultSet.getString("edge_id"),
			resultSet.getString("edge_type"),
			resultSet.getString("source_id"),
			resultSet.getString("source_snapshot_id"),
			resultSet.getString("provenance_kind"),
			resultSet.getString("verification_status"),
			toLocalDateTime(resultSet, "last_verified_at"),
			resultSet.getString("evidence_hash"),
			resultSet.getBoolean("strict_route_eligible"),
			resultSet.getString("blocker_reason"),
			toLocalDateTime(resultSet, "created_at")
		);
	}

	private LocalDateTime toLocalDateTime(ResultSet resultSet, String column) throws SQLException {
		var timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	public record RouteGateRow(
		String id,
		String stationId,
		String lineId,
		String edgeId,
		String edgeType,
		String sourceId,
		String sourceSnapshotId,
		String provenanceKind,
		String verificationStatus,
		LocalDateTime lastVerifiedAt,
		String evidenceHash,
		boolean strictRouteEligible,
		String blockerReason,
		LocalDateTime createdAt
	) {
	}
}
