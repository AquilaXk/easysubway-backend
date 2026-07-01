package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.application.port.out.SaveRouteSearchPort;
import com.easysubway.route.domain.EtaSource;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.route.domain.RouteWarning;
import com.easysubway.route.domain.RouteWarningCode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
@DisplayName("관리자 경로 검색 요약 API")
class RouteSearchAdminApiControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SaveRouteSearchPort saveRouteSearchPort;

	@Test
	@DisplayName("관리자는 경로 검색 요약을 JSON으로 조회한다")
	void adminGetsRouteSearchSummary() throws Exception {
		saveRouteSearchPort.saveRouteSearch(foundRouteSearch(
			"route-search-found-1",
			MobilityType.SENIOR,
			List.of(routeStep(EtaSource.FALLBACK)),
			List.of(new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE))
		));
		saveRouteSearchPort.saveRouteSearch(foundRouteSearch("route-search-found-2", MobilityType.WHEELCHAIR));
		saveRouteSearchPort.saveRouteSearch(blockedRouteSearch(
			"route-search-blocked-1",
			"계단 없는 역 접근 경로를 확인할 수 없습니다."
		));

		var result = mockMvc.perform(get("/admin/routes/searches/summary")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalCount").value(3))
			.andExpect(jsonPath("$.data.foundCount").value(2))
			.andExpect(jsonPath("$.data.blockedCount").value(1))
			.andExpect(jsonPath("$.data.routeNotFoundRateLabel").value("33.3%"))
			.andExpect(jsonPath("$.data.mobilityTypeRows[0].label").value("고령자"))
			.andExpect(jsonPath("$.data.mobilityTypeRows[0].count").value(1))
			.andExpect(jsonPath("$.data.mobilityTypeRows[1].label").value("휠체어 사용자"))
			.andExpect(jsonPath("$.data.regionUsageRows[0].region").value("수도권"))
			.andExpect(jsonPath("$.data.regionUsageRows[0].originCount").value(3))
			.andExpect(jsonPath("$.data.regionUsageRows[0].destinationCount").value(3))
			.andExpect(jsonPath("$.data.blockedReasonRows[0].reason").value("계단 없는 역 접근 경로를 확인할 수 없습니다."))
			.andExpect(jsonPath("$.data.blockedReasonRows[0].count").value(1))
			.andExpect(jsonPath("$.data.etaSourceRows[0].code").value("FALLBACK"))
			.andExpect(jsonPath("$.data.etaSourceRows[0].label").value("provider 지연/장애 fallback"))
			.andExpect(jsonPath("$.data.fallbackReasonRows[0].reason").value("LOW_DATA_CONFIDENCE"))
			.andExpect(jsonPath("$.data.routeQualitySignalRows[0].signal").value("ROUTE_GRAPH_DATA_QUALITY"))
			.andExpect(jsonPath("$.data.alertThresholdRows[0].metric").value("route_not_found_rate"))
			.andReturn();

		String json = result.getResponse().getContentAsString();
		assertThat(json)
			.contains("PROVIDER_OUTAGE_OR_STALE_REALTIME")
			.contains("ROUTE_GRAPH_OR_STRICT_ACCESSIBILITY_BLOCK")
			.contains("provider outage/stale")
			.doesNotContain("route-search-blocked-1")
			.doesNotContain("station-sangnoksu")
			.doesNotContain("station-sadang");
	}

	@Test
	@DisplayName("경로 검색 요약 API는 관리자 인증을 요구한다")
	void routeSearchSummaryRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/routes/searches/summary"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/routes/searches/summary")
				.with(httpBasic("anonymous-user-1", "user-test-password")))
			.andExpect(status().isForbidden());
	}

	private RouteSearchResult foundRouteSearch(String routeSearchId, MobilityType mobilityType) {
		return foundRouteSearch(routeSearchId, mobilityType, List.of(), List.of());
	}

	private RouteSearchResult foundRouteSearch(
		String routeSearchId,
		MobilityType mobilityType,
		List<RouteStep> steps,
		List<RouteWarning> warnings
	) {
		return new RouteSearchResult(
			routeSearchId,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			mobilityType,
			RouteSearchStatus.FOUND,
			"line-4",
			"수도권 4호선",
			18,
			steps,
			warnings,
			List.of(),
			LocalDateTime.of(2026, 6, 17, 9, 0)
		);
	}

	private RouteStep routeStep(EtaSource etaSource) {
		return new RouteStep(
			1,
			"ride",
			"상록수에서 사당까지 이동",
			"수도권 4호선을 이용합니다.",
			"line-4",
			"수도권 4호선",
			"station-sangnoksu",
			"station-sadang",
			24,
			15000,
			false,
			"VERIFIED_STEP_FREE",
			false,
			etaSource.name(),
			"ESTIMATED_CONSTANT",
			"낮음"
		);
	}

	private RouteSearchResult blockedRouteSearch(String routeSearchId, String blockedReason) {
		return new RouteSearchResult(
			routeSearchId,
			"station-sangnoksu",
			"상록수",
			"station-sadang",
			"사당",
			MobilityType.WHEELCHAIR,
			RouteSearchStatus.BLOCKED,
			"line-4",
			"수도권 4호선",
			0,
			List.of(),
			List.of(),
			List.of(blockedReason),
			LocalDateTime.of(2026, 6, 17, 10, 0)
		);
	}
}
