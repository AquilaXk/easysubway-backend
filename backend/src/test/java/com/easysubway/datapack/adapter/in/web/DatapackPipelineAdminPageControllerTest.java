package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
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
@DisplayName("관리자 데이터팩 파이프라인 개요 화면")
class DatapackPipelineAdminPageControllerTest {

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
		jdbcTemplate.update("DELETE FROM route_edge_evidence");
		jdbcTemplate.update("DELETE FROM facility_evidence");
		jdbcTemplate.update("DELETE FROM manual_overrides");
		jdbcTemplate.update("DELETE FROM source_quarantine_resolutions");
		jdbcTemplate.update("DELETE FROM source_quarantine_records");
		jdbcTemplate.update("DELETE FROM external_alias_approvals");
		insertCandidate();
		insertCandidateInput();
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 파이프라인 단계 그래프와 드릴다운 링크를 확인한다")
	void datapackReadAdminViewsPipelineStages() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/pipeline/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("데이터팩 파이프라인 개요")
			.contains("파이프라인 단계")
			.contains("원천 스냅샷")
			.contains("후보 게이트")
			.contains("별칭·격리")
			.contains("매니페스트 서명")
			.contains("채널 승격")
			.contains("게이트 6종 + 서명")
			.contains("복사")
			.contains("snapshot-kric-20260629")
			.contains("/admin/datapack/source-snapshots/page?candidateId=candidate-capital-1&amp;sourceSnapshotId=snapshot-kric-20260629,snapshot-route-20260629")
			.contains("/admin/datapack/candidates/page?candidateId=candidate-capital-1&amp;status=BLOCKER")
			.contains("/admin/datapack/alias-quarantine/page?status=BLOCKER")
			.contains("/admin/datapack/facility-evidence/page?status=BLOCKER")
			.contains("/admin/datapack/route-gates/page?status=BLOCKER")
			.contains("/admin/datapack/manual-overrides/page?status=BLOCKER")
			.contains("/admin/datapack/release-channels/page?candidateId=candidate-capital-1")
			.contains("서명·증거")
			.contains("sha 원문 보기");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 파이프라인 화면에 접근할 수 없다")
	void pipelineRequiresDatapackReadAuthority() throws Exception {
		mockMvc.perform(get("/admin/datapack/pipeline/page")
				.with(user("no-datapack").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	private void insertCandidate() {
		jdbcTemplate.update("""
			INSERT INTO datapack_candidates (
				id, scope_id, artifact_kind, version, source_snapshot_set_hash,
				override_set_hash, build_spec_sha256, source_inventory_sha256,
				sqlite_sha256, gzip_sha256, manifest_sha256, coverage_status,
				validator_status, route_regression_status, android_evidence_status,
				approval_status, created_at
			)
			VALUES ('candidate-capital-1', 'capital_pilot_android_v1', 'DATAPACK',
				'2026.06.29-cand.1', ?, ?, ?, ?, ?, ?, ?, 'PASS', 'PASS', 'PASS',
				'PASS', 'READY_FOR_APPROVAL', '2026-06-29 03:00:00')
			""",
			"a".repeat(64),
			"b".repeat(64),
			"c".repeat(64),
			"d".repeat(64),
			"e".repeat(64),
			"f".repeat(64),
			"0".repeat(64)
		);
	}

	private void insertCandidateInput() {
		jdbcTemplate.update("""
			INSERT INTO datapack_candidate_inputs (
				id, candidate_id, source_snapshot_ids, approved_alias_ledger_hash,
				facility_evidence_ledger_hash, route_evidence_ledger_hash,
				approved_override_set_hash, created_at
			)
			VALUES ('candidate-input-1', 'candidate-capital-1',
				'snapshot-kric-20260629,snapshot-route-20260629', ?, ?, ?, ?,
				'2026-06-29 03:01:00')
			""",
			"1".repeat(64),
			"2".repeat(64),
			"3".repeat(64),
			"4".repeat(64)
		);
	}
}
