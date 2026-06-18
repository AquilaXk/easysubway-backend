package com.easysubway.report.adapter.in.web;

import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class FacilityReportAdminPageController {

	private static final int REPORT_SURGE_ALERT_THRESHOLD = 10;
	private static final long REPORT_SURGE_LOOKBACK_HOURS = 24;

	private final FacilityReportUseCase facilityReportUseCase;
	private final Clock clock;

	@Autowired
	FacilityReportAdminPageController(FacilityReportUseCase facilityReportUseCase, ObjectProvider<Clock> clockProvider) {
		this(facilityReportUseCase, clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	FacilityReportAdminPageController(FacilityReportUseCase facilityReportUseCase, Clock clock) {
		this.facilityReportUseCase = facilityReportUseCase;
		this.clock = clock;
	}

	@GetMapping("/admin/reports/page")
	String reportListPage(
		@RequestParam(required = false) FacilityReportStatus status,
		Model model
	) {
		List<FacilityReport> allReports = facilityReportUseCase.listReports(null);
		List<FacilityReport> filteredReports = allReports.stream()
			.filter(report -> status == null || report.status() == status)
			.toList();
		List<FacilityReportListPageRow> reports = filteredReports
			.stream()
			.map(FacilityReportListPageRow::from)
			.toList();

		model.addAttribute("reports", reports);
		model.addAttribute("selectedStatus", status);
		model.addAttribute("statusOptions", statusOptions());
		model.addAttribute("reportSurgeAlert", ReportSurgeAlertView.from(allReports, clock));
		return "admin/reports/list";
	}

	@GetMapping("/admin/reports/{reportId}/page")
	String reportDetailPage(@PathVariable String reportId, Model model) {
		model.addAttribute("report", FacilityReportDetailPageView.from(facilityReportUseCase.getReport(reportId)));
		model.addAttribute(
			"reviewAudits",
			facilityReportUseCase.listReviewAudits(reportId)
				.stream()
				.map(FacilityReportReviewAuditPageRow::from)
				.toList()
		);
		model.addAttribute("reviewActions", reviewActions());
		return "admin/reports/detail";
	}

	@PostMapping("/admin/reports/{reportId}/page/review")
	String reviewReportFromPage(
		@PathVariable String reportId,
		@RequestParam FacilityReportReviewDecision decision,
		@RequestParam(required = false) String duplicateOfReportId,
		Principal principal
	) {
		facilityReportUseCase.reviewReport(
			new ReviewFacilityReportCommand(reportId, decision, principal.getName(), duplicateOfReportId)
		);
		return "redirect:/admin/reports/%s/page".formatted(reportId);
	}

	private static List<StatusOption> statusOptions() {
		return Arrays.stream(FacilityReportStatus.values())
			.map(status -> new StatusOption(status, statusLabel(status)))
			.toList();
	}

	private static List<ReviewAction> reviewActions() {
		return List.of(
			new ReviewAction(FacilityReportReviewDecision.ACCEPT, "승인"),
			new ReviewAction(FacilityReportReviewDecision.REJECT, "반려"),
			new ReviewAction(FacilityReportReviewDecision.MARK_DUPLICATE, "중복 처리")
		);
	}

	private static String reportTypeLabel(FacilityReportType reportType) {
		return switch (reportType) {
			case BROKEN -> "고장";
			case UNDER_CONSTRUCTION -> "공사 중";
			case CLOSED -> "폐쇄";
			case LOCATION_WRONG -> "위치가 달라요";
			case INFORMATION_WRONG -> "정보가 달라요";
			case RECOVERED -> "다시 정상";
		};
	}

	private static String statusLabel(FacilityReportStatus status) {
		return switch (status) {
			case SUBMITTED -> "접수됨";
			case UNDER_REVIEW -> "검수 중";
			case ACCEPTED -> "반영됨";
			case REJECTED -> "반려됨";
			case DUPLICATE -> "중복";
			case RESOLVED -> "완료";
		};
	}

	private static String reviewDecisionLabel(FacilityReportReviewDecision decision) {
		return switch (decision) {
			case ACCEPT -> "승인";
			case REJECT -> "반려";
			case MARK_DUPLICATE -> "중복 처리";
		};
	}

	private static String coordinateLabel(BigDecimal latitude, BigDecimal longitude) {
		if (latitude == null || longitude == null) {
			return "위치 없음";
		}
		return "%s, %s".formatted(latitude.toPlainString(), longitude.toPlainString());
	}

	private static boolean hasCompletePhoto(FacilityReport report) {
		return hasText(report.photoFileName())
			&& hasText(report.photoContentType())
			&& hasText(report.photoDataBase64());
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	record FacilityReportListPageRow(
		String id,
		String stationId,
		String facilityId,
		String reportTypeLabel,
		String description,
		String statusLabel,
		LocalDateTime createdAt,
		boolean hasPhoto,
		String coordinateLabel
	) {

		static FacilityReportListPageRow from(FacilityReport report) {
			return new FacilityReportListPageRow(
				report.id(),
				report.stationId(),
				report.facilityId(),
				FacilityReportAdminPageController.reportTypeLabel(report.reportType()),
				report.description(),
				FacilityReportAdminPageController.statusLabel(report.status()),
				report.createdAt(),
				FacilityReportAdminPageController.hasCompletePhoto(report),
				FacilityReportAdminPageController.coordinateLabel(report.latitude(), report.longitude())
			);
		}
	}

	record FacilityReportDetailPageView(
		String id,
		String userId,
		String stationId,
		String facilityId,
		String reportTypeLabel,
		String description,
		String statusLabel,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String reviewedBy,
		String photoFileName,
		String photoContentType,
		String photoDataBase64,
		String duplicateOfReportId,
		String coordinateLabel
	) {

		static FacilityReportDetailPageView from(FacilityReport report) {
			return new FacilityReportDetailPageView(
				report.id(),
				report.userId(),
				report.stationId(),
				report.facilityId(),
				FacilityReportAdminPageController.reportTypeLabel(report.reportType()),
				report.description(),
				FacilityReportAdminPageController.statusLabel(report.status()),
				report.createdAt(),
				report.reviewedAt(),
				report.reviewedBy(),
				report.photoFileName(),
				report.photoContentType(),
				report.photoDataBase64(),
				report.duplicateOfReportId(),
				FacilityReportAdminPageController.coordinateLabel(report.latitude(), report.longitude())
			);
		}

		public boolean hasPhoto() {
			return FacilityReportAdminPageController.hasText(photoFileName)
				&& FacilityReportAdminPageController.hasText(photoContentType)
				&& FacilityReportAdminPageController.hasText(photoDataBase64);
		}
	}

	record StatusOption(FacilityReportStatus value, String label) {
	}

	record ReportSurgeAlertView(
		String title,
		String label,
		String description,
		String alertClass
	) {

		static ReportSurgeAlertView from(List<FacilityReport> reports, Clock clock) {
			LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(REPORT_SURGE_LOOKBACK_HOURS);
			long recentReportCount = reports.stream()
				.filter(report -> !report.createdAt().isBefore(cutoff))
				.count();

			if (recentReportCount >= REPORT_SURGE_ALERT_THRESHOLD) {
				return new ReportSurgeAlertView(
					"신고 급증",
					"점검 필요",
					"최근 24시간 신고 %d건입니다. 신고가 평소보다 많습니다.".formatted(recentReportCount),
					"warning"
				);
			}
			return new ReportSurgeAlertView(
				"신고 급증",
				"정상",
				"최근 24시간 신고 %d건입니다. 접수량은 정상 범위입니다.".formatted(recentReportCount),
				"normal"
			);
		}
	}

	record ReviewAction(FacilityReportReviewDecision value, String label) {
	}

	record FacilityReportReviewAuditPageRow(
		String reviewerId,
		String decisionLabel,
		String previousStatusLabel,
		String nextStatusLabel,
		LocalDateTime createdAt
	) {

		static FacilityReportReviewAuditPageRow from(FacilityReportReviewAudit audit) {
			return new FacilityReportReviewAuditPageRow(
				audit.reviewerId(),
				FacilityReportAdminPageController.reviewDecisionLabel(audit.decision()),
				FacilityReportAdminPageController.statusLabel(audit.previousStatus()),
				FacilityReportAdminPageController.statusLabel(audit.nextStatus()),
				audit.createdAt()
			);
		}
	}
}
