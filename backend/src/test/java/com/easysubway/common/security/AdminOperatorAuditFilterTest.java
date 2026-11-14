package com.easysubway.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.audit.adapter.out.persistence.InMemoryAdminAuditEventRepository;
import com.easysubway.admin.audit.domain.AdminAuditEventType;
import com.easysubway.common.web.TrustedClientIpResolver;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.HttpSession;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.operator.username=operator-test",
	"easysubway.operator.password=operator-test-password"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("관리자 운영자 감사 필터")
class AdminOperatorAuditFilterTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryAdminAuditEventRepository auditEventRepository;

	@TestConfiguration
	static class FailingAdminMutationConfiguration {

		@Bean
		FailingAdminMutationController failingAdminMutationController() {
			return new FailingAdminMutationController();
		}
	}

	@RestController
	static class FailingAdminMutationController {

		@PostMapping("/admin/audit-test/fail")
		void fail() {
			throw new IllegalStateException("forced admin audit failure");
		}
	}

	@Test
	@DisplayName("관리자 상태 변경 요청은 민감값 없는 감사 로그를 남긴다")
	void adminMutatingRequestWritesRedactedAuditLog(CapturedOutput output) throws Exception {
		String reportId = createReport();

		mockMvc.perform(post("/admin/reports/{reportId}/page/review", reportId)
				.queryParam("receiptToken", "plain-receipt-token")
				.queryParam("latitude", "37.302421")
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.with(commandToken("/admin/reports/%s/page".formatted(reportId)))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("decision", "ACCEPT")
				.param("privateNote", "do-not-log-private-note")
				.param("uploadUrl", "https://storage.example/upload"))
			.andExpect(status().is3xxRedirection());

		assertThat(output.getOut())
			.contains("admin_operator_state_change_audit")
			.contains("method=POST")
			.contains("path=/admin/reports/{reportId}/page/review")
			.contains("principal=admin-test")
			.contains("status=302")
			.doesNotContain("plain-receipt-token")
			.doesNotContain("37.302421")
			.doesNotContain("do-not-log-private-note")
			.doesNotContain("https://storage.example/upload");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.actor()).isEqualTo("admin-test");
				assertThat(event.targetType()).isEqualTo("/admin/reports/{reportId}/page/review");
				assertThat(event.action()).isEqualTo("POST /admin/reports/{reportId}/page/review");
				assertThat(event.outcome().name()).isEqualTo("SUCCESS");
				assertThat(event.toString())
					.doesNotContain("plain-receipt-token")
					.doesNotContain("do-not-log-private-note")
					.doesNotContain("https://storage.example/upload");
			});
	}

	@Test
	@DisplayName("관리자 상태 변경 예외 요청은 500 감사 로그를 남긴다")
	void adminMutationFailureWritesServerErrorAuditLog(CapturedOutput output) throws Exception {
		String correlationId = mockMvc.perform(post("/admin/audit-test/fail")
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(header().exists("X-Correlation-Id"))
			.andReturn()
			.getResponse()
			.getHeader("X-Correlation-Id");

		assertThat(correlationId).isNotBlank();
		assertThat(output.getOut())
			.contains("admin_operator_state_change_audit")
			.contains("method=POST")
			.contains("path=/admin/audit-test/fail")
			.contains("principal=admin-test")
			.contains("status=500")
			.contains("correlation_id=" + correlationId)
			.doesNotContain("correlation_id=missing");
		assertThat(auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1))
			.singleElement()
			.satisfies(event -> assertThat(event.requestId()).isEqualTo(correlationId));
	}

	@Test
	@DisplayName("운영기관 상태 변경 경로도 동일한 감사 로그 경계에 걸린다")
	void operatorMutatingRequestWritesRedactedAuditLog(CapturedOutput output) throws Exception {
		mockMvc.perform(post("/operator/future-state-change")
				.queryParam("uploadUrl", "https://storage.example/operator-upload")
				.with(httpBasic("operator-test", "operator-test-password"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("privateNote", "operator-private-note"))
			.andExpect(status().isNotFound());

		assertThat(output.getOut())
			.contains("admin_operator_state_change_audit")
			.contains("method=POST")
			.contains("path=/operator/future-state-change")
			.contains("principal=operator-test")
			.contains("status=404")
			.doesNotContain("https://storage.example/operator-upload")
			.doesNotContain("operator-private-note");
	}

	@Test
	@DisplayName("신뢰 프록시 검증과 User-Agent 제어문자 제거 및 256자 상한을 감사 필터에 적용한다")
	void auditFilterValidatesClientIpAndSanitizesUserAgent() throws Exception {
		var untrustedFilter = new AdminOperatorAuditFilter(
			auditEventRepository,
			java.time.Clock.systemUTC(),
			TrustedClientIpResolver.fromCidrs("10.0.0.0/8")
		);
		var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
			"admin-test",
			"N/A",
			java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
		);
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
		try {
			var req1 = new MockHttpServletRequest("POST", "/admin/test-action");
			req1.setRemoteAddr("198.51.100.77");
			req1.addHeader("X-Forwarded-For", "203.0.113.99");
			req1.addHeader("User-Agent", "TestAgent\r\nInjected: 1\t" + "Z".repeat(300));
			untrustedFilter.doFilter(req1, new org.springframework.mock.web.MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain());

			var event1 = auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1).getFirst();
			assertThat(event1.clientIp()).isEqualTo("198.51.100.77");
			assertThat(event1.userAgent())
				.doesNotContain("\r")
				.doesNotContain("\n")
				.doesNotContain("\t")
				.hasSize(256);

			var req2 = new MockHttpServletRequest("POST", "/admin/test-action");
			req2.setRemoteAddr("10.0.0.5");
			req2.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.55");
			req2.addHeader("User-Agent", "\r\n\t\u0000\u007f");
			untrustedFilter.doFilter(req2, new org.springframework.mock.web.MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain());

			var event2 = auditEventRepository.findRecent(AdminAuditEventType.ADMIN_ACTION, 1).getFirst();
			assertThat(event2.clientIp()).isEqualTo("203.0.113.55");
			assertThat(event2.userAgent()).isEqualTo("missing");
		} finally {
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}
	}

	private String createReport() throws Exception {
		String response = mockMvc.perform(post("/api/v1/reports")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "clientSubmissionId": "admin-audit-report",
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "감사 로그 테스트 신고"
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(response, "$.data.id");
	}

	private String getAdminHtml(String path, MockHttpSession session) throws Exception {
		return mockMvc.perform(get(path)
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static String commandTokenFrom(String html) {
		Matcher matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private RequestPostProcessor commandToken(String pagePath) {
		return request -> {
			MockHttpSession session = sessionFrom(request);
			try {
				request.setSession(session);
				request.addParameter("commandToken", commandTokenFrom(getAdminHtml(pagePath, session)));
				return request;
			} catch (Exception exception) {
				throw new AssertionError(exception);
			}
		};
	}

	private static MockHttpSession sessionFrom(MockHttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session instanceof MockHttpSession mockHttpSession) {
			return mockHttpSession;
		}
		return new MockHttpSession();
	}
}
