package com.easysubway.usage.adapter.in.web;

import com.easysubway.admin.metric.adapter.in.web.AnalyticsComparisonCard;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
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
class UserActivityAdminPageController {

	// 활성 사용자는 증가가, API 오류율은 감소가 개선 신호다(증감 카드 tone 판정).
	private static final Set<String> HIGHER_IS_BETTER = Set.of(AdminMetricKeys.USERS_ACTIVE);
	private static final List<String> TREND_KEYS =
		List.of(AdminMetricKeys.USERS_ACTIVE, AdminMetricKeys.API_ERROR_RATE);

	private final UserActivityDashboardUseCase userActivityDashboardUseCase;
	private final AdminMetricQueryService metricQueryService;
	private final ObjectMapper objectMapper;

	UserActivityAdminPageController(
		UserActivityDashboardUseCase userActivityDashboardUseCase,
		AdminMetricQueryService metricQueryService,
		ObjectMapper objectMapper
	) {
		this.userActivityDashboardUseCase = userActivityDashboardUseCase;
		this.metricQueryService = metricQueryService;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/admin/usage/activity/page")
	String userActivityDashboardPage(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model
	) {
		model.addAttribute("summary", UserActivityDashboardView.from(userActivityDashboardUseCase.summarizeUserActivity()));
		populateTrends(days, model);
		return "admin/usage/activity";
	}

	// 추이·증감 부분 갱신(#1744): 기간 버튼이 이 fragment를 htmx로 다시 불러 차트·증감·대체표를 갈아끼운다.
	@HxRequest
	@GetMapping("/admin/usage/activity/trends")
	String userActivityTrends(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model
	) {
		populateTrends(days, model);
		return "admin/usage/activity :: trends";
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
