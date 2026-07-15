package com.easysubway.notice.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEvent;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.notice.application.port.out.ServiceNoticeRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("관리자 운행 공지 화면")
class ServiceNoticeAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private ServiceNoticeRepository repository;
	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM service_notice");
	}

	@Test
	@DisplayName("운영 권한 관리자는 화면에서 공지를 발행하고 즉시 내린다")
	void operationsManagerPublishesAndUnpublishesNotice() throws Exception {
		String emptyPage = mockMvc.perform(get("/admin/notices/page")
				.with(operationsManager()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(emptyPage)
			.contains("운행 공지")
			.contains("name=\"commandToken\"")
			.contains("공지 없음");

		mockMvc.perform(post("/admin/notices/page")
				.with(csrf())
				.with(commandToken())
				.with(operationsManager())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("scope", "LINE")
				.param("scopeValue", "2")
				.param("severity", "DISRUPTION")
				.param("title", "2호선 지연")
				.param("body", "강남-역삼 상행 지연입니다.")
				.param("expiresAt", LocalDateTime.now(ZoneOffset.UTC).plusHours(1).toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/notices/page"));

		var notices = repository.findActiveAt(LocalDateTime.now(ZoneOffset.UTC));
		assertThat(notices).hasSize(1);
		String noticeId = notices.getFirst().id();
		assertThat(auditRecorded("PUBLISH_NOTICE", noticeId)).isTrue();

		String populatedPage = mockMvc.perform(get("/admin/notices/page")
				.with(operationsManager()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(populatedPage)
			.contains("2호선 지연")
			.contains("강남-역삼 상행 지연입니다.")
			.contains("/admin/notices/" + noticeId + "/unpublish/page");

		mockMvc.perform(post("/admin/notices/{id}/unpublish/page", noticeId)
				.with(csrf())
				.with(commandToken())
				.with(operationsManager())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/admin/notices/page"));

		assertThat(repository.findById(noticeId)).isEmpty();
		assertThat(auditRecorded("UNPUBLISH_NOTICE", noticeId)).isTrue();
	}

	@Test
	@DisplayName("운영 권한이 없으면 운행 공지 화면에 접근할 수 없다")
	void pageRequiresOperationsManage() throws Exception {
		mockMvc.perform(get("/admin/notices/page")
				.with(user("viewer").authorities(new SimpleGrantedAuthority("admin.view"))))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("잘못된 발행 입력은 400으로 거부된다")
	void invalidPublishFormRejectedAsBadRequest() throws Exception {
		mockMvc.perform(post("/admin/notices/page")
				.with(csrf())
				.with(commandToken())
				.with(operationsManager())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("scope", "ALL")
				.param("severity", "WARN")
				.param("title", "잘못된 공지")
				.param("body", "본문"))
			.andExpect(status().isBadRequest());

		assertThat(repository.findActiveAt(LocalDateTime.now(ZoneOffset.UTC))).isEmpty();
	}

	@Test
	@DisplayName("없는 공지 내리기는 성공 audit 없이 404로 닫는다")
	void missingUnpublishRejectedAsNotFound() throws Exception {
		mockMvc.perform(post("/admin/notices/missing/unpublish/page")
				.with(csrf())
				.with(commandToken())
				.with(operationsManager())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED))
			.andExpect(status().isNotFound());

		assertThat(auditRecorded("UNPUBLISH_NOTICE", "missing")).isFalse();
	}

	private RequestPostProcessor operationsManager() {
		return user("operator").authorities(new SimpleGrantedAuthority("admin.operations.manage"));
	}

	private boolean auditRecorded(String action, String targetId) {
		List<AdminAuditEvent> events =
			auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 100);
		return events.stream().anyMatch(event ->
			"SERVICE_NOTICE".equals(event.targetType())
				&& action.equals(event.action())
				&& targetId.equals(event.targetId()));
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
			return mockMvc.perform(get("/admin/notices/page")
					.session(session)
					.with(operationsManager()))
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
