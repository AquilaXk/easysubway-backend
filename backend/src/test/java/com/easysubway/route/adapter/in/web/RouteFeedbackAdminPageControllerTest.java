package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password",
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("관리자 경로 피드백 현황 페이지")
class RouteFeedbackAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 경로 피드백의 전체와 평점별 건수를 확인한다")
	void adminGetsRouteFeedbackDashboardPage() throws Exception {
		String routeSearchId = createRouteSearch();
		submitRouteFeedback(routeSearchId, "HELPFUL", "안내가 도움이 됐어요");
		submitRouteFeedback(routeSearchId, "NOT_HELPFUL", "실제 이동 상황과 달랐어요");
		submitRouteFeedback(routeSearchId, "BLOCKED_BY_REAL_WORLD", "엘리베이터가 막혀 있었어요");

		String html = mockMvc.perform(get("/admin/routes/feedback/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("경로 피드백 현황")
			.contains("전체 피드백")
			.contains(">3<")
			.contains("도움이 됨")
			.contains("도움이 안 됨")
			.contains("현장 차단")
			.contains("경로 안내가 실제 이동에 도움됨")
			.contains("경로 안내가 실제 이동과 맞지 않음")
			.contains("엘리베이터 고장, 공사, 폐쇄 등으로 이동 불가")
			.doesNotContain("anonymous-user-1")
			.doesNotContain("엘리베이터가 막혀 있었어요");
	}

	@Test
	@DisplayName("경로 피드백 현황 페이지는 관리자 인증을 요구한다")
	void routeFeedbackDashboardRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/routes/feedback/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/routes/feedback/page")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	private String createRouteSearch() throws Exception {
		var result = mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "mobilityType": "SENIOR"
					}
					"""))
			.andExpect(status().isOk())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.routeSearchId");
	}

	private void submitRouteFeedback(String routeSearchId, String rating, String comment) throws Exception {
		mockMvc.perform(post("/api/v1/routes/{routeSearchId}/feedback", routeSearchId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "rating": "%s",
					  "comment": "%s"
					}
					""".formatted(rating, comment)))
			.andExpect(status().isOk());
	}
}
