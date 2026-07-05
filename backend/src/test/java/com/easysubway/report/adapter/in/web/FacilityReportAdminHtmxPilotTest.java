package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자 프론트 상호작용 기반(#1736)의 htmx·CSP·SRI 규약 참조 테스트.
 *
 * <p>이후 화면군 이슈(#1739~)는 이 패턴을 복제한다:
 * <ol>
 *   <li>일반 요청 = 셸을 포함한 풀페이지 응답.</li>
 *   <li>{@code HX-Request: true} = {@code th:fragment} 하나만 담은 부분 응답.</li>
 *   <li>부분 갱신 트리거는 {@code href}(no-JS fallback)와 {@code hx-get}을 함께 갖는다.</li>
 *   <li>관리자 응답에는 CSP 헤더가 있고, 스크립트는 self-host + SRI이며 외부 CDN 참조가 없다.</li>
 * </ol>
 */
@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 프론트 상호작용 기반 파일럿(#1736)")
class FacilityReportAdminHtmxPilotTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("일반 요청은 셸·스크립트·결과 fragment를 모두 포함한 풀페이지를 반환한다")
	void plainRequestReturnsFullPageWithShellAndScripts() throws Exception {
		createReport("풀페이지에서 확인할 신고");

		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("<!doctype html>")
			.contains("admin-sidebar")
			.contains("id=\"report-results\"")
			.contains("신고 상태 필터")
			.contains("풀페이지에서 확인할 신고");
	}

	@Test
	@DisplayName("HX-Request 요청은 셸 없이 report-results fragment만 반환한다")
	void htmxRequestReturnsOnlyTheResultsFragment() throws Exception {
		createReport("부분 응답에서 확인할 신고");

		String fragment = mockMvc.perform(get("/admin/reports/page")
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("id=\"report-results\"")
			.contains("신고 상태 필터")
			.contains("부분 응답에서 확인할 신고");
		assertThat(fragment)
			.doesNotContain("<!doctype html>")
			.doesNotContain("admin-sidebar")
			.doesNotContain("<html");
	}

	@Test
	@DisplayName("htmx 히스토리 복원 요청은 fragment가 아니라 셸을 포함한 풀페이지를 반환한다")
	void htmxHistoryRestoreRequestReturnsFullPageNotFragment() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.header("HX-Request", "true")
				.header("HX-History-Restore-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("<!doctype html>")
			.contains("admin-sidebar")
			.contains("id=\"report-results\"");
	}

	@Test
	@DisplayName("상태 필터 링크는 no-JS fallback href와 htmx hx-get을 함께 갖는다")
	void statusFilterLinksExposeBothHrefAndHxGet() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("href=\"/admin/reports/page?status=SUBMITTED\"")
			.contains("hx-get=\"/admin/reports/page?status=SUBMITTED\"")
			.contains("hx-target=\"#report-results\"")
			.contains("hx-swap=\"outerHTML\"")
			.contains("hx-push-url=\"true\"");
	}

	@Test
	@DisplayName("관리자 응답에 CSP 헤더가 있고 스크립트는 self-host + SRI이며 외부 CDN 참조가 없다")
	void adminResponseCarriesCspAndSelfHostedScriptsWithSri() throws Exception {
		String html = mockMvc.perform(get("/admin/reports/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
			.andExpect(header().string("Content-Security-Policy", containsString("object-src 'none'")))
			.andExpect(header().string("Content-Security-Policy", containsString("base-uri 'self'")))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("/vendor/htmx-2.0.10/htmx.min.js")
			.contains("/vendor/alpinejs-csp-3.15.12/cdn.min.js")
			.contains("integrity=\"sha384-")
			.contains("crossorigin=\"anonymous\"")
			// htmx의 인라인 indicator <style> 주입을 꺼 style-src 'self' 위반을 방지한다.
			.contains("name=\"htmx-config\"")
			.contains("includeIndicatorStyles");
		assertThat(html)
			.doesNotContain("cdn.jsdelivr.net")
			.doesNotContain("unpkg.com")
			.doesNotContain("cdnjs.cloudflare.com");
	}

	private String createReport(String description) throws Exception {
		String created = mockMvc.perform(post("/api/v1/reports")
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "stationId": "station-sangnoksu",
					  "facilityId": "facility-sangnoksu-elevator-1",
					  "reportType": "BROKEN",
					  "description": "%s"
					}
					""".formatted(description)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(created, "$.data.id");
	}
}
