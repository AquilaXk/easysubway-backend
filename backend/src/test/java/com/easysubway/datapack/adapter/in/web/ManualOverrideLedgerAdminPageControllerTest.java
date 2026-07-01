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
@DisplayName("관리자 데이터팩 manual override ledger 화면")
class ManualOverrideLedgerAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM route_edge_evidence");
		jdbcTemplate.update("DELETE FROM facility_evidence");
		jdbcTemplate.update("DELETE FROM source_quarantine_resolutions");
		jdbcTemplate.update("DELETE FROM source_quarantine_records");
		jdbcTemplate.update("DELETE FROM external_alias_approvals");
		jdbcTemplate.update("DELETE FROM datapack_normalization_runs");
		jdbcTemplate.update("DELETE FROM data_source_snapshots");
		jdbcTemplate.update("DELETE FROM manual_overrides");
		insertApprovedOverride();
		insertPendingConflictOverride();
		insertStrictPendingOverride();
		insertExpiredOverride();
	}

	@Test
	@DisplayName("datapack read 권한 관리자는 manual override ledger를 확인한다")
	void datapackReadAdminViewsManualOverrideLedger() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/manual-overrides/page")
				.with(user("datapack-viewer").authorities(new SimpleGrantedAuthority("admin.datapack.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("Manual Overrides")
			.contains("override-approved-1")
			.contains("FACILITY")
			.contains("operational_status")
			.contains("APPROVED")
			.contains("candidate 가능")
			.contains("override-conflict-1")
			.contains("UNRESOLVED")
			.contains("unresolved conflict")
			.contains("override-strict-pending")
			.contains("route safety approval missing")
			.contains("override-expired-1")
			.contains("EXPIRED")
			.contains("expired")
			.contains("name=\"commandToken\"")
			.contains("요청 저장")
			.contains("승인 저장")
			.contains("만료 저장")
			.doesNotContain("serviceKey");
	}

	@Test
	@DisplayName("override request 권한 관리자는 manual override 요청을 생성한다")
	void overrideRequesterCreatesManualOverrideRequest() throws Exception {
		mockMvc.perform(post("/admin/datapack/manual-overrides")
				.with(csrf())
				.with(commandToken("/admin/datapack/manual-overrides/page"))
				.with(user("override-requester").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.override.request")
				))
				.param("id", "override-request-1162")
				.param("entityType", "FACILITY")
				.param("entityId", "station-suji:ELEVATOR")
				.param("fieldName", "operational_status")
				.param("beforeValue", "UNKNOWN")
				.param("afterValue", "AVAILABLE")
				.param("reasonCode", "FIELD_CHECK")
				.param("reason", "현장 확인 완료")
				.param("evidenceUri", "s3://easysubway-evidence/manual-overrides/override-request-1162.json")
				.param("evidenceHash", "1".repeat(64))
				.param("strictRouteEligible", "false")
				.param("effectiveFrom", "2026-06-30T03:00:00")
				.param("expiresAt", "2026-07-30T03:00:00")
				.param("idempotencyKey", "override-request-1162"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/manual-overrides/page"));

		assertThat(overrideValue("override-request-1162", "approval_status")).isEqualTo("PENDING");
		assertThat(overrideValue("override-request-1162", "requested_by")).isEqualTo("override-requester");
	}

	@Test
	@DisplayName("manual override 요청 폼은 신고 상세 query 값을 보존한다")
	void manualOverrideRequestFormKeepsReportPrefill() throws Exception {
		String html = mockMvc.perform(get("/admin/datapack/manual-overrides/page")
				.param("id", "override-report-1")
				.param("entityType", "FACILITY")
				.param("entityId", "facility-sangnoksu-elevator-1")
				.param("fieldName", "operational_status")
				.param("reasonCode", "FIELD_REPORT")
				.param("reason", "신고 검수 후 임시 override")
				.param("evidenceUri", "/admin/reports/report-1/page")
				.param("idempotencyKey", "override-report-1")
				.with(user("override-requester").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.override.request")
				)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("value=\"override-report-1\"")
			.contains("value=\"facility-sangnoksu-elevator-1\"")
			.contains("value=\"FIELD_REPORT\"")
			.contains("value=\"/admin/reports/report-1/page\"");
	}

	@Test
	@DisplayName("override approve 권한 관리자는 요청자와 분리되어 strict override를 승인한다")
	void overrideApproverApprovesStrictManualOverride() throws Exception {
		mockMvc.perform(post("/admin/datapack/manual-overrides/override-strict-pending/approve")
				.with(csrf())
				.with(commandToken("/admin/datapack/manual-overrides/page"))
				.with(user("override-approver").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.override.approve")
				))
				.param("reason", "route safety reviewed")
				.param("idempotencyKey", "override-approve-1162"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/manual-overrides/page"));

		assertThat(overrideValue("override-strict-pending", "approval_status")).isEqualTo("APPROVED");
		assertThat(overrideValue("override-strict-pending", "approved_by")).isEqualTo("override-approver");
		assertThat(overrideValue("override-strict-pending", "route_safety_approved_by")).isEqualTo("override-approver");
	}

	@Test
	@DisplayName("override approve 권한 관리자는 만료 command를 기록한다")
	void overrideApproverExpiresManualOverride() throws Exception {
		mockMvc.perform(post("/admin/datapack/manual-overrides/override-approved-1/expire")
				.with(csrf())
				.with(commandToken("/admin/datapack/manual-overrides/page"))
				.with(user("override-approver").authorities(
					new SimpleGrantedAuthority("admin.datapack.read"),
					new SimpleGrantedAuthority("admin.datapack.override.approve")
				))
				.param("reason", "temporary window ended")
				.param("idempotencyKey", "override-expire-1162"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/datapack/manual-overrides/page"));

		assertThat(overrideValue("override-approved-1", "approval_status")).isEqualTo("EXPIRED");
	}

	@Test
	@DisplayName("datapack read 권한이 없으면 manual override ledger에 접근할 수 없다")
	void manualOverrideLedgerPageRequiresDatapackReadPermission() throws Exception {
		mockMvc.perform(get("/admin/datapack/manual-overrides/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	private void insertApprovedOverride() {
		insertOverride(
			"override-approved-1",
			"FACILITY",
			"station-sangnoksu:ELEVATOR",
			"operational_status",
			"UNKNOWN",
			"AVAILABLE",
			"FIELD_CHECK",
			"현장 확인으로 운행 가능 확인",
			"qa-operator",
			"qa-reviewer",
			"2026-06-29 03:30:00",
			null,
			"APPROVED",
			"NONE",
			false,
			null,
			"a".repeat(64)
		);
	}

	private void insertPendingConflictOverride() {
		insertOverride(
			"override-conflict-1",
			"FACILITY",
			"station-sadang:WHEELCHAIR_LIFT",
			"operational_status",
			"UNKNOWN",
			"CHECK_REQUIRED",
			"SOURCE_CONFLICT",
			"공식 source와 현장 확인 결과가 충돌",
			"qa-operator",
			null,
			null,
			null,
			"PENDING",
			"UNRESOLVED",
			false,
			null,
			"b".repeat(64)
		);
	}

	private void insertStrictPendingOverride() {
		insertOverride(
			"override-strict-pending",
			"ROUTE_EDGE",
			"edge-transfer-1",
			"strict_route_eligible",
			"FALSE",
			"TRUE",
			"ROUTE_SAFETY",
			"strict 경로 사용 가능 여부 검수 대기",
			"qa-operator",
			null,
			null,
			null,
			"PENDING",
			"NONE",
			true,
			null,
			"c".repeat(64)
		);
	}

	private void insertExpiredOverride() {
		insertOverride(
			"override-expired-1",
			"FACILITY",
			"station-old:ELEVATOR",
			"operational_status",
			"AVAILABLE",
			"UNAVAILABLE",
			"TEMP_CLOSURE",
			"임시 운휴 기간 종료",
			"qa-operator",
			null,
			null,
			null,
			"EXPIRED",
			"NONE",
			false,
			null,
			"d".repeat(64)
		);
	}

	private void insertOverride(
		String id,
		String entityType,
		String entityId,
		String fieldName,
		String beforeValue,
		String afterValue,
		String reasonCode,
		String reason,
		String requestedBy,
		String approvedBy,
		String approvedAt,
		String routeSafetyApprovedBy,
		String approvalStatus,
		String conflictStatus,
		boolean strictRouteEligible,
		String supersededBy,
		String evidenceHash
	) {
		jdbcTemplate.update("""
			INSERT INTO manual_overrides (
				id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by,
				approved_by, approved_at, route_safety_approved_by, approval_status,
				conflict_status, strict_route_eligible, effective_from, expires_at,
				superseded_by, created_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?,
				's3://easysubway-evidence/manual-overrides/' || ? || '.json',
				?, ?, ?, ?, ?, ?, ?, ?, '2026-06-29 03:00:00',
				'2026-07-29 03:00:00', ?, '2026-06-29 03:10:00')
			""",
			id,
			entityType,
			entityId,
			fieldName,
			beforeValue,
			afterValue,
			reasonCode,
			reason,
			id,
			evidenceHash,
			requestedBy,
			approvedBy,
			approvedAt,
			routeSafetyApprovedBy,
			approvalStatus,
			conflictStatus,
			strictRouteEligible,
			supersededBy
		);
	}

	private String overrideValue(String overrideId, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM manual_overrides WHERE id = ?",
			String.class,
			overrideId
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
