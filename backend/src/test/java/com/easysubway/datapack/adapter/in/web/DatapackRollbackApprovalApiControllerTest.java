package com.easysubway.datapack.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "easysubway.datapack.workflow-token=test-workflow-token")
@DisplayName("rollback 승인 조회 API")
class DatapackRollbackApprovalApiControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_channel_events");
		jdbcTemplate.update("DELETE FROM datapack_release_channels");
		jdbcTemplate.update("DELETE FROM datapack_candidate_inputs");
		jdbcTemplate.update("DELETE FROM datapack_candidates");
		insertCandidate("failed-candidate", "1".repeat(64));
		insertCandidate("known-good-candidate", "2".repeat(64));
		jdbcTemplate.update("""
			INSERT INTO datapack_release_channels (
				channel, candidate_id, manifest_url, manifest_sha256,
				previous_stable_candidate_id, previous_manifest_sha256,
				rollback_available, last_operation_type, last_operation_status,
				requested_by, approved_by, reason, idempotency_key, updated_at
			) VALUES ('production', 'known-good-candidate', 'https://example.com/catalog/current.json', ?,
				'failed-candidate', ?, TRUE, 'ROLLBACK', 'PASS', 'operator', 'approver',
				'incident-1', 'rollback-1', '2026-07-15 01:00:00')
			""", "2".repeat(64), "1".repeat(64));
	}

	@Test
	@DisplayName("Bearer token으로 PASS rollback event의 승인 identity를 조회한다")
	void servesTrustedRollbackApproval() throws Exception {
		insertEvent("rollback-event-1", "ROLLBACK", "PASS");

		mockMvc.perform(get("/admin/api/datapack/rollback-approvals/{id}", "rollback-event-1")
				.header("Authorization", "Bearer test-workflow-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schemaVersion").value(1))
			.andExpect(jsonPath("$.artifactKind").value("datapack-rollback-approval"))
			.andExpect(jsonPath("$.rollbackApprovalEventId").value("rollback-event-1"))
			.andExpect(jsonPath("$.targetChannel").value("production"))
			.andExpect(jsonPath("$.failedManifestSha256").value("1".repeat(64)))
			.andExpect(jsonPath("$.knownGoodManifestSha256").value("2".repeat(64)))
			.andExpect(jsonPath("$.approvedByRole").value("admin.datapack.rollback"))
			.andExpect(jsonPath("$.approvedAt").value(startsWith("2026-07-15T01:00:00")))
			.andExpect(jsonPath("$.reasonCode").value("ADMIN_APPROVED_ROLLBACK"));
	}

	@Test
	@DisplayName("PROMOTE event는 rollback 승인으로 노출하지 않는다")
	void rejectsNonRollbackApproval() throws Exception {
		insertEvent("promote-event", "PROMOTE", "PASS");
		mockMvc.perform(get("/admin/api/datapack/rollback-approvals/{id}", "promote-event")
				.header("Authorization", "Bearer test-workflow-token"))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("workflow token 없이는 승인 evidence를 읽을 수 없다")
	void requiresWorkflowToken() throws Exception {
		insertEvent("rollback-event-1", "ROLLBACK", "PASS");
		mockMvc.perform(get("/admin/api/datapack/rollback-approvals/{id}", "rollback-event-1"))
			.andExpect(status().is4xxClientError());
	}

	private void insertCandidate(String id, String manifestSha256) {
		jdbcTemplate.update("""
			INSERT INTO datapack_candidates (
				id, scope_id, artifact_kind, version, source_snapshot_set_hash,
				override_set_hash, build_spec_sha256, source_inventory_sha256,
				sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
				validator_status, route_regression_status, android_evidence_status,
				approval_status, created_at
			) VALUES (?, 'capital_pilot_android_v1', 'DATAPACK', '1', ?, ?, ?, ?, ?, ?, ?,
				'PASS', 'PASS', 'PASS', 'PASS', 'PROMOTED', '2026-07-15 00:00:00')
			""", id, "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
			"e".repeat(64), "f".repeat(64), manifestSha256);
	}

	private void insertEvent(String id, String operationType, String status) {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_channel_events (
				id, channel, previous_candidate_id, next_candidate_id,
				previous_manifest_sha256, next_manifest_sha256, operation_type,
				operation_status, requested_by, approved_by, reason,
				idempotency_key, workflow_run_url, created_at
			) VALUES (?, 'production', 'failed-candidate', 'known-good-candidate', ?, ?, ?, ?,
				'operator', 'approver', 'incident-1', ?, 'https://github.com/run/1', '2026-07-15 01:00:00')
			""", id, "1".repeat(64), "2".repeat(64), operationType, status, id);
	}
}
