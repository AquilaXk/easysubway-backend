package com.easysubway.admin.adapter.in.web;

import com.easysubway.admin.authorization.AdminAuthorization;
import com.easysubway.admin.authorization.AdminPermission;
import com.easysubway.collection.application.port.in.DataCollectionUseCase;
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
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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

	AdminOverviewPageController(
		DataQualityUseCase dataQualityUseCase,
		FacilityReportUseCase facilityReportUseCase,
		RouteSearchDashboardUseCase routeSearchDashboardUseCase,
		PushNotificationDashboardUseCase pushNotificationDashboardUseCase,
		UserActivityDashboardUseCase userActivityDashboardUseCase,
		DataCollectionUseCase dataCollectionUseCase,
		CheckHealthUseCase checkHealthUseCase,
		DatapackReleaseBlockerSummaryUseCase datapackReleaseBlockerSummaryUseCase
	) {
		this.dataQualityUseCase = dataQualityUseCase;
		this.facilityReportUseCase = facilityReportUseCase;
		this.routeSearchDashboardUseCase = routeSearchDashboardUseCase;
		this.pushNotificationDashboardUseCase = pushNotificationDashboardUseCase;
		this.userActivityDashboardUseCase = userActivityDashboardUseCase;
		this.dataCollectionUseCase = dataCollectionUseCase;
		this.checkHealthUseCase = checkHealthUseCase;
		this.datapackReleaseBlockerSummaryUseCase = datapackReleaseBlockerSummaryUseCase;
	}

	@GetMapping("/admin/dashboard/page")
	String dashboardPage(Model model, Authentication authentication) {
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
		return "admin/dashboard";
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
