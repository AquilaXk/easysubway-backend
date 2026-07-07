package com.easysubway.notice.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import com.easysubway.notice.domain.ServiceNotice;
import com.easysubway.notice.domain.ServiceNoticeScope;
import com.easysubway.notice.domain.ServiceNoticeSeverity;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 운행 공지 API")
class ServiceNoticeAdminApiControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ServiceNoticeRepository repository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM service_notice");
	}

	private boolean auditRecorded(String action, String targetId) {
		List<AdminAuditEvent> events =
			auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 100);
		return events.stream().anyMatch(event ->
			"SERVICE_NOTICE".equals(event.targetType())
				&& action.equals(event.action())
				&& targetId.equals(event.targetId()));
	}

	@Test
	@DisplayName("관리자 발행은 공지를 저장하고 audit에 기록한다")
	void publishSavesAndAudits() throws Exception {
		mockMvc.perform(post("/admin/notices")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"scope":"LINE","scopeValue":"2","title":"2호선 지연",
					"body":"우회 경로를 확인하세요.","severity":"DISRUPTION","expiresAt":null}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").isNotEmpty())
			.andExpect(jsonPath("$.data.publishedBy").doesNotExist());

		List<ServiceNotice> active = repository.findActiveAt(LocalDateTime.now(ZoneOffset.UTC));
		assertThat(active).hasSize(1);
		assertThat(auditRecorded("PUBLISH_NOTICE", active.get(0).id())).isTrue();
	}

	@Test
	@DisplayName("즉시 내리기는 공지를 제거하고 audit에 기록한다")
	void unpublishRemovesAndAudits() throws Exception {
		repository.save(new ServiceNotice("n1", ServiceNoticeScope.ALL, null,
			"전체 공지", "본문", ServiceNoticeSeverity.INFO,
			LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(1), null, "operator-a"));

		mockMvc.perform(post("/admin/notices/n1/unpublish")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf()))
			.andExpect(status().isOk());

		assertThat(repository.findById("n1")).isEmpty();
		assertThat(auditRecorded("UNPUBLISH_NOTICE", "n1")).isTrue();
	}

	@Test
	@DisplayName("알 수 없는 severity 발행은 500이 아닌 400으로 거부된다")
	void unknownSeverityRejectedAsBadRequest() throws Exception {
		mockMvc.perform(post("/admin/notices")
				.with(httpBasic("admin-user", "admin-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"scope\":\"ALL\",\"title\":\"x\",\"body\":\"y\",\"severity\":\"WARN\"}"))
			.andExpect(status().isBadRequest());

		assertThat(repository.findActiveAt(LocalDateTime.now(ZoneOffset.UTC))).isEmpty();
	}

	@Test
	@DisplayName("인증 없는 발행은 거부된다")
	void unauthenticatedPublishRejected() throws Exception {
		mockMvc.perform(post("/admin/notices")
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"scope\":\"ALL\",\"title\":\"x\",\"body\":\"y\",\"severity\":\"INFO\"}"))
			.andExpect(status().isUnauthorized());

		assertThat(repository.findActiveAt(LocalDateTime.now(java.time.ZoneOffset.UTC))).isEmpty();
	}
}
