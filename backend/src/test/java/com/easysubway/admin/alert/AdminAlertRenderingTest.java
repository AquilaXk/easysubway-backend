package com.easysubway.admin.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 알림 패널 템플릿 렌더링 검증(#1738). 실제 신호를 유발하기 어려워 서비스를 대체해 항목이 있는
 * 요약을 주입하고, 빈 상태와 항목이 함께 렌더되지 않는지(th:replace + th:unless 우선순위 함정)를 잠근다.
 */
@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("관리자 알림 패널 렌더링")
class AdminAlertRenderingTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private AdminAlertService alertService;

	@Test
	@DisplayName("알림이 있으면 항목·딥링크를 보여주고 빈 상태 문구는 나오지 않는다")
	void alertsPresentShowItemsNotEmptyState() throws Exception {
		when(alertService.summarize(any())).thenReturn(new AdminAlertSummary(List.of(
			new AdminAlertItem("datapack-blocker", "데이터팩 릴리즈 blocker", "1건",
				"warning", "/admin/datapack/candidates/page"))));

		String fragment = mockMvc.perform(get("/admin/alerts")
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("데이터팩 릴리즈 blocker")
			.contains("/admin/datapack/candidates/page")
			.contains("tone-warning")
			.doesNotContain("확인할 알림이 없습니다.");
	}

	@Test
	@DisplayName("알림이 없으면 빈 상태 문구만 나온다")
	void alertsEmptyShowsEmptyStateOnly() throws Exception {
		when(alertService.summarize(any())).thenReturn(AdminAlertSummary.empty());

		String fragment = mockMvc.perform(get("/admin/alerts")
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("확인할 알림이 없습니다.")
			.doesNotContain("admin-alert-list");
	}
}
