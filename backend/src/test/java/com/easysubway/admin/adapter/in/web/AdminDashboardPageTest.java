package com.easysubway.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-test",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("통합 대시보드 재설계")
class AdminDashboardPageTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminMetricDailyRepository repository;

	@Test
	@DisplayName("핵심 카드가 클릭 가능하고 스냅샷 이력이 있으면 스파크라인을 그린다")
	void rendersClickableCardsWithSparkline() throws Exception {
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_PENDING, LocalDate.now().minusDays(1), 3));
		repository.save(AdminMetricDaily.scalar(AdminMetricKeys.REPORTS_PENDING, LocalDate.now(), 8));

		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"dashboard-card metric-cell\"")
			.contains("확인할 제보")
			.contains("href=\"/admin/reports/page\"")
			.contains("dashboard-spark")
			.contains("<polyline");
	}

	@Test
	@DisplayName("지표 스냅샷 수동 재실행은 command token으로 집계 후 대시보드로 리다이렉트한다")
	void manualSnapshotRerunRedirects() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String token = issueCommandToken(session);

		mockMvc.perform(post("/admin/dashboard/metrics/snapshot")
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password"))
				.with(csrf())
				.param("commandToken", token))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", "/admin/dashboard/page"));
	}

	@Test
	@DisplayName("추이 섹션은 차트 canvas와 접근성 대체 표·기간 선택을 렌더한다")
	void rendersTrendChartsWithAltTable() throws Exception {
		String html = mockMvc.perform(get("/admin/dashboard/page")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("class=\"trend-periods\"")
			.contains("class=\"trend-canvas\"")
			.contains("data-chart=")
			.contains("role=\"img\"")
			.contains("데이터 표로 보기")
			.contains("/js/admin/dashboard-charts.js");
	}

	@Test
	@DisplayName("기간 버튼은 HX-Request로 추이 fragment만 부분 갱신한다")
	void trendsHxFragmentSwitchesPeriod() throws Exception {
		String fragment = mockMvc.perform(get("/admin/dashboard/trends")
				.param("days", "30")
				.header("HX-Request", "true")
				.with(httpBasic("admin-test", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("class=\"trend-canvas\"")
			.doesNotContain("<!doctype html>")
			.doesNotContain("admin-sidebar");
	}

	private String issueCommandToken(MockHttpSession session) throws Exception {
		String html = mockMvc.perform(get("/admin/dashboard/page")
				.session(session)
				.with(httpBasic("admin-test", "admin-test-password")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		Matcher matcher = Pattern.compile("name=\"commandToken\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).as("command token in dashboard form").isTrue();
		return matcher.group(1);
	}
}
