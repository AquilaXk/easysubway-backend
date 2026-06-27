package com.easysubway.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 접근성 smoke")
class AdminAccessibilitySmokeTest {

	private static final Pattern COMMAND_TOKEN = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"");

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자 로그인 화면은 모바일 viewport와 form label, CSRF를 제공한다")
	void adminLoginPageHasAccessibleFormBaseline() throws Exception {
		String html = mockMvc.perform(get("/admin/login"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("<html lang=\"ko\"")
			.contains("name=\"viewport\"")
			.contains("<h1>관리자 로그인</h1>")
			.contains("for=\"username\"")
			.contains("id=\"username\"")
			.contains("for=\"password\"")
			.contains("id=\"password\"")
			.contains("name=\"_csrf\"");
	}

	@Test
	@DisplayName("관리자 주요 화면은 skip link, landmark, 로그아웃 form을 유지한다")
	void adminPagesKeepAccessibleShell() throws Exception {
		RequestPostProcessor admin = fullAdmin();

		assertAdminShell("/admin/dashboard/page", "통합 대시보드", admin);
		assertAdminShell("/admin/reports/page", "시설 신고 검수", admin);
		assertAdminShell("/admin/facilities/page", "시설 상태판", admin);
		assertAdminShell("/admin/batches/page", "배치 운영", admin);
		assertAdminShell("/admin/audits/privacy/page", "개인정보 조회 로그", admin);
	}

	@Test
	@DisplayName("관리자 오류와 validation 화면은 alert 의미와 기존 입력 확인 문구를 제공한다")
	void adminErrorAndValidationPagesExposeAlertSemantics() throws Exception {
		RequestPostProcessor admin = fullAdmin();

		String methodErrorHtml = mockMvc.perform(get("/admin/batches/transit-master-collection/runs/missing-run/retry")
				.with(admin))
			.andExpect(status().isMethodNotAllowed())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(methodErrorHtml)
			.contains("class=\"admin-shell\"")
			.contains("role=\"alert\"")
			.contains("aria-labelledby=\"admin-error-title\"")
			.contains("허용되지 않는 요청입니다");

		MockHttpSession session = new MockHttpSession();
		String token = commandTokenFrom(getAdminHtml("/admin/facilities/page", session, admin));
		String validationHtml = mockMvc.perform(post("/admin/facilities/facility-sangnoksu-elevator-1/page/status")
				.session(session)
				.with(admin)
				.with(csrf())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("commandToken", token))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(validationHtml)
			.contains("class=\"admin-shell\"")
			.contains("role=\"alert\"")
			.contains("aria-labelledby=\"form-error-summary-title\"")
			.contains("입력값을 확인해 주세요")
			.contains("시설 상태를 선택해야 합니다.");
	}

	private void assertAdminShell(String path, String expectedHeading, RequestPostProcessor admin) throws Exception {
		String html = getAdminHtml(path, new MockHttpSession(), admin);

		assertThat(html)
			.contains("<html lang=\"ko\"")
			.contains("name=\"viewport\"")
			.contains("href=\"#admin-content\"")
			.contains("class=\"admin-shell\"")
			.contains("aria-label=\"통합 관리자 화면\"")
			.contains("class=\"admin-topbar-row\" aria-label=\"관리자 실행 환경\"")
			.contains("id=\"admin-content\" class=\"admin-content-anchor\" tabindex=\"-1\"")
			.contains("<main class=\"admin-main\">")
			.contains("<h1>" + expectedHeading + "</h1>")
			.contains("aria-label=\"관리자 로그아웃\"")
			.contains("action=\"/admin/logout\"")
			.contains("name=\"_csrf\"")
			.doesNotContain("<main id=\"admin-content\"");
		assertThat(html.indexOf("href=\"#admin-content\"")).isLessThan(html.indexOf("class=\"admin-shell\""));
		assertThat(html.indexOf("class=\"admin-topbar-row\"")).isLessThan(html.indexOf("id=\"admin-content\""));
		assertThat(html.indexOf("id=\"admin-content\"")).isLessThan(html.indexOf("<header class=\"admin-page-head\">"));
	}

	private String getAdminHtml(String path, MockHttpSession session, RequestPostProcessor admin) throws Exception {
		return mockMvc.perform(get(path)
				.session(session)
				.with(admin))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static String commandTokenFrom(String html) {
		Matcher matcher = COMMAND_TOKEN.matcher(html);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private static RequestPostProcessor fullAdmin() {
		return user("admin-accessibility").authorities(
			List.of(
				"admin.view",
				"admin.report.review",
				"admin.master.edit",
				"admin.data.operate",
				"admin.audit.read",
				"admin.privacy-log.read",
				"admin.batch.retry"
			)
				.stream()
				.map(SimpleGrantedAuthority::new)
				.toList()
		);
	}
}
