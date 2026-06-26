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

	private AdminAuditEvent event(AdminAuditEventType type, String action) {
		return new AdminAuditEvent(
			null,
			type,
			"admin-user",
			"admin.view",
			"request-1",
			"127.0.0.1",
			"JUnit",
			"FACILITY_REPORT",
			"report-1",
			action,
			AdminAuditOutcome.SUCCESS,
			"업무 맥락: 신고 상세 조회",
			LocalDateTime.of(2026, 6, 27, 0, 0)
		);
	}
}
