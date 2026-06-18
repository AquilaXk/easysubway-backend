package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@DisplayName("관리자 경로 피드백 요약 API")
class RouteFeedbackAdminApiControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("관리자는 경로 피드백 요약을 JSON으로 조회한다")
	void adminGetsRouteFeedbackSummary() throws Exception {
		String routeSearchId = createRouteSearch();
		submitRouteFeedback(routeSearchId, "HELPFUL", "안내가 도움이 됐어요");
		submitRouteFeedback(routeSearchId, "NOT_HELPFUL", "실제 이동 상황과 달랐어요");
		submitRouteFeedback(routeSearchId, "BLOCKED_BY_REAL_WORLD", "엘리베이터가 막혀 있었어요");

		var result = mockMvc.perform(get("/admin/routes/feedback/summary")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalCount").value(3))
			.andExpect(jsonPath("$.data.helpfulCount").value(1))
			.andExpect(jsonPath("$.data.notHelpfulCount").value(1))
			.andExpect(jsonPath("$.data.blockedByRealWorldCount").value(1))
			.andExpect(jsonPath("$.data.ratingRows[0].label").value("도움이 됨"))
			.andExpect(jsonPath("$.data.ratingRows[0].description").value("경로 안내가 실제 이동에 도움됨"))
			.andExpect(jsonPath("$.data.ratingRows[0].count").value(1))
			.andExpect(jsonPath("$.data.ratingRows[2].label").value("현장 차단"))
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].originStationName").value("상록수"))
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].destinationStationName").value("사당"))
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].mobilityTypeLabel").value("고령자"))
			.andReturn();

		String json = result.getResponse().getContentAsString();
		assertThat(json)
			.doesNotContain("anonymous-user-1")
			.doesNotContain(routeSearchId)
			.doesNotContain("엘리베이터가 막혀 있었어요");
	}

	@Test
	@DisplayName("경로 피드백 요약 API는 관리자 인증을 요구한다")
	void routeFeedbackSummaryRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/routes/feedback/summary"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/routes/feedback/summary")
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
