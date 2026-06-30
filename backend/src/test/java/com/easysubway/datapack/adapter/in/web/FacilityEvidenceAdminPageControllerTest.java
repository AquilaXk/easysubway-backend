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
@DisplayName("관리자 데이터팩 facility evidence matrix 화면")
class FacilityEvidenceAdminPageControllerTest {

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
			.contains("name=\"commandToken\"")
			.contains("검수 저장")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("evidence review 권한 관리자는 facility evidence 검수 상태를 저장한다")
	void evidenceReviewerUpdatesFacilityEvidenceReviewState() throws Exception {
		mockMvc.perform(post("/admin/datapack/facility-evidence/facility-evidence-lift-unknown/review")
				.with(csrf())
				.with(commandToken("/admin/datapack/facility-evidence/page"))
				.with(user("facility-reviewer").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.evidence.review")
				))
				.param("strictRouteEligible", "false")
				.param("strictRouteEligibleReason", "wheelchair lift requires field verification")
				.param("conflictStatus", "RESOLVED")
				.param("reason", "official evidence reviewed")
				.param("idempotencyKey", "facility-review-1162"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/facility-evidence/page"));

		assertThat(evidenceValue("facility-evidence-lift-unknown", "conflict_status")).isEqualTo("RESOLVED");
		assertThat(evidenceValue("facility-evidence-lift-unknown", "strict_route_eligible_reason"))
			.isEqualTo("wheelchair lift requires field verification");
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

	private String evidenceValue(String id, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM facility_evidence WHERE id = ?",
			String.class,
			id
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
