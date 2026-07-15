package com.easysubway.admin.audit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 감사 조회 화면")
class AdminAuditPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Test
	@DisplayName("감사 조회 화면은 AUDIT_READ 권한으로 보호된다")
	void auditPageRequiresAuditReadPermission() throws Exception {
		mockMvc.perform(get("/admin/audits/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());

		String html = mockMvc.perform(get("/admin/audits/page")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).contains("관리자 감사");
	}

	@Test
	@DisplayName("개인정보 조회 로그 화면은 PRIVACY_LOG_READ 권한으로 보호되고 privacy read만 표시한다")
	void privacyAuditPageRequiresPrivacyLogReadPermission() throws Exception {
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "POST /admin/reports/{reportId}/page/review"));
		auditEventRepository.save(event(AdminAuditEventType.PRIVACY_READ, "VIEW_REPORT_DETAIL"));

		mockMvc.perform(get("/admin/audits/privacy/page")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isForbidden());

		String html = mockMvc.perform(get("/admin/audits/privacy/page")
				.with(user("privacy").authorities(new SimpleGrantedAuthority("admin.privacy-log.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("개인정보 조회 로그")
			.contains("VIEW_REPORT_DETAIL")
			.doesNotContain("POST /admin/reports/{reportId}/page/review");
	}

	@Test
	@DisplayName("actor·결과 2필터로 특정 관리자의 실패 이벤트만 좁혀 볼 수 있다")
	void auditPageFiltersByActorAndOutcome() throws Exception {
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "alice", "OK_ONE", AdminAuditOutcome.SUCCESS, "정상"));
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "alice", "FAIL_ALICE", AdminAuditOutcome.FAILURE, "실패"));
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "bob", "FAIL_BOB", AdminAuditOutcome.FAILURE, "실패"));

		String html = mockMvc.perform(get("/admin/audits/page")
				.param("actor", "alice")
				.param("outcome", "FAILURE")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("FAIL_ALICE")
			.doesNotContain("OK_ONE")
			.doesNotContain("FAIL_BOB");
	}

	@Test
	@DisplayName("관리자 감사 목록은 개인정보 조회(PRIVACY_READ) 이벤트를 제외한다(권한 분리)")
	void auditPageExcludesPrivacyReadEvents() throws Exception {
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "alice", "ADMIN_ROW",
			AdminAuditOutcome.SUCCESS, "업무"));
		auditEventRepository.save(event(AdminAuditEventType.PRIVACY_READ, "alice", "PRIVACY_ROW",
			AdminAuditOutcome.SUCCESS, "민원 확인"));

		String html = mockMvc.perform(get("/admin/audits/page")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).contains("ADMIN_ROW").doesNotContain("PRIVACY_ROW");
	}

	@Test
	@DisplayName("사유 없는 조회만 거르는 필터를 제공한다")
	void auditPageFiltersReasonMissing() throws Exception {
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "alice", "WITH_REASON", AdminAuditOutcome.SUCCESS, "사유 있음"));
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "alice", "NO_REASON", AdminAuditOutcome.SUCCESS, null));

		String html = mockMvc.perform(get("/admin/audits/page")
				.param("reasonMissing", "true")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).contains("NO_REASON").doesNotContain("WITH_REASON");
	}

	@Test
	@DisplayName("잘못된 페이지·기간 요청값은 500이 아니라 보정되어 정상 렌더된다")
	void invalidRequestParamsAreSanitized() throws Exception {
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "alice", "SANE_ROW",
			AdminAuditOutcome.SUCCESS, "업무"));

		mockMvc.perform(get("/admin/audits/page")
				.param("page", "-1")
				.param("size", "0")
				.param("from", "2026-06-30")
				.param("to", "2026-06-01")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("htmx 요청은 셸 없이 결과 fragment만 돌려준다")
	void auditPageHtmxReturnsFragmentOnly() throws Exception {
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "alice", "FRAG_ACTION", AdminAuditOutcome.SUCCESS, "사유"));

		String html = mockMvc.perform(get("/admin/audits/page")
				.header("HX-Request", "true")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("FRAG_ACTION")
			.doesNotContain("admin-shell");
	}

	@Test
	@DisplayName("개인정보 로그는 사유 없는 조회 건수를 점검 배너로 노출한다")
	void privacyPageShowsReasonMissingComplianceBanner() throws Exception {
		auditEventRepository.save(event(AdminAuditEventType.PRIVACY_READ, "alice", "WITH_REASON",
			AdminAuditOutcome.SUCCESS, "업무 맥락: 민원 처리"));
		auditEventRepository.save(event(AdminAuditEventType.PRIVACY_READ, "bob", "NO_REASON",
			AdminAuditOutcome.SUCCESS, null));

		String html = mockMvc.perform(get("/admin/audits/privacy/page")
				.with(user("privacy").authorities(new SimpleGrantedAuthority("admin.privacy-log.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("compliance-banner")
			.contains("사유 없는 조회만 보기");
	}

	@Test
	@DisplayName("상세 드로어는 htmx 요청에 detailBody fragment와 admin-drawer-open 트리거를 돌려주고 전후 타임라인을 담는다")
	void auditDetailDrawerReturnsFragmentWithTimeline() throws Exception {
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		auditEventRepository.save(eventAt(AdminAuditEventType.ADMIN_ACTION, "alice", "BEFORE_ACTION", base));
		auditEventRepository.save(eventAt(AdminAuditEventType.ADMIN_ACTION, "alice", "PIVOT_ACTION", base.plusMinutes(1)));
		auditEventRepository.save(eventAt(AdminAuditEventType.ADMIN_ACTION, "alice", "AFTER_ACTION", base.plusMinutes(2)));
		Long pivotId = auditEventRepository.search(
				new com.easysubway.admin.audit.application.AdminAuditQuery(
					null, "alice", null, null, null, null, false, 0, 10))
			.stream()
			.filter(event -> event.action().equals("PIVOT_ACTION"))
			.findFirst()
			.orElseThrow()
			.id();

		var result = mockMvc.perform(get("/admin/audits/" + pivotId)
				.header("HX-Request", "true")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(result.getResponse().getHeader("HX-Trigger")).contains("admin-drawer-open");
		String html = result.getResponse().getContentAsString();
		assertThat(html)
			.contains("PIVOT_ACTION")
			.contains("BEFORE_ACTION")
			.contains("AFTER_ACTION")
			.doesNotContain("admin-shell");
	}

	@Test
	@DisplayName("개인정보 상세는 개인정보 로그 권한을 요구하고 감사 상세는 privacy 이벤트를 열지 못한다(권한 분리)")
	void detailRespectsPermissionSeparation() throws Exception {
		auditEventRepository.save(eventAt(AdminAuditEventType.PRIVACY_READ, "alice", "VIEW_DETAIL",
			LocalDateTime.of(2026, 6, 27, 9, 0)));
		Long privacyId = auditEventRepository.search(
				new com.easysubway.admin.audit.application.AdminAuditQuery(
					null, "alice", null, null, null, null, false, 0, 10))
			.getFirst()
			.id();

		// 개인정보 조회 로그 화면(상세 포함)은 개인정보 로그 권한을 요구한다 — AUDIT_READ로는 접근 불가.
		mockMvc.perform(get("/admin/audits/privacy/" + privacyId)
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/audits/privacy/page")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isForbidden());

		// 관리자 감사 화면 상세는 PRIVACY_READ 이벤트를 제외하므로 404(권한 분리 — 개인정보는 전용 화면에서만).
		mockMvc.perform(get("/admin/audits/" + privacyId)
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isNotFound());

		// 개인정보 로그 권한이 있으면 개인정보 상세를 연다.
		mockMvc.perform(get("/admin/audits/privacy/" + privacyId)
				.with(user("privacy").authorities(new SimpleGrantedAuthority("admin.privacy-log.read"))))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("감사 조회 화면은 page size와 현재 페이지를 링크에 표시한다")
	void auditPageShowsPaginationLinks() throws Exception {
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "FIRST_ACTION"));
		auditEventRepository.save(event(AdminAuditEventType.ADMIN_ACTION, "SECOND_ACTION"));

		String html = mockMvc.perform(get("/admin/audits/page")
				.param("size", "1")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("관리자 감사 페이지")
			.contains("aria-current=\"page\"")
			.contains("page=1&amp;size=1")
			.contains("다음");
	}

	private AdminAuditEvent event(AdminAuditEventType type, String action) {
		return event(type, "admin-user", action, AdminAuditOutcome.SUCCESS, "업무 맥락: 신고 상세 조회");
	}

	private AdminAuditEvent event(
		AdminAuditEventType type,
		String actor,
		String action,
		AdminAuditOutcome outcome,
		String reason
	) {
		return new AdminAuditEvent(
			null, type, actor, "admin.view", "request-1", "127.0.0.1", "JUnit",
			"FACILITY_REPORT", "report-1", action, outcome, reason, LocalDateTime.of(2026, 6, 27, 0, 0));
	}

	private AdminAuditEvent eventAt(
		AdminAuditEventType type, String actor, String action, LocalDateTime occurredAt) {
		return new AdminAuditEvent(
			null, type, actor, "admin.view", "request-1", "127.0.0.1", "JUnit",
			"FACILITY_REPORT", "report-1", action, AdminAuditOutcome.SUCCESS, "업무 맥락", occurredAt);
	}
}
