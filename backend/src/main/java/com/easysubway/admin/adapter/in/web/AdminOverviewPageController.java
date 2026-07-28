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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
	private static final DateTimeFormatter SNAPSHOT_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

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
		long pending = count(reportCounts, FacilityReportStatus.SUBMITTED)
			+ count(reportCounts, FacilityReportStatus.UNDER_REVIEW);
		RouteSearchDashboardSummary routes = routeSearchDashboardUseCase.summarizeRouteSearches();
		PushNotificationDashboardSummary push = pushNotificationDashboardUseCase.summarizePushNotifications();
		UserActivityDashboardSummary usage = userActivityDashboardUseCase.summarizeUserActivity();
		HealthStatus health = checkHealthUseCase.checkHealth();
		var snapshotStatus = metricSnapshotStatusHolder.latest().orElse(null);
		model.addAttribute("dashboard", new DashboardView(
			pending,
			facilityReportUseCase.countReportsCreatedSince(LocalDateTime.now().minusHours(24)),
			quality.needsVerificationFacilityCount(),
			quality.delayedFacilityStatusCount(),
			routes.totalCount(),
			routes.blockedCount(),
			blockedRateLabel(routes),
			push.failedCount(),
			usage.totalActiveUsers(),
			usage.apiErrorRatePercent(),
			quality.totalFacilities(),
			usage.totalApiRequests() > 0,
			apiNormalRateLabel(usage),
			apiNormalRate(usage),
			health.status(),
			health.service()
		));
		model.addAttribute("snapshotStatus", snapshotStatus);
		model.addAttribute("snapshotBasisTime",
			LocalDateTime.now().format(SNAPSHOT_TIME_FORMAT));
		// 데이터팩 출시 준비: 권한 있을 때만 조회. 상세 표의 차단 요인 행은 0건을 숨기고 비-0만 노출한다(#2349).
		DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary datapackSummary = null;
		if (AdminAuthorization.hasPermission(authentication, AdminPermission.DATAPACK_READ)) {
			datapackSummary = datapackReleaseBlockerSummaryUseCase.summarize();
			model.addAttribute("datapackReleaseSummary", datapackSummary);
		}
		List<DatapackBlockerRow> datapackBlockerRows = datapackBlockerRows(datapackSummary);
		model.addAttribute("datapackBlockerRows", datapackBlockerRows);
		model.addAttribute("datapackHiddenBlockerRowCount", DATAPACK_BLOCKER_ROW_TOTAL - datapackBlockerRows.size());

		// 핵심 카드: 현재 값 + 7일 스파크라인 + 전일 대비. 화면 권한이 있는 카드만 노출(역할 인지).
		long needsVerification = quality.needsVerificationFacilityCount();
		double blockedRate = routes.totalCount() == 0
			? 0.0 : (double) routes.blockedCount() * 100 / routes.totalCount();
		List<AdminProgram> visible = AdminProgram.visibleTo(authentication);
		List<DashboardCard> cards = new ArrayList<>();
		if (visible.contains(AdminProgram.REPORTS)) {
			cards.add(dashboardCardService.card("확인할 제보", AdminProgram.REPORTS.path(),
				AdminMetricKeys.REPORTS_PENDING, pending, String.valueOf(pending)));
		}
		if (visible.contains(AdminProgram.FACILITIES)) {
			cards.add(dashboardCardService.card("확인 필요 시설", AdminProgram.FACILITIES.path(),
				AdminMetricKeys.FACILITIES_NEEDS_VERIFICATION, needsVerification, String.valueOf(needsVerification)));
		}
		if (visible.contains(AdminProgram.ROUTE_SEARCHES)) {
			cards.add(dashboardCardService.card("경로 차단률", AdminProgram.ROUTE_SEARCHES.path(),
				AdminMetricKeys.ROUTE_BLOCKED_RATE, blockedRate, "%.1f%%".formatted(blockedRate)));
		}
		if (visible.contains(AdminProgram.PUSH)) {
			cards.add(dashboardCardService.card("푸시 실패", AdminProgram.PUSH.path(),
				AdminMetricKeys.PUSH_FAILED, push.failedCount(), String.valueOf(push.failedCount())));
		}
		model.addAttribute("cards", cards);

		// 확인 필요(#2349): 트리아지 최우선 통합 카드. 제보·시설·데이터팩 차단 요인 중 0건인 항목은 뺀다.
		List<TriageItem> triageItems = new ArrayList<>();
		if (visible.contains(AdminProgram.REPORTS) && pending > 0) {
			triageItems.add(new TriageItem("확인할 제보", AdminProgram.REPORTS.path(), pending));
		}
		if (visible.contains(AdminProgram.FACILITIES) && needsVerification > 0) {
			triageItems.add(new TriageItem("확인 필요 시설", AdminProgram.FACILITIES.path(), needsVerification));
		}
		if (datapackSummary != null && datapackSummary.totalBlockers() > 0) {
			triageItems.add(new TriageItem(
				"데이터팩 차단 요인", AdminProgram.DATAPACK_PIPELINE.path(), datapackSummary.totalBlockers()));
		}
		model.addAttribute("triageItems", triageItems);
		model.addAttribute("weeklyReport", weeklyReport());

		// 긴급 줄: 알림 센터 신호 요약(있을 때만). 지표 스냅샷 마지막 실행 상태.
		model.addAttribute("alertSummary", alertService.summarize(authentication));

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
		model.addAttribute("trendDays", trends.getFirst().data().days());
	}

	private TrendChart trendChart(String id, String title, List<String> keys, int days) {
		AdminMetricChart chart = metricQueryService.chart(keys, days);
		return new TrendChart(id, title, chart, toJson(chart), chart.empty());
	}

	// Chart.js가 읽을 데이터 섬(JSON). 직렬화 실패 시 빈 차트로 안전 폴백.
	private String toJson(AdminMetricChart chart) {
		try {
			return objectMapper.writeValueAsString(chart);
		} catch (JsonProcessingException exception) {
			return "{\"labels\":[],\"series\":[]}";
		}
	}

	// empty는 조회 기간 내 데이터가 전무한지(#2327) — dashboard-trends fragment가 canvas 대신 empty-state를
	// 렌더하는 분기에 쓴다. 파생 메서드가 아니라 canonical record 컴포넌트로 둔 이유: SpringEL 프로퍼티 접근
	// (${trend.empty})은 package-private 클래스에서 일반 getter(isXxx) reflection을 "public 클래스만" 허용해
	// 실패하지만, record 컴포넌트 accessor는 별도 특례 경로로 접근 가능하다.
	record TrendChart(String id, String title, AdminMetricChart data, String json, boolean empty) {
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
		return "%.1f%%".formatted((double) summary.blockedCount() * 100 / summary.totalCount());
	}

	private WeeklyReportView weeklyReport() {
		AdminMetricChart chart = metricQueryService.chart(List.of(AdminMetricKeys.REPORTS_RECENT_24H), 7);
		List<Double> values = chart.series().getFirst().values();
		Map<LocalDate, Double> valuesByDate = new HashMap<>();
		for (int index = 0; index < chart.labels().size(); index++) {
			valuesByDate.put(LocalDate.parse(chart.labels().get(index)), values.get(index));
		}
		LocalDate today = LocalDate.parse(chart.labels().getLast());
		LocalDate monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		List<WeeklyReportDay> days = new ArrayList<>(7);
		int presentDays = 0;
		for (int index = 0; index < 7; index++) {
			LocalDate date = monday.plusDays(index);
			Double value = valuesByDate.get(date);
			if (value != null) {
				presentDays++;
			}
			days.add(new WeeklyReportDay(
				weekdayLabel(date),
				date.format(DateTimeFormatter.ofPattern("M.d")),
				formatMetricCount(value),
				date.equals(today),
				date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
					|| date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY));
		}
		return new WeeklyReportView(days, weeklyCoverageLabel(presentDays));
	}

	static String weeklyCoverageLabel(int presentDays) {
		return presentDays == 0 ? "집계 대기" : "%d일 집계".formatted(presentDays);
	}

	private static String weekdayLabel(LocalDate date) {
		return switch (date.getDayOfWeek()) {
			case MONDAY -> "월";
			case TUESDAY -> "화";
			case WEDNESDAY -> "수";
			case THURSDAY -> "목";
			case FRIDAY -> "금";
			case SATURDAY -> "토";
			case SUNDAY -> "일";
		};
	}

	private static String formatMetricCount(Double value) {
		return value == null ? "—" : Long.toString(Math.round(value));
	}

	private static double apiNormalRate(UserActivityDashboardSummary usage) {
		if (usage.totalApiRequests() == 0) {
			return 0.0;
		}
		return (double) (usage.totalApiRequests() - usage.totalApiErrors()) * 100 / usage.totalApiRequests();
	}

	private static String apiNormalRateLabel(UserActivityDashboardSummary usage) {
		if (usage.totalApiRequests() == 0) {
			return "—";
		}
		return String.format(Locale.ROOT, "%.1f%%", apiNormalRate(usage));
	}

	// 데이터팩 출시 준비 상세 표(#2349, #2352 리뷰로 10개로 확장): 후보 게이트·별칭·격리·소스 최신성·
	// 수동 오버라이드·시설 근거·경로 게이트·콜백 정합성 확인·증거 번들 검증·매니페스트 서명 10개 차단 요인
	// 카테고리 중 0건은 숨기고 비-0만 노출한다(뷰 모델 한정 가공, DatapackReleaseBlockerSummaryUseCase
	// 집계 로직은 변경하지 않는다). 10개 행의 합은 항상 datapackReleaseSummary.totalBlockers()와
	// 일치해야 한다(트리아지 카드·blocker-total 표기와의 정합, #2352 리뷰 지적).
	private static final int DATAPACK_BLOCKER_ROW_TOTAL = 10;

	private static List<DatapackBlockerRow> datapackBlockerRows(
		DatapackReleaseBlockerSummaryUseCase.DatapackReleaseBlockerSummary summary
	) {
		if (summary == null) {
			return List.of();
		}
		List<DatapackBlockerRow> rows = new ArrayList<>();
		addBlockerRow(rows, "후보 게이트", summary.candidateGateBlockers());
		addBlockerRow(rows, "별칭", summary.aliasBlockers());
		addBlockerRow(rows, "격리", summary.quarantineBlockers());
		addBlockerRow(rows, "소스 최신성", summary.sourceFreshnessBlockers());
		addBlockerRow(rows, "수동 오버라이드", summary.manualOverrideBlockers());
		addBlockerRow(rows, "시설 근거", summary.facilityBlockers());
		addBlockerRow(rows, "경로 게이트", summary.routeGateBlockers());
		addBlockerRow(rows, "콜백 정합성 확인", summary.callbackReconciliationBlockers());
		// "증거 번들" 단독 라벨은 위 sha·워크플로 상세 표의 evidenceBundleSha256 행(같은 details 안,
		// 같은 <th scope="row">증거 번들</th> 마크업)과 충돌해 "증거 번들 검증"으로 구분한다(#2352 리뷰).
		addBlockerRow(rows, "증거 번들 검증", summary.evidenceBundleBlockers());
		addBlockerRow(rows, "매니페스트 서명", summary.manifestBlockers());
		return rows;
	}

	private static void addBlockerRow(List<DatapackBlockerRow> rows, String label, long count) {
		if (count > 0) {
			rows.add(new DatapackBlockerRow(label, count));
		}
	}

	// 확인 필요 통합 카드(#2349)의 행 하나. count는 항상 0보다 크다(0건 항목은 컨트롤러에서 걸러진다).
	record TriageItem(String label, String href, long count) {
	}

	record DatapackBlockerRow(String label, long count) {
	}

	record WeeklyReportDay(String weekday, String dateLabel, String value, boolean today, boolean weekend) {
	}

	record WeeklyReportView(List<WeeklyReportDay> days, String summaryLabel) {
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
		long totalFacilities,
		boolean apiDataAvailable,
		String apiNormalRateLabel,
		double apiNormalRate,
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
