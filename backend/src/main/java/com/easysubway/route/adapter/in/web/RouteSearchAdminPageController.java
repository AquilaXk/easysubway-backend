package com.easysubway.route.adapter.in.web;

import com.easysubway.admin.metric.adapter.in.web.AnalyticsComparisonCard;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.domain.BlockedStationRanking;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class RouteSearchAdminPageController {

	// 검색량은 높을수록, 차단률은 낮을수록 좋은 신호다(증감 카드 tone 판정).
	private static final Set<String> HIGHER_IS_BETTER = Set.of(AdminMetricKeys.ROUTE_SEARCHES);
	private static final List<String> TREND_KEYS =
		List.of(AdminMetricKeys.ROUTE_SEARCHES, AdminMetricKeys.ROUTE_BLOCKED_RATE);
	private static final int TOP_BLOCKED_STATIONS = 10;

	private final RouteSearchDashboardUseCase routeSearchDashboardUseCase;
	private final AdminMetricQueryService metricQueryService;
	private final ObjectMapper objectMapper;

	RouteSearchAdminPageController(
		RouteSearchDashboardUseCase routeSearchDashboardUseCase,
		AdminMetricQueryService metricQueryService,
		ObjectMapper objectMapper
	) {
		this.routeSearchDashboardUseCase = routeSearchDashboardUseCase;
		this.metricQueryService = metricQueryService;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/admin/routes/searches/page")
	String routeSearchDashboardPage(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model
	) {
		RouteSearchDashboardSummary summary = routeSearchDashboardUseCase.summarizeRouteSearches();
		model.addAttribute("summary", RouteSearchDashboardView.from(summary));
		model.addAttribute("blockedStationRankings",
			BlockedStationRankingRow.from(routeSearchDashboardUseCase.topBlockedStations(TOP_BLOCKED_STATIONS)));
		populateTrends(days, model);
		return "admin/routes/searches";
	}

	// 추이·증감 부분 갱신(#1744): 기간 버튼이 이 fragment를 htmx로 다시 불러 차트·증감 카드·대체표를 갈아끼운다.
	@HxRequest
	@GetMapping("/admin/routes/searches/trends")
	String routeSearchTrends(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model
	) {
		populateTrends(days, model);
		return "admin/routes/searches :: trends";
	}

	private void populateTrends(int days, Model model) {
		AdminMetricChart chart = metricQueryService.chart(TREND_KEYS, days);
		model.addAttribute("trendChart", chart);
		model.addAttribute("trendJson", toJson(chart));
		model.addAttribute("trendDays", chart.days());
		model.addAttribute("exportKeys", TREND_KEYS);
		model.addAttribute("comparisons", metricQueryService.compare(TREND_KEYS, days)
			.stream()
			.map(comparison -> AnalyticsComparisonCard.from(comparison, HIGHER_IS_BETTER.contains(comparison.key())))
			.toList());
	}

	// Chart.js가 읽을 데이터 섬(JSON). 직렬화 실패 시 빈 차트로 안전 폴백(details 표가 대체).
	private String toJson(AdminMetricChart chart) {
		try {
			return objectMapper.writeValueAsString(chart);
		} catch (JsonProcessingException exception) {
			return "{\"labels\":[],\"series\":[]}";
		}
	}

	/**
	 * 차단 상위 역 랭킹 행 뷰. 순위·역 허브 딥링크·최다 차단 행 강조를 표시용으로 정리한다.
	 */
	record BlockedStationRankingRow(
		int rank,
		String stationId,
		String stationName,
		long blockedCount,
		String hubUrl,
		boolean highlight
	) {

		static List<BlockedStationRankingRow> from(List<BlockedStationRanking> rankings) {
			long maxBlocked = rankings.stream().mapToLong(BlockedStationRanking::blockedCount).max().orElse(0);
			List<BlockedStationRankingRow> rows = new ArrayList<>(rankings.size());
			for (int index = 0; index < rankings.size(); index++) {
				BlockedStationRanking ranking = rankings.get(index);
				rows.add(new BlockedStationRankingRow(
					index + 1,
					ranking.stationId(),
					ranking.stationName(),
					ranking.blockedCount(),
					"/admin/stations/" + ranking.stationId() + "/page",
					ranking.blockedCount() == maxBlocked && maxBlocked > 0));
			}
			return rows;
		}
	}
}
