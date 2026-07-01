package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@DisplayName("관리자 경로 검색 현황 페이지")
class RouteSearchAdminPageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SaveRouteSearchPort saveRouteSearchPort;

	@Test
	@DisplayName("관리자는 경로 검색의 전체, 상태별, 이동 프로필별 건수를 확인한다")
	void adminGetsRouteSearchDashboardPage() throws Exception {
		saveRouteSearchPort.saveRouteSearch(foundRouteSearch(
			"route-search-found-1",
			MobilityType.SENIOR,
			List.of(routeStep(EtaSource.FALLBACK)),
			List.of(new RouteWarning(RouteWarningCode.LOW_DATA_CONFIDENCE))
		));
		saveRouteSearchPort.saveRouteSearch(foundRouteSearch("route-search-found-2", MobilityType.WHEELCHAIR));

		String html = mockMvc.perform(get("/admin/routes/searches/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("경로 검색 현황")
			.contains("전체 검색")
			.contains(">2<")
			.contains("경로 찾음")
			.contains("경로 차단")
			.contains(">0<")
			.contains("route_not_found_rate")
			.contains("이동 프로필별 검색")
			.contains("고령자")
			.contains("휠체어 사용자")
			.contains("지역별 사용량")
			.contains("지역")
			.contains("출발 검색")
			.contains("도착 검색")
			.contains("수도권")
			.contains("ETA source 현황")
			.contains("FALLBACK")
			.contains("provider 지연/장애 fallback")
			.contains("fallback 사유별 현황")
			.contains("LOW_DATA_CONFIDENCE")
			.contains("품질 신호 구분")
			.contains("PROVIDER_OUTAGE")
			.contains("알림 기준")
			.contains("route graph/strict accessibility source review")
			.doesNotContain("routeSearchId")
			.doesNotContain("station-sangnoksu");
	}

	@Test
	@DisplayName("관리자는 경로 검색 차단 사유별 건수를 확인한다")
	void adminGetsRouteSearchBlockedReasonCounts() throws Exception {
		saveRouteSearchPort.saveRouteSearch(blockedRouteSearch(
			"route-search-blocked-1",
			"계단 없는 역 접근 경로를 확인할 수 없습니다."
		));
		saveRouteSearchPort.saveRouteSearch(blockedRouteSearch(
			"route-search-blocked-2",
			"계단 없는 역 접근 경로를 확인할 수 없습니다."
		));

		String html = mockMvc.perform(get("/admin/routes/searches/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("차단 사유별 현황")
			.contains("차단 사유")
			.contains("계단 없는 역 접근 경로를 확인할 수 없습니다.")
			.contains(">2<")
			.doesNotContain("route-search-blocked-1");
	}

	@Test
	@DisplayName("경로 검색 현황 페이지는 관리자 인증을 요구한다")
	void routeSearchDashboardRequiresAdminAuthentication() throws Exception {
		mockMvc.perform(get("/admin/routes/searches/page"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/admin/routes/searches/page")
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
