package com.easysubway.route.adapter.in.web;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.transit.domain.StationNotFoundException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"easysubway.admin.username=admin-user",
	"easysubway.admin.password=admin-test-password"
})
@AutoConfigureMockMvc
@DisplayName("공개 경로 검색 API")
class RouteSearchControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RouteSearchUseCase routeSearchUseCase;

	@Test
	@DisplayName("모바일 V1 계약으로 경로 검색 결과와 legacy ETA 표시를 반환한다")
	void searchRouteReturnsMobileV1Contract() throws Exception {
		when(routeSearchUseCase.searchRoute(argThat(command ->
			"station-sangnoksu".equals(command.originStationId())
				&& "station-sadang".equals(command.destinationStationId())
				&& command.mobilityType() == MobilityType.WHEELCHAIR
		))).thenReturn(foundRouteSearch());

		mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "mobilityType": "WHEELCHAIR"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.routeSearchId").value("route-search-1"))
			.andExpect(jsonPath("$.data.originStationName").value("상록수"))
			.andExpect(jsonPath("$.data.destinationStationName").value("사당"))
			.andExpect(jsonPath("$.data.status").value("FOUND"))
			.andExpect(jsonPath("$.data.lineId").value("line-4"))
			.andExpect(jsonPath("$.data.lineName").value("수도권 4호선"))
			.andExpect(jsonPath("$.data.score").value(18))
			.andExpect(jsonPath("$.data.burdenCost").value(18))
			.andExpect(jsonPath("$.data.estimatedDurationSeconds").value(420))
			.andExpect(jsonPath("$.data.walkingDistanceMeters").value(180))
			.andExpect(jsonPath("$.data.transferCount").value(0))
			.andExpect(jsonPath("$.data.steps[0].title").value("상록수역 진입"))
			.andExpect(jsonPath("$.data.warnings").isArray())
			.andExpect(jsonPath("$.data.blockedReasons").isArray())
			.andExpect(jsonPath("$.data.createdAt").value("2026-06-30T09:00:00"))
			.andExpect(jsonPath("$.data.etaSource").value("PLANNED"))
			.andExpect(jsonPath("$.data.routeQuality").value("LEGACY_STATIC"))
			.andExpect(jsonPath("$.data.commercialEtaEligible").value(false));
	}

	@Test
	@DisplayName("잘못된 요청은 인증 redirect가 아닌 JSON API error를 반환한다")
	void invalidRequestReturnsJsonApiError() throws Exception {
		mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "destinationStationId": "station-sadang",
					  "mobilityType": "WHEELCHAIR"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").exists());
	}

	@Test
	@DisplayName("지원하지 않는 역은 JSON not found error를 반환한다")
	void unsupportedStationReturnsJsonNotFound() throws Exception {
		when(routeSearchUseCase.searchRoute(argThat(command ->
			"station-missing".equals(command.originStationId())
		))).thenThrow(new StationNotFoundException());

		mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-missing",
					  "destinationStationId": "station-sadang",
					  "mobilityType": "WHEELCHAIR"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("역 정보를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("차단된 경로도 모바일이 표시할 수 있게 200과 blockedReasons를 반환한다")
	void blockedRouteReturnsBlockedResult() throws Exception {
		when(routeSearchUseCase.searchRoute(argThat(command ->
			"station-stair-only".equals(command.destinationStationId())
		))).thenReturn(blockedRouteSearch());

		mockMvc.perform(post("/api/v1/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-stair-only",
					  "mobilityType": "WHEELCHAIR"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("BLOCKED"))
			.andExpect(jsonPath("$.data.blockedReasons[0]").value("계단 없는 역 접근 경로를 확인할 수 없습니다."))
			.andExpect(jsonPath("$.data.etaSource").value("PLANNED"))
			.andExpect(jsonPath("$.data.routeQuality").value("LEGACY_STATIC"))
			.andExpect(jsonPath("$.data.commercialEtaEligible").value(false));
	}

	private RouteSearchResult foundRouteSearch() {
		return new RouteSearchResult(
			"route-search-1",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			List.of(new RouteStep(
				1,
				"entry",
				"상록수역 진입",
				"엘리베이터를 이용해 승강장으로 이동",
				"line-4",
				"수도권 4호선",
				"station-sangnoksu",
				"station-sangnoksu",
				7,
				180,
				false,
				"VERIFIED",
				false,
				"STATIC_BACKEND_V1",
				"STATIC_BACKEND_V1",
				"LEGACY_STATIC"
			)),
			List.of(),
			List.of(),
			LocalDateTime.of(2026, 6, 30, 9, 0)
		);
	}

	private RouteSearchResult blockedRouteSearch() {
		return new RouteSearchResult(
			"route-search-blocked-1",
			"station-sangnoksu",
			"상록수",
			"station-stair-only",
			"계단역",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.BLOCKED,
			"line-4",
			"수도권 4호선",
			0,
			List.of(),
			List.of(),
			List.of("계단 없는 역 접근 경로를 확인할 수 없습니다."),
			LocalDateTime.of(2026, 6, 30, 9, 10)
		);
	}
}
