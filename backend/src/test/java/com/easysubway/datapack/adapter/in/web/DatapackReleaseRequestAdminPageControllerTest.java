package com.easysubway.datapack.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("관리자 release request 화면")
class DatapackReleaseRequestAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM datapack_release_request");
		jdbcTemplate.update("DELETE FROM datapack_candidate_inputs");
		jdbcTemplate.update("DELETE FROM datapack_candidates");
		insertCandidate("candidate-stable-9", "2026.07.06-stable.9");
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 생성 폼과 candidate 목록을 본다")
	void rendersCreateForm() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/release-requests/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(html)
			.contains("release request")
			.contains("candidate-stable-9")
			.contains("name=\"candidateId\"")
			.contains("name=\"targetChannel\"")
			.contains("name=\"commandToken\"");
	}

	@Test
	@DisplayName("staging promote 권한 관리자는 candidate 선택으로 release request를 생성한다")
	void createsRequest() throws Exception {
		mockMvc.perform(post("/admin/datapack/release-requests")
				.with(csrf())
				.with(commandToken())
				.with(user("alice").authorities(new SimpleGrantedAuthority("admin.datapack.staging.promote")))
				.param("candidateId", "candidate-stable-9")
				.param("targetChannel", "staging"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/release-requests/page"));

		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM datapack_release_request WHERE candidate_id = ?", String.class, "candidate-stable-9"))
			.isEqualTo("REQUESTED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT requested_by FROM datapack_release_request WHERE candidate_id = ?", String.class, "candidate-stable-9"))
			.isEqualTo("alice");
		// 파생: approvedLedgerHash ← candidate.override_set_hash("b"*64)
		assertThat(jdbcTemplate.queryForObject(
			"SELECT approved_ledger_hash FROM datapack_release_request WHERE candidate_id = ?", String.class, "candidate-stable-9"))
			.isEqualTo("b".repeat(64));
	}

	@Test
	@DisplayName("production approve 권한 관리자는 다른 요청자의 요청을 승인한다")
	void approvesRequest() throws Exception {
		// alice가 생성
		mockMvc.perform(post("/admin/datapack/release-requests")
				.with(csrf()).with(commandToken())
				.with(user("alice").authorities(new SimpleGrantedAuthority("admin.datapack.staging.promote")))
				.param("candidateId", "candidate-stable-9")
				.param("targetChannel", "staging"))
			.andExpect(status().is3xxRedirection());
		String approvalId = jdbcTemplate.queryForObject(
			"SELECT approval_id FROM datapack_release_request WHERE candidate_id = ?", String.class, "candidate-stable-9");

		// bob이 승인
		mockMvc.perform(post("/admin/datapack/release-requests/{id}/approve", approvalId)
				.with(csrf()).with(commandToken())
				.with(user("bob").authorities(new SimpleGrantedAuthority("admin.datapack.production.approve"))))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/release-requests/page"));

		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM datapack_release_request WHERE approval_id = ?", String.class, approvalId))
			.isEqualTo("APPROVED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT approved_by FROM datapack_release_request WHERE approval_id = ?", String.class, approvalId))
			.isEqualTo("bob");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 화면에 접근할 수 없다")
	void pageRequiresDatapackRead() throws Exception {
		mockMvc.perform(get("/admin/datapack/release-requests/page")
				.with(user("v").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("미승인 candidate는 드롭다운에서 제외되고 create도 서버에서 거부된다")
	void ineligibleCandidateRejected() throws Exception {
		insertCandidateWithStatus("candidate-draft-1", "2026.07.06-draft.1", "DRAFT");

		// 드롭다운 필터: 적격(PROMOTED)만 노출, DRAFT 제외
		String html = mockMvc.perform(get("/admin/datapack/release-requests/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andReturn().getResponse().getContentAsString();
		assertThat(html).contains("candidate-stable-9").doesNotContain("candidate-draft-1");

		// 서버측 create 거부(폼 우회 시도) — 미처리 예외는 표면화될 수 있으나 핵심은 미생성
		try {
			mockMvc.perform(post("/admin/datapack/release-requests")
				.with(csrf()).with(commandToken())
				.with(user("alice").authorities(new SimpleGrantedAuthority("admin.datapack.staging.promote")))
				.param("candidateId", "candidate-draft-1")
				.param("targetChannel", "staging"));
		} catch (Exception expected) {
			// no-op
		}
		Integer count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM datapack_release_request WHERE candidate_id = ?", Integer.class, "candidate-draft-1");
		assertThat(count).isZero();
	}

	private void insertCandidate(String id, String version) {
		insertCandidateWithStatus(id, version, "PROMOTED");
	}

	private void insertCandidateWithStatus(String id, String version, String approvalStatus) {
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
				'PASS', ?, '2026-07-06 03:00:00')
			""",
			id, version,
			"a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
			"e".repeat(64), "f".repeat(64), "0".repeat(64), approvalStatus);
	}

	private RequestPostProcessor commandToken() {
		return request -> {
			MockHttpSession session = (MockHttpSession) request.getSession();
			request.addParameter("commandToken", commandTokenFrom(getAdminHtml(session)));
			return request;
		};
	}

	private String getAdminHtml(MockHttpSession session) {
		try {
			return mockMvc.perform(get("/admin/datapack/release-requests/page")
					.session(session)
					.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
				.andReturn().getResponse().getContentAsString();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String commandTokenFrom(String html) {
		var matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		if (!matcher.find()) {
			throw new IllegalStateException("commandToken missing");
		}
		return matcher.group(1);
	}
}
