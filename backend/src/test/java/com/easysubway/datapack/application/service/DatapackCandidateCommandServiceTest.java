package com.easysubway.datapack.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.datapack.application.service.DatapackCandidateCommandService.CandidateGateRerunCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("데이터팩 candidate command service")
class DatapackCandidateCommandServiceTest {

	@Autowired
	private DatapackCandidateCommandService service;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_channel_events");
		jdbcTemplate.update("DELETE FROM datapack_release_channels");
		jdbcTemplate.update("DELETE FROM datapack_release_evidence_bundles");
		jdbcTemplate.update("DELETE FROM datapack_candidate_inputs");
		jdbcTemplate.update("DELETE FROM datapack_candidates");
		insertCandidate("candidate-active-1", "2026.06.30-active.1");
		insertCandidate("candidate-previous-1", "2026.06.29-previous.1");
		insertEvidenceBundle("candidate-active-1");
		insertReleaseChannel();
	}

	@Test
	@DisplayName("release channel이 참조 중인 candidate는 gate 재실행을 거절한다")
	void rerunRejectsCandidateReferencedByReleaseChannel() {
		assertThatThrownBy(() -> service.rerunGates(
			"candidate-active-1",
			new CandidateGateRerunCommand("rerun active release", "candidate-rerun-active-1")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("candidate is referenced by release channel");

		assertThat(candidateValue("candidate-active-1", "approval_status")).isEqualTo("PROMOTED");
		assertThat(candidateValue("candidate-active-1", "validator_status")).isEqualTo("PASS");
		assertThat(evidenceBundleCount("candidate-active-1")).isEqualTo(1);
	}

	private void insertCandidate(String id, String version) {
		jdbcTemplate.update("""
			INSERT INTO datapack_candidates (
				id, scope_id, artifact_kind, version, source_snapshot_set_hash,
				override_set_hash, build_spec_sha256, source_inventory_sha256,
				sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
				validator_status, route_regression_status, android_evidence_status,
				approval_status, created_at
			)
			VALUES (?, 'capital_pilot_android_v1', 'DATAPACK',
				?, ?, ?, ?, ?, ?, ?, ?, 'PASS', 'PASS', 'PASS',
				'PASS', 'PROMOTED', '2026-06-30 03:00:00')
			""",
			id,
			version,
			"a".repeat(64),
			"b".repeat(64),
			"c".repeat(64),
			"d".repeat(64),
			"e".repeat(64),
			"f".repeat(64),
			"0".repeat(64)
		);
	}

	private void insertEvidenceBundle(String candidateId) {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_evidence_bundles (
				id, candidate_id, evidence_bundle_sha256, workflow_run_url,
				validator_status, route_regression_status, manifest_signature_status,
				android_evidence_status, created_at
			)
			VALUES (?, ?, ?, 'https://github.com/AquilaXk/easysubway/actions/runs/1162',
				'PASS', 'PASS', 'PASS', 'PASS', '2026-06-30 03:20:00')
			""",
			"evidence-" + candidateId,
			candidateId,
			"5".repeat(64)
		);
	}

	private void insertReleaseChannel() {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_channels (
				channel, candidate_id, manifest_url, manifest_sha256,
				previous_stable_candidate_id, previous_manifest_sha256,
				rollback_available, last_operation_type, last_operation_status,
				requested_by, approved_by, reason, idempotency_key, updated_at
			)
			VALUES ('production', 'candidate-active-1',
				'https://datapack.example.com/production/current.json', ?,
				'candidate-previous-1', ?, TRUE, 'PROMOTE', 'PASS',
				'data-operator', 'release-approver', 'approval-1162',
				'idempotency-production-1162', '2026-06-30 03:30:00')
			""",
			"0".repeat(64),
			"0".repeat(64)
		);
	}

	private String candidateValue(String candidateId, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM datapack_candidates WHERE id = ?",
			String.class,
			candidateId
		);
	}

	private Integer evidenceBundleCount(String candidateId) {
		return jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM datapack_release_evidence_bundles
			WHERE candidate_id = ?
			""", Integer.class, candidateId);
	}
}
