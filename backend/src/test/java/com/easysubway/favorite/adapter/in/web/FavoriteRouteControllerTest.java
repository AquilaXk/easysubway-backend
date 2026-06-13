package com.easysubway.favorite.adapter.in.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.adapter.out.persistence.InMemoryRouteSearchRepository;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.user.username=anonymous-user-1",
	"easysubway.user.password=user-test-password"
})
@AutoConfigureMockMvc
@DisplayName("즐겨찾기 경로 API")
class FavoriteRouteControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryRouteSearchRepository routeSearchRepository;

	@Test
	@DisplayName("인증 사용자 기준으로 경로를 저장하고 목록 조회와 삭제를 처리한다")
	void favoriteRoutesCanBeSavedListedAndRemoved() throws Exception {
		routeSearchRepository.saveRouteSearch(routeSearch("route-search-controller-1"));

		mockMvc.perform(post("/api/v1/me/favorites/routes")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "userId": "spoofed-user",
					  "routeSearchId": "route-search-controller-1"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value("anonymous-user-1"))
			.andExpect(jsonPath("$.data.favoriteRouteId").value("route-search-controller-1"))
			.andExpect(jsonPath("$.data.routeSearchId").value("route-search-controller-1"))
			.andExpect(jsonPath("$.data.originStationName").value("상록수"))
			.andExpect(jsonPath("$.data.destinationStationName").value("사당"))
			.andExpect(jsonPath("$.data.lineName").value("수도권 4호선"))
			.andExpect(jsonPath("$.data.addedAt").isNotEmpty());

		mockMvc.perform(get("/api/v1/me/favorites/routes")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].routeSearchId").value("route-search-controller-1"))
			.andExpect(jsonPath("$.data[0].originStationName").value("상록수"));

		mockMvc.perform(delete("/api/v1/me/favorites/routes/route-search-controller-1")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		mockMvc.perform(get("/api/v1/me/favorites/routes")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	@DisplayName("존재하지 않는 경로 검색 결과는 공통 404 응답으로 거부한다")
	void favoriteRoutesRejectUnknownRouteSearch() throws Exception {
		mockMvc.perform(post("/api/v1/me/favorites/routes")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "routeSearchId": "missing-route-search"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.message").value("경로 검색 결과를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("빈 경로 검색 식별자는 공통 400 응답으로 거부한다")
	void favoriteRoutesRejectBlankRouteSearchId() throws Exception {
		mockMvc.perform(post("/api/v1/me/favorites/routes")
				.with(httpBasic("anonymous-user-1", "user-test-password"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "routeSearchId": ""
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("경로 검색 식별자가 필요합니다."));
	}

	@Test
	@DisplayName("즐겨찾기 경로 목록은 인증된 사용자만 조회할 수 있다")
	void favoriteRoutesRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/me/favorites/routes"))
			.andExpect(status().isUnauthorized());
	}

	private RouteSearchResult routeSearch(String routeSearchId) {
		return new RouteSearchResult(
			routeSearchId,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.SENIOR,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			90,
			List.of(),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 13, 9, 0)
		);
	}
}
