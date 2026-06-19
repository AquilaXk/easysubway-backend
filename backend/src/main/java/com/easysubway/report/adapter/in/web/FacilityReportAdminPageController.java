package com.easysubway.report.adapter.in.web;

import com.easysubway.common.domain.PageResult;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
	private final LoadFacilityReportPhotoPort loadFacilityReportPhotoPort;
	private final Clock clock;

	@Autowired
	FacilityReportAdminPageController(
		FacilityReportUseCase facilityReportUseCase,
		LoadFacilityReportPhotoPort loadFacilityReportPhotoPort,
		ObjectProvider<Clock> clockProvider
	) {
		this(facilityReportUseCase, loadFacilityReportPhotoPort, clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	FacilityReportAdminPageController(FacilityReportUseCase facilityReportUseCase, Clock clock) {
		this(facilityReportUseCase, objectKey -> java.util.Optional.empty(), clock);
	}

	FacilityReportAdminPageController(
		FacilityReportUseCase facilityReportUseCase,
		LoadFacilityReportPhotoPort loadFacilityReportPhotoPort,
		Clock clock
	) {
		this.facilityReportUseCase = facilityReportUseCase;
		this.loadFacilityReportPhotoPort = loadFacilityReportPhotoPort;
		this.clock = clock;
	}

	@GetMapping("/admin/reports/page")
	String reportListPage(
		@RequestParam(required = false) FacilityReportStatus status,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size,
		Model model
	) {
		FacilityReportPageRequest pageRequest = FacilityReportPageRequest.of(page, size);
		PageResult<FacilityReportSummary> reportPage = facilityReportUseCase.listReportSummaries(status, pageRequest);
		List<FacilityReportListPageRow> reports = reportPage.items()
			.stream()
			.map(FacilityReportListPageRow::from)
			.toList();
		LocalDateTime surgeCutoff = LocalDateTime.now(clock).minusHours(REPORT_SURGE_LOOKBACK_HOURS);

		model.addAttribute("reports", reports);
		model.addAttribute("page", PageView.from(reportPage));
		model.addAttribute("selectedStatus", status);
		model.addAttribute("statusOptions", statusOptions());
		model.addAttribute("reportSurgeAlert", ReportSurgeAlertView.from(
			facilityReportUseCase.countReportsCreatedSince(surgeCutoff)
		));
		model.addAttribute("processingTime", ReportProcessingTimeView.from(
			facilityReportUseCase.summarizeReportProcessingTime()
		));
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

	@GetMapping("/admin/reports/photos")
	ResponseEntity<byte[]> reportPhoto(@RequestParam String objectKey) {
		return loadFacilityReportPhotoPort.loadFacilityReportPhoto(objectKey)
			.map(photo -> ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(photo.contentType()))
				.body(photo.bytes()))
			.orElseGet(() -> ResponseEntity.notFound().build());
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
		return report.hasPhoto();
	}

	private static boolean hasCompletePhoto(FacilityReportSummary report) {
		return report.hasPhoto();
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

		static FacilityReportListPageRow from(FacilityReportSummary report) {
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

	record PageView(
		int page,
		int size,
		boolean hasPrevious,
		boolean hasNext,
		int previousPage,
		int nextPage
	) {

		static PageView from(PageResult<?> page) {
			return new PageView(
				page.page(),
				page.size(),
				page.page() > 0,
				page.hasNext(),
				Math.max(page.page() - 1, 0),
				page.page() + 1
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
		String photoObjectKey,
		String photoThumbnailObjectKey,
		String photoSha256,
		Long photoSizeBytes,
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
				report.photoObjectKey(),
				report.photoThumbnailObjectKey(),
				report.photoSha256(),
				report.photoSizeBytes(),
				report.duplicateOfReportId(),
				FacilityReportAdminPageController.coordinateLabel(report.latitude(), report.longitude())
			);
		}

		public boolean hasPhoto() {
			return FacilityReportAdminPageController.hasText(photoFileName)
				&& FacilityReportAdminPageController.hasText(photoContentType)
				&& FacilityReportAdminPageController.hasText(photoObjectKey);
		}

		public String photoPreviewPath() {
			return hasPhoto() ? "/admin/reports/photos/" + photoObjectKey : null;
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

		static ReportSurgeAlertView from(long recentReportCount) {
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

	record ReportProcessingTimeView(
		String title,
		String label,
		String description,
		String metricClass
	) {

		static ReportProcessingTimeView from(ReportProcessingTimeSummary summary) {
			if (summary.reviewedReportCount() == 0) {
				return new ReportProcessingTimeView(
					"신고 처리 시간",
					"처리 완료 신고 없음",
					"검수 완료 후 평균 처리 시간을 표시합니다.",
					"empty"
				);
			}

			return new ReportProcessingTimeView(
				"신고 처리 시간",
				"평균 " + durationLabel(summary.averageProcessingMinutes()),
				"처리 완료 신고 %d건 기준입니다.".formatted(summary.reviewedReportCount()),
				"ok"
			);
		}

		private static String durationLabel(long minutes) {
			if (minutes < 60) {
				return minutes + "분";
			}
			long hours = minutes / 60;
			long remainingMinutes = minutes % 60;
			if (remainingMinutes == 0) {
				return hours + "시간";
			}
			return "%d시간 %d분".formatted(hours, remainingMinutes);
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
