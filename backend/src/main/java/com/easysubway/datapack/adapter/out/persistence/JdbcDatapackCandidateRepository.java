package com.easysubway.datapack.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDatapackCandidateRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcDatapackCandidateRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	public List<CandidateRow> listRecentCandidates(int limit) {
		return jdbcTemplate.query("""
			SELECT id, scope_id, artifact_kind, version, source_snapshot_set_hash,
				override_set_hash, build_spec_sha256, source_inventory_sha256,
				sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
				validator_status, route_regression_status, android_evidence_status,
				approval_status, created_at
			FROM datapack_candidates
			ORDER BY created_at DESC, id ASC
			LIMIT ?
			""", this::mapCandidate, limit);
	}

	public Optional<CandidateRow> findCandidate(String candidateId) {
		return jdbcTemplate.query("""
			SELECT id, scope_id, artifact_kind, version, source_snapshot_set_hash,
				override_set_hash, build_spec_sha256, source_inventory_sha256,
				sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
				validator_status, route_regression_status, android_evidence_status,
				approval_status, created_at
			FROM datapack_candidates
			WHERE id = ?
			""", this::mapCandidate, candidateId).stream().findFirst();
	}

	public Optional<CandidateInputRow> findInput(String candidateId) {
		return jdbcTemplate.query("""
			SELECT id, candidate_id, source_snapshot_ids, approved_alias_ledger_hash,
				facility_evidence_ledger_hash, route_evidence_ledger_hash,
				approved_override_set_hash, created_at
			FROM datapack_candidate_inputs
			WHERE candidate_id = ?
			""", this::mapInput, candidateId).stream().findFirst();
	}

	public Optional<EvidenceBundleRow> findEvidenceBundle(String candidateId) {
		return jdbcTemplate.query("""
			SELECT id, candidate_id, evidence_bundle_sha256, workflow_run_url,
				validator_status, route_regression_status, manifest_signature_status,
				android_evidence_status, created_at
			FROM datapack_release_evidence_bundles
			WHERE candidate_id = ?
			""", this::mapEvidenceBundle, candidateId).stream().findFirst();
	}

	public boolean candidateReferencedByReleaseChannel(String candidateId) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM datapack_release_channels
			WHERE candidate_id = ? OR previous_stable_candidate_id = ?
			""", Integer.class, candidateId, candidateId);
		return count != null && count > 0;
	}

	public int rerunGates(String candidateId) {
		int updated = jdbcTemplate.update("""
			UPDATE datapack_candidates
			SET coverage_status = 'PENDING',
				validator_status = 'PENDING',
				route_regression_status = 'PENDING',
				android_evidence_status = 'PENDING',
				approval_status = 'DRAFT'
			WHERE id = ?
			""", candidateId);
		if (updated == 1) {
			jdbcTemplate.update("DELETE FROM datapack_release_evidence_bundles WHERE candidate_id = ?", candidateId);
		}
		return updated;
	}

	private CandidateRow mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CandidateRow(
			resultSet.getString("id"),
			resultSet.getString("scope_id"),
			resultSet.getString("artifact_kind"),
			resultSet.getString("version"),
			resultSet.getString("source_snapshot_set_hash"),
			resultSet.getString("override_set_hash"),
			resultSet.getString("build_spec_sha256"),
			resultSet.getString("source_inventory_sha256"),
			resultSet.getString("sqlite_sha256"),
			resultSet.getString("gzip_sha256"),
			resultSet.getString("manifest_sha256"),
			resultSet.getString("coverage_status"),
			resultSet.getString("validator_status"),
			resultSet.getString("route_regression_status"),
			resultSet.getString("android_evidence_status"),
			resultSet.getString("approval_status"),
			toLocalDateTime(resultSet, "created_at")
		);
	}

	private CandidateInputRow mapInput(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CandidateInputRow(
			resultSet.getString("id"),
			resultSet.getString("candidate_id"),
			resultSet.getString("source_snapshot_ids"),
			resultSet.getString("approved_alias_ledger_hash"),
			resultSet.getString("facility_evidence_ledger_hash"),
			resultSet.getString("route_evidence_ledger_hash"),
			resultSet.getString("approved_override_set_hash"),
			toLocalDateTime(resultSet, "created_at")
		);
	}

	private EvidenceBundleRow mapEvidenceBundle(ResultSet resultSet, int rowNumber) throws SQLException {
		return new EvidenceBundleRow(
			resultSet.getString("id"),
			resultSet.getString("candidate_id"),
			resultSet.getString("evidence_bundle_sha256"),
			resultSet.getString("workflow_run_url"),
			resultSet.getString("validator_status"),
			resultSet.getString("route_regression_status"),
			resultSet.getString("manifest_signature_status"),
			resultSet.getString("android_evidence_status"),
			toLocalDateTime(resultSet, "created_at")
		);
	}

	private LocalDateTime toLocalDateTime(ResultSet resultSet, String column) throws SQLException {
		var timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	public record CandidateRow(
		String id,
		String scopeId,
		String artifactKind,
		String version,
		String sourceSnapshotSetHash,
		String overrideSetHash,
		String buildSpecSha256,
		String sourceInventorySha256,
		String sqliteSha256,
		String gzipSha256,
		String manifestSha256,
		String coverageStatus,
		String validatorStatus,
		String routeRegressionStatus,
		String androidEvidenceStatus,
		String approvalStatus,
		LocalDateTime createdAt
	) {
	}

	public record CandidateInputRow(
		String id,
		String candidateId,
		String sourceSnapshotIds,
		String approvedAliasLedgerHash,
		String facilityEvidenceLedgerHash,
		String routeEvidenceLedgerHash,
		String approvedOverrideSetHash,
		LocalDateTime createdAt
	) {
	}

	public record EvidenceBundleRow(
		String id,
		String candidateId,
		String evidenceBundleSha256,
		String workflowRunUrl,
		String validatorStatus,
		String routeRegressionStatus,
		String manifestSignatureStatus,
		String androidEvidenceStatus,
		LocalDateTime createdAt
	) {
	}
}
