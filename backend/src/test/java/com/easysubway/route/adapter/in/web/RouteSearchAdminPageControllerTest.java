package com.easysubway.route.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easysubway.journey.analytics.JourneySearchKind;
import com.easysubway.journey.analytics.JourneySearchOutcome;
import com.easysubway.journey.analytics.JourneySearchRecord;
import com.easysubway.journey.analytics.JourneySearchRecordStore;
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

	@Autowired
	private JourneySearchRecordStore journeySearchRecordStore;

	@Test
	@DisplayName("관리자는 경로 검색의 전체, 상태별, 이동 프로필별 건수를 확인한다")
	void adminGetsRouteSearchDashboardPage() throws Exception {
		saveRouteSearchPort.saveRouteSearch(foundRouteSearch(
			"route-search-found-1",
			MobilityType.SENIOR,
			List.of(routeStep(EtaSource.PLANNED_WITHOUT_REALTIME)),
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
			.contains("도착 예정 출처 현황")
			.contains("PLANNED_WITHOUT_REALTIME")
			.contains("실시간 불가 · 계획 시각 안내")
			.contains("대체 경로 사유별 현황")
			.contains("LOW_DATA_CONFIDENCE")
			.contains("품질 신호 구분")
			.contains("PROVIDER_OUTAGE")
			.contains("알림 기준")
			.contains("경로 그래프·엄격 접근성 데이터 소스 검수")
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
			.contains("경로 그래프·접근성 데이터 품질 실패")
			.doesNotContain("route graph/accessibility data quality failure")
			.doesNotContain("route-search-blocked-1");
	}

	@Test
	@DisplayName("차단 상위 역 랭킹은 역 허브 딥링크로 표시된다")
	void routeSearchPageRendersBlockedStationRankingWithHubLink() throws Exception {
		saveRouteSearchPort.saveRouteSearch(blockedRouteSearch(
			"route-search-blocked-1", "계단 없는 역 접근 경로를 확인할 수 없습니다."));
		saveRouteSearchPort.saveRouteSearch(blockedRouteSearch(
			"route-search-blocked-2", "계단 없는 역 접근 경로를 확인할 수 없습니다."));

		String html = mockMvc.perform(get("/admin/routes/searches/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("차단 상위 역")
			.contains("/admin/stations/station-sangnoksu/page")
			.contains("/admin/stations/station-sadang/page")
			.contains("is-blocked-surge");
	}

	@Test
	@DisplayName("경로 검색 화면은 기간 추이 차트·증감 카드·기간 버튼을 렌더링한다")
	void routeSearchPageRendersTrendChartAndComparison() throws Exception {
		String html = mockMvc.perform(get("/admin/routes/searches/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html)
			.contains("id=\"route-search-trends\"")
			.contains("추이 기간 선택")
			.contains("/admin/routes/searches/trends")
			.contains("전 기간 대비 증감")
			.contains("route-search-trend-canvas")
			.contains("data-chart=")
			.contains("데이터 표로 보기")
			.contains("/js/admin/dashboard-charts.js");
	}

	@Test
	@DisplayName("기간 추이 fragment는 셸 없이 추이 영역만 반환한다")
	void routeSearchTrendsFragmentReturnsSectionOnly() throws Exception {
		String fragment = mockMvc.perform(get("/admin/routes/searches/trends")
				.param("days", "30")
				.header("HX-Request", "true")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(fragment)
			.contains("id=\"route-search-trends\"")
			.contains("최근 30일 추이")
			.doesNotContain("admin-shell")
			.doesNotContain("이동 프로필별 검색");
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

	@Test
	@DisplayName("관리자는 Journey 탐색 종류별 결과 분류·엔진 버전·접근성 판정과 기록 없는 날을 확인한다")
	void adminGetsJourneySearchAnalytics() throws Exception {
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
		journeySearchRecordStore.save(journeyRecord(today, JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND,
			"UNKNOWN", java.util.List.of("FASTEST", "STAIR_FREE"), "UNDETERMINED"));
		journeySearchRecordStore.save(journeyRecord(today, JourneySearchKind.DEPART_AT, JourneySearchOutcome.NO_ROUTE,
			"UNKNOWN", java.util.List.of(), "NOT_APPLICABLE"));
		journeySearchRecordStore.save(journeyRecord(today, JourneySearchKind.ARRIVE_BY, JourneySearchOutcome.TOO_COMPLEX,
			"EASYSUBWAY_RAPTOR_SUITE_V2/REVERSE_RANGE_RAPTOR/2.0.0", java.util.List.of(), "NOT_APPLICABLE"));
		journeySearchRecordStore.save(journeyRecord(today, JourneySearchKind.LAST_CONNECTION, JourneySearchOutcome.TIMEOUT,
			"UNKNOWN", java.util.List.of(), "NOT_APPLICABLE"));

		String html = mockMvc.perform(get("/admin/routes/searches/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String dump = System.getenv("EASYSUBWAY_EVIDENCE_HTML");
		if (dump != null) {
			java.nio.file.Files.writeString(java.nio.file.Path.of(dump), html);
		}
		assertThat(html)
			.contains("경로 탐색 종류별 결과 (최근 7일)")
			.contains("탐색 종류별 결과 분류")
			.contains("<th scope=\"row\">출발 시각</th>")
			.contains("<th scope=\"row\">도착 희망</th>")
			.contains("<th scope=\"row\">막차</th>")
			.doesNotContain("<th scope=\"row\">출발 시간대</th>")
			.contains("<th scope=\"col\">복잡도 초과</th>")
			.contains("<th scope=\"col\">시간 초과</th>")
			.contains("<th scope=\"col\">일시 불가</th>")
			.contains("일별 추이")
			.contains("기록 없음")
			.contains("EASYSUBWAY_RAPTOR_SUITE_V2/REVERSE_RANGE_RAPTOR/2.0.0")
			.contains("확인되지 않음")
			.contains("접근성 정보가 부족해 확정하지 못함")
			.contains("계단 없는 경로")
			.contains("기록 실패(서버 시작 이후)")
			.contains("aria-label=\"가로로 스크롤 가능한 탐색 종류별 결과 분류 표\"")
			.contains("aria-label=\"가로로 스크롤 가능한 일별 탐색 결과 추이 표\"")
			.doesNotContain("UNDETERMINED")
			.doesNotContain("NOT_APPLICABLE");
	}

	@Test
	@DisplayName("Journey 탐색 기록이 없으면 기록 없음으로 표시한다")
	void adminSeesNoRecordsExplicitly() throws Exception {
		String html = mockMvc.perform(get("/admin/routes/searches/page")
				.with(httpBasic("admin-user", "admin-test-password")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(html).contains("경로 탐색 종류별 결과").contains("기록 없음");
	}

	private static JourneySearchRecord journeyRecord(java.time.LocalDate day, JourneySearchKind kind,
		JourneySearchOutcome outcome, String engine, java.util.List<String> categories, String stairFree) {
		return new JourneySearchRecord(java.util.UUID.randomUUID().toString(), day, kind,
			outcome, outcome == JourneySearchOutcome.FOUND ? 200 : 422, null, engine, "STEP_FREE", categories, stairFree);
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
