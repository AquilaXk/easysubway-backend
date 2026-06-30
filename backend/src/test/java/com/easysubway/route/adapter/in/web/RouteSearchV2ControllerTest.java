package com.easysubway.route.adapter.in.web;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.in.RouteSearchUseCase;
import com.easysubway.route.domain.ConstraintMode;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
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
@DisplayName("공개 경로 검색 V2 API")
class RouteSearchV2ControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RouteSearchUseCase routeSearchUseCase;

	@Test
	@DisplayName("모바일 V2 계약으로 itinerary와 leg 단위 ETA 필드를 반환한다")
	void routeSearchV2ReturnsItineraryContract() throws Exception {
		when(routeSearchUseCase.searchRoute(argThat(command ->
			"station-sangnoksu".equals(command.originStationId())
				&& "station-sadang".equals(command.destinationStationId())
				&& command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.STRICT_STEP_FREE
		))).thenReturn(foundRouteSearch());

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.contractVersion").value("ROUTE_SEARCH_V2"))
			.andExpect(jsonPath("$.data.originStationId").value("station-sangnoksu"))
			.andExpect(jsonPath("$.data.destinationStationId").value("station-sadang"))
			.andExpect(jsonPath("$.data.departureTime").value("2026-06-30T09:15:00+09:00"))
			.andExpect(jsonPath("$.data.constraintMode").value("STRICT_STEP_FREE"))
			.andExpect(jsonPath("$.data.useRealtime").value(true))
			.andExpect(jsonPath("$.data.maxTransfers").value(3))
			.andExpect(jsonPath("$.data.alternativeCount").value(3))
			.andExpect(jsonPath("$.data.statuses[0]").value("FOUND"))
			.andExpect(jsonPath("$.data.statuses[1]").value("BLOCKED_ACCESSIBILITY"))
			.andExpect(jsonPath("$.data.statuses[2]").value("NO_TIMETABLE_SERVICE"))
			.andExpect(jsonPath("$.data.statuses[3]").value("REALTIME_UNAVAILABLE_PLANNED_USED"))
			.andExpect(jsonPath("$.data.statuses[4]").value("UNSUPPORTED_REGION"))
			.andExpect(jsonPath("$.data.statuses[5]").value("ROUTE_GRAPH_UNKNOWN"))
			.andExpect(jsonPath("$.data.itineraries[0].itineraryId").value("route-search-1-primary"))
			.andExpect(jsonPath("$.data.itineraries[0].status").value("FOUND"))
			.andExpect(jsonPath("$.data.itineraries[0].plannedArrivalTime").value("2026-06-30T09:22:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].realtimeArrivalTime").doesNotExist())
			.andExpect(jsonPath("$.data.itineraries[0].etaSource").value("STATIC_BACKEND_V1"))
			.andExpect(jsonPath("$.data.itineraries[0].etaConfidence").value("LOW"))
			.andExpect(jsonPath("$.data.itineraries[0].durationSeconds").value(420))
			.andExpect(jsonPath("$.data.itineraries[0].transferCount").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].walkingDistanceMeters").value(300))
			.andExpect(jsonPath("$.data.itineraries[0].accessibilityRisk.level").value("LOW"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].legType").value("ACCESS"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].plannedDepartureTime").value("2026-06-30T09:15:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].plannedArrivalTime").value("2026-06-30T09:19:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].plannedDepartureTime").value("2026-06-30T09:19:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[1].plannedArrivalTime").value("2026-06-30T09:22:00+09:00"))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].waitTimeSeconds").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].slackSeconds").value(0))
			.andExpect(jsonPath("$.data.itineraries[0].legs[0].etaSource").value("STATIC_BACKEND_V1"))
			.andExpect(jsonPath("$.data.itineraries[0].commercialEtaEligible").value(false));
	}

	@Test
	@DisplayName("잘못된 V2 출발 시간은 search 저장 전에 JSON 400으로 거부한다")
	void invalidRouteSearchV2DepartureTimeReturnsBadRequestBeforeSearch() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-99-99T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STRICT_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("출발 시간은 ISO offset 형식이어야 합니다."));

		verifyNoInteractions(routeSearchUseCase);
	}

	@Test
	@DisplayName("V2 prefer step-free는 mobility type을 유지한 채 command에 전달한다")
	void routeSearchV2PreferStepFreeKeepsMobilityType() throws Exception {
		when(routeSearchUseCase.searchRoute(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.PREFER_STEP_FREE
		))).thenReturn(foundRouteSearch());

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "PREFER_STEP_FREE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.constraintMode").value("PREFER_STEP_FREE"));
	}

	@Test
	@DisplayName("V2 PROFILE_DEFAULT는 기존 client 호환을 위해 mobility type 기본 constraint로 처리한다")
	void routeSearchV2ProfileDefaultUsesMobilityTypeDefaultConstraintMode() throws Exception {
		when(routeSearchUseCase.searchRoute(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.PREFER_STEP_FREE
		))).thenReturn(foundRouteSearch());

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "PROFILE_DEFAULT",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.constraintMode").value("PROFILE_DEFAULT"));
	}

	@Test
	@DisplayName("V2 allow-with-warnings는 constraintMode를 command와 응답에 반영한다")
	void routeSearchV2AllowWithWarningsKeepsConstraintMode() throws Exception {
		when(routeSearchUseCase.searchRoute(argThat(command ->
			command.mobilityType() == MobilityType.STROLLER
				&& command.constraintMode() == ConstraintMode.ALLOW_WITH_WARNINGS
		))).thenReturn(foundRouteSearch());

		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "ALLOW_WITH_WARNINGS",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.constraintMode").value("ALLOW_WITH_WARNINGS"));
	}

	@Test
	@DisplayName("알 수 없는 V2 constraintMode는 search 저장 전에 JSON 400으로 거부한다")
	void unknownRouteSearchV2ConstraintModeReturnsBadRequestBeforeSearch() throws Exception {
		mockMvc.perform(post("/api/v2/routes/search")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "originStationId": "station-sangnoksu",
					  "destinationStationId": "station-sadang",
					  "departureTime": "2026-06-30T09:15:00+09:00",
					  "mobilityType": "STROLLER",
					  "constraintMode": "STAIRS_ARE_FINE",
					  "useRealtime": true,
					  "maxTransfers": 3,
					  "alternativeCount": 3
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.message").value("지원하지 않는 이동 제약 조건입니다."));

		verifyNoInteractions(routeSearchUseCase);
	}

	private RouteSearchResult foundRouteSearch() {
		return new RouteSearchResult(
			"route-search-1",
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.STROLLER,
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
				4,
				180,
				false,
				"VERIFIED",
				false,
				"STATIC_BACKEND_V1",
				"STATIC_BACKEND_V1",
				"LEGACY_STATIC"
			), new RouteStep(
				2,
				"exit",
				"사당역 출구 이동",
				"출구 엘리베이터를 확인합니다.",
				"line-4",
				"수도권 4호선",
				"station-sadang",
				"station-sadang",
				3,
				120,
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
}
