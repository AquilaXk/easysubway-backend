package com.easysubway.admin.metric.adapter.in.web;

import com.easysubway.admin.metric.application.service.AdminMetricQueryService;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 차트 데이터 API(#1739). Chart.js가 소비할 시계열 JSON을 돌려준다.
 *
 * <p>{@code GET /admin/dashboard/metrics?keys=reports.recent_24h,route.blocked_rate&days=7|30|90}.
 * 권한은 /admin/** 기본 규칙(ADMIN_VIEW)을 따른다. 기간 전환은 이 엔드포인트 재호출로 부분 갱신한다.
 */
@RestController
class AdminMetricChartController {

	private final AdminMetricQueryService metricQueryService;

	AdminMetricChartController(AdminMetricQueryService metricQueryService) {
		this.metricQueryService = metricQueryService;
	}

	@GetMapping("/admin/dashboard/metrics")
	AdminMetricChart metrics(
		@RequestParam(name = "keys", required = false) List<String> keys,
		@RequestParam(name = "days", defaultValue = "7") int days
	) {
		return metricQueryService.chart(keys == null ? List.of() : keys, days);
	}
}
