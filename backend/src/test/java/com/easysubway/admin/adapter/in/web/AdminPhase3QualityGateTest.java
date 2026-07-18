package com.easysubway.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.navigation.AdminProgram;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 Phase 3 QA 회귀 게이트")
class AdminPhase3QualityGateTest {

	private static final List<FragmentEndpoint> FRAGMENT_ENDPOINTS = List.of(
		new FragmentEndpoint("/admin/reports/page", "id=\"report-results\""),
		new FragmentEndpoint("/admin/facilities/page", "id=\"facility-results\""),
		new FragmentEndpoint("/admin/stations/page", "id=\"station-results\""),
		new FragmentEndpoint("/admin/stations/station-sangnoksu/page?tab=reports", "id=\"station-hub\""),
		new FragmentEndpoint("/admin/dashboard/trends", "class=\"dashboard-trends-section\""),
		new FragmentEndpoint("/admin/alerts", "class=\"admin-alert-panel\""),
		new FragmentEndpoint("/admin/batches/page/live", "id=\"batch-live\""),
		new FragmentEndpoint("/admin/data-collections/page/live", "id=\"collection-live\""),
		new FragmentEndpoint("/admin/incidents/page/live", "id=\"incident-live\"")
	);

	private static final List<String> OPERATOR_PAGES = List.of(
		"/operator/accessibility-report/page",
		"/operator/repeated-broken-facilities/page",
		"/operator/data-collection-failures/page",
		"/operator/route-feedback-report/page",
		"/operator/push-notification-report/page"
	);

	// #2272 V6-00: operator surface는 login 1개와 report 5개로 고정한다.
	private static final List<String> OPERATOR_LOGIN_PAGES = List.of(
		"/operator/login"
	);

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자 전 프로그램은 CSP와 접근성 셸을 유지한다")
	void adminProgramsKeepCspAndAccessibleShell() throws Exception {
		for (AdminProgram program : AdminProgram.values()) {
			String html = mockMvc.perform(get(program.path())
					.with(fullAdmin()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
				.andReturn()
				.getResponse()
				.getContentAsString();

			assertThat(html)
				.as(program.path())
				.contains("href=\"#admin-content\"")
				.contains("class=\"admin-shell\"")
				.contains("id=\"admin-content\"")
				// #2277: 모든 admin surface가 workspace disclosure shell을 유지한다.
				.contains("class=\"admin-nav-workspace-toggle\"")
				.contains("aria-label=\"관리자 로그아웃\"")
				.doesNotContain("cdn.jsdelivr.net")
				.doesNotContain("unpkg.com")
				.doesNotContain("cdnjs.cloudflare.com");
		}
	}

	@Test
	@DisplayName("운영기관 리포트 페이지는 CSP와 전용 셸을 유지한다")
	void operatorPagesKeepCspAndShellBoundary() throws Exception {
		for (String path : OPERATOR_PAGES) {
			String html = mockMvc.perform(get(path)
					.with(user("operator").roles("OPERATOR_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
				.andReturn()
				.getResponse()
				.getContentAsString();

			assertThat(html)
				.as(path)
				.contains("운영기관")
				.contains("desktop-sidebar")
				.doesNotContain("command-palette")
				.doesNotContain("admin-alert-center");
		}
	}

	@Test
	@DisplayName("htmx 엔드포인트는 fragment만 반환하고 세션 만료는 HX-Refresh로 복구된다")
	void htmxEndpointsReturnFragmentsAndRefreshOnExpiredSession() throws Exception {
		for (FragmentEndpoint endpoint : FRAGMENT_ENDPOINTS) {
			String fragment = mockMvc.perform(get(endpoint.path())
					.header("HX-Request", "true")
					.with(fullAdmin()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

			assertThat(fragment)
				.as(endpoint.path())
				.contains(endpoint.marker())
				.doesNotContain("<!doctype html>")
				.doesNotContain("class=\"admin-shell\"");

			mockMvc.perform(get(endpoint.path())
					.header("HX-Request", "true")
					.header("Accept", "text/html"))
				.andExpect(status().isNoContent())
				.andExpect(header().string("HX-Refresh", "true"));
		}
	}

	// #2272 V6-00: operator surface 수를 source assertion으로 고정한다. login 1개 + report 5개 = 6개이며
	// admin surface(AdminProgram 29개)와 별개 경계다. v6 이관 중 이 분할이 흔들리면 테스트가 실패해야 한다.
	@Test
	@DisplayName("operator surface는 login 1개와 report 5개로 고정된다")
	void operatorSurfaceInventoryIsPinnedToLoginAndReports() throws Exception {
		assertThat(OPERATOR_LOGIN_PAGES).hasSize(1);
		assertThat(OPERATOR_PAGES).hasSize(5);
		assertThat(OPERATOR_LOGIN_PAGES.size() + OPERATOR_PAGES.size()).isEqualTo(6);

		assertThat(OPERATOR_LOGIN_PAGES).allSatisfy(path -> assertThat(path).startsWith("/operator/"));
		assertThat(OPERATOR_PAGES)
			.allSatisfy(path -> assertThat(path).startsWith("/operator/").endsWith("/page"))
			.doesNotContainAnyElementsOf(OPERATOR_LOGIN_PAGES);

		for (String path : OPERATOR_LOGIN_PAGES) {
			String html = mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
				.andReturn()
				.getResponse()
				.getContentAsString();

			assertThat(html)
				.as(path)
				.contains("class=\"login-card\"")
				.contains("아이디")
				.contains("비밀번호");
		}
	}

	private static RequestPostProcessor fullAdmin() {
		return user("phase3-admin").authorities(
			Arrays.stream(AdminPermission.values())
				.map(permission -> new SimpleGrantedAuthority(permission.authority()))
				.toList()
		);
	}

	private record FragmentEndpoint(String path, String marker) {
	}
}
