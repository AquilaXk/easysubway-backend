package com.easysubway.admin.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.authorization.AdminPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 알림 센터")
class AdminAlertControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("알림 전용 페이지는 셸과 함께 렌더된다")
	void alertsPageRendersWithShell() throws Exception {
		String html = mockMvc.perform(get("/admin/alerts")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("<!doctype html>")
			.contains("알림 센터")
			.contains("admin-sidebar")
			.doesNotContain("신고 급증·푸시 실패·배치 실패·데이터팩 blocker를 한 곳에서 확인합니다.");
	}

	@Test
	@DisplayName("HX-Request는 셸 없이 요약 패널 fragment만 반환한다")
	void hxRequestReturnsPanelFragmentOnly() throws Exception {
		String fragment = mockMvc.perform(get("/admin/alerts")
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment).contains("admin-alert-panel");
		assertThat(fragment)
			.doesNotContain("<!doctype html>")
			.doesNotContain("admin-sidebar");
	}

	@Test
	@DisplayName("topbar에 알림 벨이 렌더되고 no-JS는 알림 페이지로 이동한다")
	void topbarRendersAlertBell() throws Exception {
		String html = mockMvc.perform(get("/admin/alerts")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"admin-alert-center\"")
			.contains("x-data=\"alertCenter\"")
			.contains("aria-controls=\"admin-alert-live\"")
			.contains("id=\"admin-alert-live\" class=\"admin-alert-live\" role=\"region\" aria-label=\"알림 센터\"")
			.doesNotContain("class=\"admin-alert-bell\" aria-haspopup=\"dialog\"")
			// no-JS fallback: 벨은 알림 전용 페이지로 이동
			.contains("href=\"/admin/alerts\"");
		assertThat(html).containsOnlyOnce("role=\"region\" aria-label=\"알림 센터\"");
	}

	@Test
	@DisplayName("신호 화면 권한이 없는 계정은 빈 알림을 본다")
	void operatorWithoutSignalPermissionsSeesEmptyAlerts() throws Exception {
		// ADMIN_VIEW만 가진 계정: 제보·푸시·배치·데이터팩 화면이 안 보이므로 신호가 제외되어 빈 상태.
		RequestPostProcessor viewOnly = user("viewer")
			.authorities(new SimpleGrantedAuthority(AdminPermission.ADMIN_VIEW.authority()));

		String fragment = mockMvc.perform(get("/admin/alerts")
				.header("HX-Request", "true")
				.with(viewOnly))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment).contains("확인할 알림이 없습니다.");
	}
}
