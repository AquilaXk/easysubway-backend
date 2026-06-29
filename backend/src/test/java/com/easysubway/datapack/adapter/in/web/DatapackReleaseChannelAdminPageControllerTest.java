package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터팩 release channel 화면")
class DatapackReleaseChannelAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_channel_events");
		jdbcTemplate.update("DELETE FROM datapack_release_channels");
		jdbcTemplate.update("DELETE FROM datapack_release_evidence_bundles");
		jdbcTemplate.update("DELETE FROM datapack_candidate_inputs");
		jdbcTemplate.update("DELETE FROM datapack_candidates");
		insertCandidate("candidate-stable-2", "2026.06.28-stable.2");
		insertCandidate("candidate-stable-3", "2026.06.29-stable.3");
		insertChannel();
		insertEvent();
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 release channel과 rollback 상태를 확인한다")
	void datapackReadAdminViewsReleaseChannels() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/release-channels/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("Release Channels")
			.contains("production")
			.contains("candidate-stable-3")
			.contains("2026.06.29-stable.3")
			.contains("candidate-stable-2")
			.contains("rollback 가능")
			.contains("https://datapack.example.com/production/current.json")
			.contains("PROMOTE")
			.contains("data-operator")
			.contains("release-approver")
			.contains("approval-1128")
			.contains("idempotency-production-1128")
			.contains("https://github.com/AquilaXk/easysubway/actions/runs/1128")
			.doesNotContain("name=\"commandToken\"")
			.doesNotContain("rollback 실행")
			.doesNotContain("production 승인")
			.doesNotContain("serviceKey")
			.doesNotContain("OBJECT_STORAGE_SECRET");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 release channel 화면에 접근할 수 없다")
	void releaseChannelPageRequiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get("/admin/datapack/release-channels/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
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
				'PASS', 'PROMOTED', '2026-06-29 03:00:00')
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

	private void insertChannel() {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_channels (
				channel, candidate_id, manifest_url, manifest_sha256,
				previous_stable_candidate_id, previous_manifest_sha256,
				rollback_available, last_operation_type, last_operation_status,
				requested_by, approved_by, reason, idempotency_key, updated_at
			)
			VALUES ('production', 'candidate-stable-3',
				'https://datapack.example.com/production/current.json', ?,
				'candidate-stable-2', ?, TRUE, 'PROMOTE', 'PASS',
				'data-operator', 'release-approver', 'approval-1128',
				'idempotency-production-1128', '2026-06-29 03:10:00')
			""",
			"1".repeat(64),
			"2".repeat(64)
		);
	}

	private void insertEvent() {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_channel_events (
				id, channel, previous_candidate_id, next_candidate_id,
				previous_manifest_sha256, next_manifest_sha256, operation_type,
				operation_status, requested_by, approved_by, reason,
				idempotency_key, workflow_run_url, created_at
			)
			VALUES ('event-production-1', 'production', 'candidate-stable-2',
				'candidate-stable-3', ?, ?, 'PROMOTE', 'PASS',
				'data-operator', 'release-approver', 'approval-1128',
				'idempotency-production-1128',
				'https://github.com/AquilaXk/easysubway/actions/runs/1128',
				'2026-06-29 03:10:00')
			""",
			"2".repeat(64),
			"1".repeat(64)
		);
	}
}
