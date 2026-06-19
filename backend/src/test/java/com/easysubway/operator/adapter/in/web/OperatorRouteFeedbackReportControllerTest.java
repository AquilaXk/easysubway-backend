package com.easysubway.operator.adapter.in.web;

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
	"easysubway.operator.username=operator-user",
	"easysubway.operator.password=operator-test-password",
	"easysubway.user.username=basic-user",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("운영기관 이동 불편 신고 분석 API")
class OperatorRouteFeedbackReportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("운영기관 계정은 이동 불편 신고 분석을 JSON으로 조회한다")
	void operatorGetsRouteFeedbackReport() throws Exception {
		String routeSearchId = createRouteSearch();
		submitRouteFeedback(routeSearchId, "HELPFUL", "안내가 도움이 됐어요");
		submitRouteFeedback(routeSearchId, "NOT_HELPFUL", "실제 이동 상황과 달랐어요");
		submitRouteFeedback(routeSearchId, "BLOCKED_BY_REAL_WORLD", "엘리베이터가 막혀 있었어요");

		mockMvc.perform(get("/operator/api/route-feedback-report")
				.with(httpBasic("operator-user", "operator-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalCount").value(3))
			.andExpect(jsonPath("$.data.helpfulCount").value(1))
			.andExpect(jsonPath("$.data.notHelpfulCount").value(1))
			.andExpect(jsonPath("$.data.blockedByRealWorldCount").value(1))
			.andExpect(jsonPath("$.data.ratingRows[0].label").value("도움이 됨"))
			.andExpect(jsonPath("$.data.ratingRows[0].description").value("경로 안내가 실제 이동에 도움됨"))
			.andExpect(jsonPath("$.data.ratingRows[0].count").value(1))
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].originStationName").value("상록수"))
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].destinationStationName").value("사당"))
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].mobilityTypeLabel").value("고령자"))
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].createdAtLabel").exists())
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].routeSearchId").doesNotExist())
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].userId").doesNotExist())
			.andExpect(jsonPath("$.data.recentBlockedFeedbacks[0].comment").doesNotExist());
	}

	@Test
	@DisplayName("운영기관 이동 불편 신고 분석 API는 운영기관 계정 인증을 요구한다")
	void routeFeedbackReportRequiresOperatorAuthentication() throws Exception {
		mockMvc.perform(get("/operator/api/route-feedback-report"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/operator/api/route-feedback-report")
				.with(httpBasic("basic-user", "user-test-password")))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/operator/api/route-feedback-report")
				.with(httpBasic("admin-user", "admin-test-password")))
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
				.with(httpBasic("basic-user", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "basic-user",
					  "rating": "%s",
					  "comment": "%s"
					}
					""".formatted(rating, comment)))
			.andExpect(status().isOk());
	}
}
