package com.easysubway.route.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("경로 검색 API")
class RouteSearchControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("경로 검색을 생성하고 같은 식별자로 다시 조회한다")
	void postRouteSearchCreatesRouteAndGetReturnsStoredResult() throws Exception {
		var result = mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "mobilityType": "STROLLER"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("FOUND"))
			.andExpect(jsonPath("$.data.originStationName").value("상록수"))
			.andExpect(jsonPath("$.data.destinationStationName").value("사당"))
			.andExpect(jsonPath("$.data.lineName").value("수도권 4호선"))
			.andExpect(jsonPath("$.data.steps[0].title").value("상록수역에서 4호선 승강장으로 이동"))
			.andExpect(jsonPath("$.data.steps[0].estimatedMinutes").value(4))
			.andExpect(jsonPath("$.data.steps[0].distanceMeters").value(180))
			.andExpect(jsonPath("$.data.steps[0].includesStairs").value(false))
			.andExpect(jsonPath("$.data.steps[0].requiresAccessibilityCheck").value(true))
			.andReturn();

		String routeSearchId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.routeSearchId");

		mockMvc.perform(get("/api/v1/routes/{routeSearchId}", routeSearchId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.routeSearchId").value(routeSearchId))
			.andExpect(jsonPath("$.data.status").value("FOUND"));
	}

	@Test
	@DisplayName("공개 경로 검색은 잘못된 Basic 인증 헤더가 있어도 허용한다")
	void publicRouteSearchIgnoresInvalidBasicAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/routes/search")
				.with(httpBasic("wrong-user", "wrong-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "mobilityType": "SENIOR"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("FOUND"));
	}

	@Test
	@DisplayName("존재하지 않는 역으로 경로 검색을 요청하면 공통 404 응답을 반환한다")
	void postRouteSearchRejectsUnknownStation() throws Exception {
		mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "missing",
					  "destinationStationId": "station-sadang",
					  "mobilityType": "SENIOR"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("알 수 없는 경로 검색 식별자는 공통 404 응답을 반환한다")
	void getRouteSearchRejectsUnknownRouteSearchId() throws Exception {
		mockMvc.perform(get("/api/v1/routes/route-missing"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("경로 검색 결과를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("경로 피드백은 생성된 경로 검색 결과에 연결해 저장한다")
	void postRouteFeedbackSavesFeedbackForStoredRouteSearch() throws Exception {
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
		String routeSearchId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.routeSearchId");

		mockMvc.perform(post("/api/v1/routes/{routeSearchId}/feedback", routeSearchId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "rating": "HELPFUL",
					  "comment": "엘리베이터 안내가 실제 이동에 맞았어요"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.feedbackId").exists())
			.andExpect(jsonPath("$.data.routeSearchId").value(routeSearchId))
			.andExpect(jsonPath("$.data.userId").value("anonymous-user-1"))
			.andExpect(jsonPath("$.data.rating").value("HELPFUL"))
			.andExpect(jsonPath("$.data.comment").value("엘리베이터 안내가 실제 이동에 맞았어요"));
	}

	@Test
	@DisplayName("알 수 없는 경로 검색 결과에는 피드백을 저장하지 않는다")
	void postRouteFeedbackRejectsUnknownRouteSearchId() throws Exception {
		mockMvc.perform(post("/api/v1/routes/route-missing/feedback")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "rating": "HELPFUL",
					  "comment": "안내가 도움이 됐어요"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("경로 검색 결과를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("경로 피드백은 사용자 식별자와 평가가 필요하다")
	void postRouteFeedbackRequiresUserIdAndRating() throws Exception {
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
		String routeSearchId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.routeSearchId");

		mockMvc.perform(post("/api/v1/routes/{routeSearchId}/feedback", routeSearchId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": " ",
					  "rating": null,
					  "comment": " "
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("피드백 작성자를 확인해야 합니다."));

		mockMvc.perform(post("/api/v1/routes/{routeSearchId}/feedback", routeSearchId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "anonymous-user-1",
					  "rating": null,
					  "comment": "안내 확인이 필요했어요"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("피드백 평가를 선택해야 합니다."));
	}
}
