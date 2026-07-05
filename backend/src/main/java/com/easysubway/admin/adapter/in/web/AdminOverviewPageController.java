package com.easysubway.admin.adapter.in.web;

import com.easysubway.admin.alert.AdminAlertService;
import com.easysubway.admin.alert.AdminAlertSummary;
import com.easysubway.admin.authorization.AdminAuthorization;
import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.admin.metric.application.service.AdminDashboardCardService;
import com.easysubway.admin.metric.application.service.AdminDashboardCardService.DashboardCard;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.application.service.AdminMetricSnapshotService;
import com.easysubway.admin.metric.application.service.AdminMetricSnapshotStatusHolder;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.admin.navigation.AdminProgram;
import com.easysubway.collection.application.port.in.DataCollectionUseCase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wimdeblauwe.htmx.spring.boot.mvc.HxRequest;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.datapack.application.port.in.DatapackReleaseBlockerSummaryUseCase;
import com.easysubway.health.application.port.in.CheckHealthUseCase;
import com.easysubway.health.domain.HealthComponent;
import com.easysubway.health.domain.HealthStatus;
import com.easysubway.notification.application.port.in.PushNotificationDashboardUseCase;
import com.easysubway.notification.domain.PushNotificationDashboardSummary;
import com.easysubway.quality.application.port.in.DataQualityUseCase;
import com.easysubway.quality.domain.DataQualitySummary;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.route.application.port.in.RouteSearchDashboardUseCase;
import com.easysubway.route.domain.RouteSearchDashboardSummary;
import com.easysubway.usage.application.port.in.UserActivityDashboardUseCase;
import com.easysubway.usage.domain.UserActivityDashboardSummary;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class AdminOverviewPageController {

	private final DataQualityUseCase dataQualityUseCase;
	private final FacilityReportUseCase facilityReportUseCase;
	private final RouteSearchDashboardUseCase routeSearchDashboardUseCase;
	private final PushNotificationDashboardUseCase pushNotificationDashboardUseCase;
	private final UserActivityDashboardUseCase userActivityDashboardUseCase;
	private final DataCollectionUseCase dataCollectionUseCase;
	private final CheckHealthUseCase checkHealthUseCase;
	private final DatapackReleaseBlockerSummaryUseCase datapackReleaseBlockerSummaryUseCase;
	private final AdminDashboardCardService dashboardCardService;
	private final AdminAlertService alertService;
	private final AdminMetricSnapshotService metricSnapshotService;
	private final AdminMetricSnapshotStatusHolder metricSnapshotStatusHolder;
	private final AdminMetricQueryService metricQueryService;
	private final ObjectMapper objectMapper;

	AdminOverviewPageController(
		DataQualityUseCase dataQualityUseCase,
		FacilityReportUseCase facilityReportUseCase,
		RouteSearchDashboardUseCase routeSearchDashboardUseCase,
		PushNotificationDashboardUseCase pushNotificationDashboardUseCase,
		UserActivityDashboardUseCase userActivityDashboardUseCase,
		DataCollectionUseCase dataCollectionUseCase,
		CheckHealthUseCase checkHealthUseCase,
		DatapackReleaseBlockerSummaryUseCase datapackReleaseBlockerSummaryUseCase,
		AdminDashboardCardService dashboardCardService,
		AdminAlertService alertService,
		AdminMetricSnapshotService metricSnapshotService,
		AdminMetricSnapshotStatusHolder metricSnapshotStatusHolder,
		AdminMetricQueryService metricQueryService,
		ObjectMapper objectMapper
	) {
		this.dataQualityUseCase = dataQualityUseCase;
		this.facilityReportUseCase = facilityReportUseCase;
		this.routeSearchDashboardUseCase = routeSearchDashboardUseCase;
		this.pushNotificationDashboardUseCase = pushNotificationDashboardUseCase;
		this.userActivityDashboardUseCase = userActivityDashboardUseCase;
		this.dataCollectionUseCase = dataCollectionUseCase;
		this.checkHealthUseCase = checkHealthUseCase;
		this.datapackReleaseBlockerSummaryUseCase = datapackReleaseBlockerSummaryUseCase;
		this.dashboardCardService = dashboardCardService;
		this.alertService = alertService;
		this.metricSnapshotService = metricSnapshotService;
		this.metricSnapshotStatusHolder = metricSnapshotStatusHolder;
		this.metricQueryService = metricQueryService;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/admin/dashboard/page")
	String dashboardPage(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model,
		Authentication authentication
	) {
		DataQualitySummary quality = dataQualityUseCase.summarizeDataQuality();
		Map<FacilityReportStatus, Long> reportCounts = facilityReportUseCase.countReportsByStatus();
		RouteSearchDashboardSummary routes = routeSearchDashboardUseCase.summarizeRouteSearches();
		PushNotificationDashboardSummary push = pushNotificationDashboardUseCase.summarizePushNotifications();
		UserActivityDashboardSummary usage = userActivityDashboardUseCase.summarizeUserActivity();
		HealthStatus health = checkHealthUseCase.checkHealth();
		model.addAttribute("dashboard", new DashboardView(
			count(reportCounts, FacilityReportStatus.SUBMITTED) + count(reportCounts, FacilityReportStatus.UNDER_REVIEW),
			facilityReportUseCase.countReportsCreatedSince(LocalDateTime.now().minusHours(24)),
			quality.needsVerificationFacilityCount(),
			quality.delayedFacilityStatusCount(),
			routes.totalCount(),
			routes.blockedCount(),
			blockedRateLabel(routes),
			push.failedCount(),
			usage.totalActiveUsers(),
			usage.apiErrorRatePercent(),
			health.status(),
			health.service()
		));
		if (AdminAuthorization.hasPermission(authentication, AdminPermission.DATAPACK_READ)) {
			model.addAttribute("datapackReleaseSummary", datapackReleaseBlockerSummaryUseCase.summarize());
		}

		// 핵심 카드: 현재 값 + 7일 스파크라인 + 전일 대비. 화면 권한이 있는 카드만 노출(역할 인지).
		long pending = count(reportCounts, FacilityReportStatus.SUBMITTED)
			+ count(reportCounts, FacilityReportStatus.UNDER_REVIEW);
		double blockedRate = routes.totalCount() == 0
			? 0.0 : (double) routes.blockedCount() * 100 / routes.totalCount();
		List<AdminProgram> visible = AdminProgram.visibleTo(authentication);
		List<DashboardCard> cards = new ArrayList<>();
		if (visible.contains(AdminProgram.REPORTS)) {
			cards.add(dashboardCardService.card("확인할 제보", AdminProgram.REPORTS.path(),
				AdminMetricKeys.REPORTS_PENDING, pending, String.valueOf(pending)));
		}
		if (visible.contains(AdminProgram.FACILITIES)) {
			long needsVerification = quality.needsVerificationFacilityCount();
			cards.add(dashboardCardService.card("확인 필요 시설", AdminProgram.FACILITIES.path(),
				AdminMetricKeys.FACILITIES_NEEDS_VERIFICATION, needsVerification, String.valueOf(needsVerification)));
		}
		if (visible.contains(AdminProgram.ROUTE_SEARCHES)) {
			cards.add(dashboardCardService.card("경로 차단률", AdminProgram.ROUTE_SEARCHES.path(),
				AdminMetricKeys.ROUTE_BLOCKED_RATE, blockedRate, String.format("%.1f%%", blockedRate)));
		}
		if (visible.contains(AdminProgram.PUSH)) {
			cards.add(dashboardCardService.card("푸시 실패", AdminProgram.PUSH.path(),
				AdminMetricKeys.PUSH_FAILED, push.failedCount(), String.valueOf(push.failedCount())));
		}
		model.addAttribute("cards", cards);

		// 긴급 줄: 알림 센터 신호 요약(있을 때만). 지표 스냅샷 마지막 실행 상태.
		model.addAttribute("alertSummary", alertService.summarize(authentication));
		model.addAttribute("snapshotStatus", metricSnapshotStatusHolder.latest().orElse(null));

		// 추이 섹션: 기간(7/30/90) 라인 차트 2개. 기간 전환은 htmx 부분 갱신(fragment 재호출).
		populateTrends(days, model);
		return "admin/dashboard";
	}

	// 추이 섹션 부분 갱신(#1739): 기간 버튼이 이 fragment를 htmx로 다시 불러 차트·대체표를 갈아끼운다.
	@HxRequest
	@GetMapping("/admin/dashboard/trends")
	String dashboardTrends(
		@RequestParam(name = "days", defaultValue = "7") int days,
		Model model
	) {
		populateTrends(days, model);
		return "admin/fragments/dashboard-trends :: section";
	}

	private void populateTrends(int days, Model model) {
		List<TrendChart> trends = List.of(
			trendChart("trend-reports", "제보 추이",
				List.of(AdminMetricKeys.REPORTS_RECENT_24H, AdminMetricKeys.REPORTS_PENDING), days),
			trendChart("trend-quality", "경로 차단률·API 오류율 추이",
				List.of(AdminMetricKeys.ROUTE_BLOCKED_RATE, AdminMetricKeys.API_ERROR_RATE), days));
		model.addAttribute("trends", trends);
		// 정규화된 실제 기간(허용 밖 입력은 서비스가 7로 되돌리므로 첫 차트에서 읽는다).
		model.addAttribute("trendDays", trends.get(0).data().days());
	}

	private TrendChart trendChart(String id, String title, List<String> keys, int days) {
		AdminMetricChart chart = metricQueryService.chart(keys, days);
		return new TrendChart(id, title, chart, toJson(chart));
	}

	// Chart.js가 읽을 데이터 섬(JSON). 직렬화 실패 시 빈 차트로 안전 폴백.
	private String toJson(AdminMetricChart chart) {
		try {
			return objectMapper.writeValueAsString(chart);
		} catch (JsonProcessingException exception) {
			return "{\"labels\":[],\"series\":[]}";
		}
	}

	record TrendChart(String id, String title, AdminMetricChart data, String json) {
	}

	// 지표 스냅샷 수동 재실행(#1739). 스케줄과 별개로 지금 즉시 오늘 집계를 다시 돌린다(멱등).
	@PostMapping("/admin/dashboard/metrics/snapshot")
	@PreAuthorize("hasAuthority('admin.data.operate')")
	String rerunSnapshot(RedirectAttributes redirectAttributes) {
		try {
			metricSnapshotService.snapshotToday();
			redirectAttributes.addFlashAttribute("flashMessage", "지표 스냅샷을 다시 집계했습니다.");
			redirectAttributes.addFlashAttribute("flashTone", "good");
		} catch (RuntimeException exception) {
			redirectAttributes.addFlashAttribute("flashMessage", "지표 스냅샷 집계에 실패했습니다.");
			redirectAttributes.addFlashAttribute("flashTone", "failure");
		}
		return "redirect:/admin/dashboard/page";
	}

	@GetMapping("/admin/system/page")
	String systemPage(Model model) {
		HealthStatus health = checkHealthUseCase.checkHealth();
		List<DataCollectionRun> runs = dataCollectionUseCase.listRecentRuns(5);
		PushNotificationDashboardSummary push = pushNotificationDashboardUseCase.summarizePushNotifications();
		UserActivityDashboardSummary usage = userActivityDashboardUseCase.summarizeUserActivity();
		model.addAttribute("health", health);
		model.addAttribute("healthComponents", health.components().stream().map(HealthComponentRow::from).toList());
		model.addAttribute("runs", runs.stream().map(CollectionRunRow::from).toList());
		model.addAttribute("push", push);
		model.addAttribute("usage", usage);
		return "admin/system";
	}

	private static long count(Map<FacilityReportStatus, Long> counts, FacilityReportStatus status) {
		return counts.getOrDefault(status, 0L);
	}

	private static String blockedRateLabel(RouteSearchDashboardSummary summary) {
		if (summary.totalCount() == 0) {
			return "0.0%";
		}
		return String.format("%.1f%%", (double) summary.blockedCount() * 100 / summary.totalCount());
	}

	record DashboardView(
		long pendingReports,
		long recentReports,
		long needsVerificationFacilities,
		long delayedFacilities,
		long routeSearches,
		long blockedRoutes,
		String blockedRate,
		long failedPushes,
		long activeUsers,
		String apiErrorRate,
		String healthStatus,
		String serviceName
	) {
	}

	record CollectionRunRow(
		String runId,
		String source,
		String status,
		String requestedBy,
		String startedAt,
		String completedAt,
		int collectedCount,
		String failureMessage
	) {

		static CollectionRunRow from(DataCollectionRun run) {
			return new CollectionRunRow(
				run.runId(),
				run.source().name(),
				run.status().name(),
				run.requestedBy(),
				String.valueOf(run.startedAt()),
				String.valueOf(run.completedAt()),
				run.collectedCount(),
				run.failureMessage()
			);
		}
	}

	record HealthComponentRow(
		String name,
		String status,
		String label,
		String reason
	) {

		static HealthComponentRow from(HealthComponent component) {
			return new HealthComponentRow(
				component.name(),
				component.status(),
				component.label(),
				component.reason()
			);
		}
	}
}
