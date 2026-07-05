package com.easysubway.route.adapter.in.web;

import com.easysubway.admin.metric.adapter.in.web.AnalyticsComparisonCard;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class RouteFeedbackAdminPageController {

	// 도움됨은 증가가, 도움안됨·현장차단은 감소가 개선 신호다(증감 카드 tone 판정).
	private static final Set<String> HIGHER_IS_BETTER = Set.of(AdminMetricKeys.ROUTE_FEEDBACK_HELPFUL);
	private static final List<String> TREND_KEYS = List.of(
		AdminMetricKeys.ROUTE_FEEDBACK_HELPFUL,
		AdminMetricKeys.ROUTE_FEEDBACK_NOT_HELPFUL,
		AdminMetricKeys.ROUTE_FEEDBACK_BLOCKED);

	private final RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler;
	private final AdminMetricQueryService metricQueryService;
	private final ObjectMapper objectMapper;

	RouteFeedbackAdminPageController(
		RouteFeedbackDashboardAssembler routeFeedbackDashboardAssembler,
		AdminMetricQueryService metricQueryService,
		ObjectMapper objectMapper
	) {
		this.routeFeedbackDashboardAssembler = routeFeedbackDashboardAssembler;
		this.metricQueryService = metricQueryService;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/admin/routes/feedback/page")
	String routeFeedbackDashboardPage(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model
	) {
		model.addAttribute("summary", routeFeedbackDashboardAssembler.assemble());
		populateTrends(days, model);
		return "admin/routes/feedback";
	}

	// 유형별 추이·증감 부분 갱신(#1744): 기간 버튼이 이 fragment를 htmx로 다시 불러 차트·증감·대체표를 갈아끼운다.
	@HxRequest
	@GetMapping("/admin/routes/feedback/trends")
	String routeFeedbackTrends(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model
	) {
		populateTrends(days, model);
		return "admin/routes/feedback :: trends";
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
}
