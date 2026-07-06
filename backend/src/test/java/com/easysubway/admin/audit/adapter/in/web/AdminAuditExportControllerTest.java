package com.easysubway.admin.audit.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.application.AdminAuditQuery;
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
@DisplayName("감사 로그 내보내기")
class AdminAuditExportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@Test
	@DisplayName("CSV 내보내기는 BOM·헤더·필터 결과를 담고 내보내기 자체를 감사에 남긴다")
	void csvExportWritesRowsAndAuditsItself() throws Exception {
		auditEventRepository.save(eventAt(AdminAuditEventType.ADMIN_ACTION, "alice", "EXPORT_ME",
			AdminAuditOutcome.FAILURE, LocalDateTime.of(2026, 6, 27, 9, 0)));
		auditEventRepository.save(eventAt(AdminAuditEventType.ADMIN_ACTION, "bob", "SKIP_ME",
			AdminAuditOutcome.SUCCESS, LocalDateTime.of(2026, 6, 27, 9, 1)));

		var response = mockMvc.perform(get("/admin/audits/export")
				.param("actor", "alice")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();

		String csv = response.getContentAsString();
		assertThat(response.getContentType()).contains("text/csv");
		assertThat(csv).startsWith("﻿").contains("occurred_at,event_type,actor").contains("EXPORT_ME");
		assertThat(csv).doesNotContain("SKIP_ME");

		// 내보내기 자체가 AUDIT_EXPORT 감사로 남는다.
		boolean exportAudited = auditEventRepository.findForExport(
				new AdminAuditQuery(null, null, null, null, null, null, false, 0, 100), 100)
			.stream()
			.anyMatch(event -> "AUDIT_EXPORT".equals(event.targetType()) && event.action().startsWith("EXPORT_AUDIT"));
		assertThat(exportAudited).isTrue();
	}

	@Test
	@DisplayName("JSON 내보내기는 application/json으로 내보낸다")
	void jsonExport() throws Exception {
		auditEventRepository.save(eventAt(AdminAuditEventType.ADMIN_ACTION, "alice", "JSON_ROW",
			AdminAuditOutcome.SUCCESS, LocalDateTime.of(2026, 6, 27, 9, 0)));

		var response = mockMvc.perform(get("/admin/audits/export")
				.param("format", "json")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();

		assertThat(response.getContentType()).contains("application/json");
		assertThat(response.getContentAsString()).contains("JSON_ROW").contains("\"actor\":\"alice\"");
	}

	@Test
	@DisplayName("CSV 내보내기는 수식 인젝션 위험 값(=,+,-,@ 시작)을 작은따옴표로 무력화한다")
	void csvExportNeutralizesFormulaInjection() throws Exception {
		auditEventRepository.save(new AdminAuditEvent(
			null, AdminAuditEventType.ADMIN_ACTION, "=cmd|calc", "admin.view", "req", "127.0.0.1",
			"@SUM(1)", "FACILITY_REPORT", "report-1", "VIEW", AdminAuditOutcome.SUCCESS, "업무 맥락",
			LocalDateTime.of(2026, 6, 27, 9, 0)));

		String csv = mockMvc.perform(get("/admin/audits/export")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(csv).contains("'=cmd|calc").contains("'@SUM(1)");
		assertThat(csv).doesNotContain(",=cmd|calc,").doesNotContain(",@SUM(1),");
	}

	@Test
	@DisplayName("CSV 내보내기는 MAX_EXPORT_ROWS(5000) 행 상한에서 잘린다")
	void csvExportCapsAtMaxRows() throws Exception {
		// AdminAuditExportController.MAX_EXPORT_ROWS(=5000)보다 많은 행을 심어 상한 절삭을 검증한다.
		LocalDateTime base = LocalDateTime.of(2026, 6, 27, 9, 0);
		for (int i = 0; i < 5005; i++) {
			auditEventRepository.save(eventAt(AdminAuditEventType.ADMIN_ACTION, "flooder", "FLOOD",
				AdminAuditOutcome.SUCCESS, base.plusSeconds(i)));
		}

		String csv = mockMvc.perform(get("/admin/audits/export")
				.param("actor", "flooder")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		// 헤더에는 actor 이름이 없으므로 "flooder"를 담은 줄 = 데이터 행. 5005개를 심어도 상한 5000에서 잘린다.
		long dataRows = csv.lines().filter(line -> line.contains("flooder")).count();
		assertThat(dataRows).isEqualTo(5000);
	}

	@Test
	@DisplayName("개인정보 로그 내보내기는 개인정보 로그 권한을 요구한다(권한 분리)")
	void privacyExportRequiresPrivacyPermission() throws Exception {
		mockMvc.perform(get("/admin/audits/privacy/export")
				.with(user("auditor").authorities(new SimpleGrantedAuthority("admin.audit.read"))))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/audits/export")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/audits/privacy/export")
				.with(user("privacy").authorities(new SimpleGrantedAuthority("admin.privacy-log.read"))))
			.andExpect(status().isOk());
	}

	private AdminAuditEvent eventAt(
		AdminAuditEventType type, String actor, String action, AdminAuditOutcome outcome, LocalDateTime occurredAt) {
		return new AdminAuditEvent(
			null, type, actor, "admin.view", "request-1", "127.0.0.1", "JUnit",
			"FACILITY_REPORT", "report-1", action, outcome, "업무 맥락", occurredAt);
	}
}
