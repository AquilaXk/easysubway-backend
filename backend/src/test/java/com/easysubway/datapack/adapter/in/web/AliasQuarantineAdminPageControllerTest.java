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
			.doesNotContain("name=\"commandToken\"")
			.doesNotContain("승인 저장")
			.doesNotContain("해결 저장")
			.doesNotContain("serviceKey");
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
}
