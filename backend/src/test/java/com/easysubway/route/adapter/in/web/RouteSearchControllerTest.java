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
}
