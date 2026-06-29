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
@DisplayName("관리자 데이터팩 facility evidence matrix 화면")
class FacilityEvidenceAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM facility_evidence");
		jdbcTemplate.update("DELETE FROM manual_overrides");
		jdbcTemplate.update("DELETE FROM data_source_snapshots");
		insertSnapshot();
		insertManualOverride();
		insertStrictEligibleEvidence();
		insertUnknownEvidence();
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 facility evidence matrix를 확인한다")
	void datapackReadAdminViewsFacilityEvidenceMatrix() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/facility-evidence/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("Facility Evidence Matrix")
			.contains("station-sangnoksu")
			.contains("line-4")
			.contains("ELEVATOR")
			.contains("EXISTS")
			.contains("INSTALLED")
			.contains("AVAILABLE")
			.contains("OPERATOR_CONFIRMED")
			.contains("strict 가능")
			.contains("station-sadang")
			.contains("WHEELCHAIR_LIFT")
			.contains("UNKNOWN_PENDING_REVIEW")
			.contains("UNKNOWN")
			.contains("strict 불가")
			.contains("운행 상태 확인 필요")
			.contains("override-facility-1")
			.doesNotContain("name=\"commandToken\"")
			.doesNotContain("수정 저장")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 facility evidence matrix에 접근할 수 없다")
	void facilityEvidencePageRequiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get("/admin/datapack/facility-evidence/page")
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

	private void insertManualOverride() {
		jdbcTemplate.update("""
			INSERT INTO manual_overrides (
				id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by, approved_by,
				approved_at, route_safety_approved_by, approval_status, conflict_status,
				strict_route_eligible, effective_from, expires_at, superseded_by, created_at
			)
			VALUES ('override-facility-1', 'FACILITY', 'station-sadang:WHEELCHAIR_LIFT',
				'operational_status', 'UNKNOWN', 'CHECK_REQUIRED', 'FIELD_CHECK',
				'현장 확인 필요', 's3://easysubway-evidence/facility/override-facility-1.json',
				?, 'qa-operator', 'qa-reviewer', '2026-06-29 03:30:00', NULL,
				'APPROVED', 'NONE', FALSE, '2026-06-29 03:30:00',
				'2026-07-29 03:30:00', NULL, '2026-06-29 03:20:00')
			""",
			"d".repeat(64)
		);
	}

	private void insertStrictEligibleEvidence() {
		jdbcTemplate.update("""
			INSERT INTO facility_evidence (
				id, station_id, line_id, facility_type, evidence_kind, source_id,
				source_snapshot_id, provider_record_hash, status_meaning,
				installation_status, operational_status, verified_at, retrieved_at,
				freshness_expires_at, confidence, strict_route_eligible,
				strict_route_eligible_reason, conflict_status, manual_override_id,
				created_at
			)
			VALUES ('facility-evidence-elevator-1', 'station-sangnoksu', 'line-4',
				'ELEVATOR', 'EXISTS', 'kric-station-elevator', 'snapshot-kric-20260629',
				?, 'OPERATOR_CONFIRMED', 'INSTALLED', 'AVAILABLE',
				'2026-06-29 03:00:00', '2026-06-29 03:00:00',
				'2026-07-06 03:00:00', 95, TRUE, NULL, 'NONE', NULL,
				'2026-06-29 03:10:00')
			""",
			"e".repeat(64)
		);
	}

	private void insertUnknownEvidence() {
		jdbcTemplate.update("""
			INSERT INTO facility_evidence (
				id, station_id, line_id, facility_type, evidence_kind, source_id,
				source_snapshot_id, provider_record_hash, status_meaning,
				installation_status, operational_status, verified_at, retrieved_at,
				freshness_expires_at, confidence, strict_route_eligible,
				strict_route_eligible_reason, conflict_status, manual_override_id,
				created_at
			)
			VALUES ('facility-evidence-lift-unknown', 'station-sadang', 'line-2',
				'WHEELCHAIR_LIFT', 'UNKNOWN_PENDING_REVIEW', 'kric-station-elevator',
				'snapshot-kric-20260629', ?, 'STATIC_LOCATION', 'UNKNOWN',
				'UNKNOWN', '2026-06-29 03:00:00', '2026-06-29 03:00:00',
				'2026-07-06 03:00:00', 40, FALSE, '운행 상태 확인 필요',
				'UNRESOLVED', 'override-facility-1', '2026-06-29 03:11:00')
			""",
			"f".repeat(64)
		);
	}
}
