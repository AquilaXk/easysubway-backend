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

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 데이터팩 alias/quarantine 검수 큐 화면")
class AliasQuarantineAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM route_edge_evidence");
		jdbcTemplate.update("DELETE FROM facility_evidence");
		jdbcTemplate.update("DELETE FROM manual_overrides");
		jdbcTemplate.update("DELETE FROM source_quarantine_resolutions");
		jdbcTemplate.update("DELETE FROM source_quarantine_records");
		jdbcTemplate.update("DELETE FROM external_alias_approvals");
		jdbcTemplate.update("DELETE FROM datapack_normalization_runs");
		jdbcTemplate.update("DELETE FROM data_source_snapshots");
		insertSnapshot();
		insertAliasApproval();
		insertQuarantineRecord();
		insertResolvedQuarantineRecord();
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 alias/quarantine 검수 큐를 확인한다")
	void datapackReadAdminViewsAliasQuarantineQueue() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/alias-quarantine/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("Alias / Quarantine")
			.contains("alias-station-1")
			.contains("provider-station-code-243")
			.contains("station-sangnoksu")
			.contains("92")
			.contains("PENDING")
			.contains("quarantine-open-1")
			.contains("ALIAS_CONFLICT")
			.contains("provider line=4, station=상록수")
			.contains("quarantine-resolved-1")
			.contains("ALIAS_APPROVED")
			.contains("station-sadang")
			.contains("name=\"commandToken\"")
			.contains("alias 승인")
			.contains("alias 거절")
			.contains("quarantine 해결")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("alias review 권한 관리자는 alias를 승인한다")
	void aliasReviewerApprovesAlias() throws Exception {
		mockMvc.perform(post("/admin/datapack/alias-quarantine/aliases/alias-station-1/approve")
				.with(csrf())
				.with(commandToken("/admin/datapack/alias-quarantine/page"))
				.with(user("alias-reviewer").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.alias.review")
				)))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/alias-quarantine/page"));

		assertThat(aliasValue("alias-station-1", "approval_status")).isEqualTo("APPROVED");
		assertThat(aliasValue("alias-station-1", "approved_by")).isEqualTo("alias-reviewer");
	}

	@Test
	@DisplayName("alias review 권한 관리자는 alias를 거절한다")
	void aliasReviewerRejectsAlias() throws Exception {
		mockMvc.perform(post("/admin/datapack/alias-quarantine/aliases/alias-station-1/reject")
				.with(csrf())
				.with(commandToken("/admin/datapack/alias-quarantine/page"))
				.with(user("alias-reviewer").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.alias.review")
				)))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/alias-quarantine/page"));

		assertThat(aliasValue("alias-station-1", "approval_status")).isEqualTo("REJECTED");
		assertThat(aliasValue("alias-station-1", "approved_by")).isEqualTo("alias-reviewer");
	}

	@Test
	@DisplayName("alias review 권한 없이 alias command를 실행할 수 없다")
	void aliasCommandRequiresAliasReviewPermission() throws Exception {
		mockMvc.perform(post("/admin/datapack/alias-quarantine/aliases/alias-station-1/approve")
				.with(csrf())
				.with(commandToken("/admin/datapack/alias-quarantine/page"))
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("quarantine review 권한 관리자는 quarantine blocker를 해결한다")
	void quarantineReviewerResolvesQuarantine() throws Exception {
		mockMvc.perform(post("/admin/datapack/alias-quarantine/quarantines/quarantine-open-1/resolve")
				.with(csrf())
				.with(commandToken("/admin/datapack/alias-quarantine/page"))
				.with(user("quarantine-reviewer").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.quarantine.review")
				))
				.param("resolutionStatus", "ALIAS_APPROVED")
				.param("resolutionReason", "station alias approved")
				.param("canonicalEntityType", "STATION")
				.param("canonicalEntityId", "station-sangnoksu")
				.param("evidenceHash", "1".repeat(64)))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/alias-quarantine/page"));

		assertThat(quarantineValue("quarantine-open-1", "resolution_status")).isEqualTo("RESOLVED");
		assertThat(quarantineValue("quarantine-open-1", "resolved_by")).isEqualTo("quarantine-reviewer");
		assertThat(resolutionValue("quarantine-open-1", "resolution_status")).isEqualTo("ALIAS_APPROVED");
		assertThat(resolutionValue("quarantine-open-1", "canonical_entity_id")).isEqualTo("station-sangnoksu");
	}

	@Test
	@DisplayName("quarantine review 권한 없이 quarantine command를 실행할 수 없다")
	void quarantineCommandRequiresQuarantineReviewPermission() throws Exception {
		mockMvc.perform(post("/admin/datapack/alias-quarantine/quarantines/quarantine-open-1/resolve")
				.with(csrf())
				.with(commandToken("/admin/datapack/alias-quarantine/page"))
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read")))
				.param("resolutionStatus", "IGNORED")
				.param("resolutionReason", "not actionable"))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 alias/quarantine 화면에 접근할 수 없다")
	void aliasQuarantinePageRequiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get("/admin/datapack/alias-quarantine/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	private void insertSnapshot() {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status, redistribution_allowed,
				credential_redacted, previous_snapshot_id, diff_summary, freshness_expires_at,
				raw_retention_expires_at
			)
			VALUES ('snapshot-kric-20260629', 'kric-station-elevator', '국가철도공단',
				'2026-06-29 03:00:00', '2026-06-28 00:00:00', 12345,
				?, 's3://easysubway-datapack-sources/kric-station-elevator/snapshot-kric-20260629.json',
				?, ?, 'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE, NULL,
				'이전 snapshot 대비 +12 rows', '2026-07-06 03:00:00', '2026-09-29 03:00:00')
			""",
			"a".repeat(64),
			"b".repeat(64),
			"c".repeat(64)
		);
	}

	private void insertAliasApproval() {
		jdbcTemplate.update("""
			INSERT INTO external_alias_approvals (
				id, source_id, source_snapshot_id, provider_entity_type, provider_entity_id,
				canonical_entity_type, canonical_entity_id, confidence, match_method,
				approval_status, requested_by, approved_by, approved_at, evidence_hash,
				superseded_by, created_at
			)
			VALUES ('alias-station-1', 'kric-station-elevator', 'snapshot-kric-20260629',
				'STATION', 'provider-station-code-243', 'STATION', 'station-sangnoksu',
				92, 'AUTO_NAME_LINE', 'PENDING', 'qa-operator', NULL, NULL, ?,
				NULL, '2026-06-29 03:10:00')
			""",
			"d".repeat(64)
		);
	}

	private void insertQuarantineRecord() {
		jdbcTemplate.update("""
			INSERT INTO source_quarantine_records (
				id, source_id, source_snapshot_id, provider_record_hash, reason_code,
				severity, redacted_excerpt, resolution_status, resolved_by, resolved_at,
				created_at
			)
			VALUES ('quarantine-open-1', 'kric-station-elevator', 'snapshot-kric-20260629',
				?, 'ALIAS_CONFLICT', 'P1', 'provider line=4, station=상록수',
				'OPEN', NULL, NULL, '2026-06-29 03:20:00')
			""",
			"e".repeat(64)
		);
	}

	private void insertResolvedQuarantineRecord() {
		jdbcTemplate.update("""
			INSERT INTO source_quarantine_records (
				id, source_id, source_snapshot_id, provider_record_hash, reason_code,
				severity, redacted_excerpt, resolution_status, resolved_by, resolved_at,
				created_at
			)
			VALUES ('quarantine-resolved-1', 'kric-station-elevator', 'snapshot-kric-20260629',
				?, 'MISSING_CANONICAL_STATION', 'P2', 'provider station=사당',
				'RESOLVED', 'qa-reviewer', '2026-06-29 03:35:00', '2026-06-29 03:30:00')
			""",
			"f".repeat(64)
		);
		jdbcTemplate.update("""
			INSERT INTO source_quarantine_resolutions (
				id, quarantine_record_id, resolution_status, resolution_reason,
				resolved_by, resolved_at, canonical_entity_type, canonical_entity_id,
				evidence_hash
			)
			VALUES ('resolution-1', 'quarantine-resolved-1', 'ALIAS_APPROVED',
				'station alias approved', 'qa-reviewer', '2026-06-29 03:35:00',
				'STATION', 'station-sadang', ?)
			""",
			"0".repeat(64)
		);
	}

	private Object aliasValue(String aliasId, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM external_alias_approvals WHERE id = ?",
			Object.class,
			aliasId
		);
	}

	private Object quarantineValue(String quarantineId, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM source_quarantine_records WHERE id = ?",
			Object.class,
			quarantineId
		);
	}

	private Object resolutionValue(String quarantineId, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM source_quarantine_resolutions WHERE quarantine_record_id = ?",
			Object.class,
			quarantineId
		);
	}

	private RequestPostProcessor commandToken(String pagePath) {
		return request -> {
			MockHttpSession session = (MockHttpSession) request.getSession();
			request.addParameter("commandToken", commandTokenFrom(getAdminHtml(pagePath, session)));
			return request;
		};
	}

	private String getAdminHtml(String pagePath, MockHttpSession session) {
		try {
			return mockMvc.perform(get(pagePath)
					.session(session)
					.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
				.andReturn()
				.getResponse()
				.getContentAsString();
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
