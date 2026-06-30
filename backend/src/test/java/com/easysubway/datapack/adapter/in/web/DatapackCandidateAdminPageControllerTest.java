package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터팩 candidate pack 화면")
class DatapackCandidateAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_evidence_bundles");
		jdbcTemplate.update("DELETE FROM datapack_candidate_inputs");
		jdbcTemplate.update("DELETE FROM datapack_candidates");
		insertCandidate();
		insertCandidateInput();
		insertEvidenceBundle();
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 candidate pack 목록과 상세를 확인한다")
	void datapackReadAdminViewsCandidatePackListAndDetail() throws Exception {
		String listHtml = mockMvc.perform(get("/admin/datapack/candidates/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(listHtml)
			.contains("Candidate Packs")
			.contains("candidate-capital-1")
			.contains("capital_pilot_android_v1")
			.contains("2026.06.29-cand.1")
			.contains("READY_FOR_APPROVAL")
			.contains("PASS")
			.contains("/admin/datapack/candidates/candidate-capital-1/page")
			.doesNotContain("name=\"commandToken\"")
			.doesNotContain("승격 실행")
			.doesNotContain("serviceKey");

		String detailHtml = mockMvc.perform(get("/admin/datapack/candidates/candidate-capital-1/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(detailHtml)
			.contains("candidate-capital-1")
			.contains("sourceSnapshotIds")
			.contains("snapshot-kric-20260629")
			.contains("buildSpec")
			.contains("workflowRunUrl")
			.contains("https://github.com/AquilaXk/easysubway/actions/runs/123?redacted")
			.contains("evidenceBundleSha256")
			.contains("raw evidence 원문은 표시하지 않고 검증된 hash/status만 표시합니다.")
			.contains("production promote 가능")
			.contains("name=\"commandToken\"")
			.contains("gate 재실행")
			.doesNotContain("production 승인")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("candidate build 권한 관리자는 gate 재실행을 요청한다")
	void candidateBuilderRequestsGateRerun() throws Exception {
		mockMvc.perform(post("/admin/datapack/candidates/candidate-capital-1/rerun-gates")
				.with(csrf())
				.with(commandToken("/admin/datapack/candidates/candidate-capital-1/page"))
				.with(user("candidate-builder").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.candidate.build")
				))
				.param("reason", "rerun after evidence review")
				.param("idempotencyKey", "candidate-rerun-1162"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/candidates/candidate-capital-1/page"));

		assertThat(candidateValue("coverage_status")).isEqualTo("PENDING");
		assertThat(candidateValue("validator_status")).isEqualTo("PENDING");
		assertThat(candidateValue("route_regression_status")).isEqualTo("PENDING");
		assertThat(candidateValue("android_evidence_status")).isEqualTo("PENDING");
		assertThat(candidateValue("approval_status")).isEqualTo("DRAFT");
		assertThat(evidenceBundleCount()).isZero();
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 candidate pack 화면에 접근할 수 없다")
	void candidatePackPageRequiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get("/admin/datapack/candidates/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
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

	private void insertEvidenceBundle() {
		jdbcTemplate.update("""
			INSERT INTO datapack_release_evidence_bundles (
				id, candidate_id, evidence_bundle_sha256, workflow_run_url,
				validator_status, route_regression_status, manifest_signature_status,
				android_evidence_status, created_at
			)
			VALUES ('evidence-bundle-1', 'candidate-capital-1', ?,
				'https://github.com/AquilaXk/easysubway/actions/runs/123?serviceKey=secret',
				'PASS', 'PASS', 'PASS', 'PASS', '2026-06-29 03:02:00')
			""",
			"5".repeat(64)
		);
	}

	private String candidateValue(String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM datapack_candidates WHERE id = 'candidate-capital-1'",
			String.class
		);
	}

	private Integer evidenceBundleCount() {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM datapack_release_evidence_bundles WHERE candidate_id = 'candidate-capital-1'",
			Integer.class
		);
	}

	private RequestPostProcessor commandToken(String pagePath) {
		return request -> {
			MockHttpSession session = (MockHttpSession) request.getSession(true);
			request.addParameter("commandToken", commandTokenFrom(getAdminHtml(pagePath, session)));
			request.setSession(session);
			return request;
		};
	}

	private String getAdminHtml(String path, MockHttpSession session) {
		try {
			return mockMvc.perform(get(path)
					.session(session)
					.with(user("token-reader").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String commandTokenFrom(String html) {
		Matcher matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}
}
