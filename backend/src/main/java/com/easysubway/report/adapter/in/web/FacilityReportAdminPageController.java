package com.easysubway.report.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.web.AdminFormErrorView;
import com.easysubway.common.domain.PageResult;
import com.easysubway.common.web.WebMessageResolver;
import com.easysubway.common.web.pagination.EgovPaginationView;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.application.port.out.LoadFacilityReportPhotoPort;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import com.easysubway.transit.domain.MasterDataWriteNotAllowedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
class FacilityReportAdminPageController {

	private static final int REPORT_SURGE_ALERT_THRESHOLD = 10;
	private static final long REPORT_SURGE_LOOKBACK_HOURS = 24;

	private final FacilityReportUseCase facilityReportUseCase;
	private final LoadFacilityReportPhotoPort loadFacilityReportPhotoPort;
	private final WebMessageResolver messages;
	private final AdminAuditWriter auditWriter;
	private final Clock clock;

	@Autowired
	FacilityReportAdminPageController(
		FacilityReportUseCase facilityReportUseCase,
		LoadFacilityReportPhotoPort loadFacilityReportPhotoPort,
		WebMessageResolver messages,
		AdminAuditWriter auditWriter,
		ObjectProvider<Clock> clockProvider
	) {
		this(
			facilityReportUseCase,
			loadFacilityReportPhotoPort,
			messages,
			auditWriter,
			clockProvider.getIfAvailable(Clock::systemDefaultZone)
		);
	}

	FacilityReportAdminPageController(FacilityReportUseCase facilityReportUseCase, Clock clock) {
		this(facilityReportUseCase, objectKey -> java.util.Optional.empty(), WebMessageResolver.defaultMessages(), clock);
	}

	FacilityReportAdminPageController(
		FacilityReportUseCase facilityReportUseCase,
		LoadFacilityReportPhotoPort loadFacilityReportPhotoPort,
		WebMessageResolver messages,
		Clock clock
	) {
		this(facilityReportUseCase, loadFacilityReportPhotoPort, messages, AdminAuditWriter.noop(), clock);
	}

	FacilityReportAdminPageController(
		FacilityReportUseCase facilityReportUseCase,
		LoadFacilityReportPhotoPort loadFacilityReportPhotoPort,
		WebMessageResolver messages,
		AdminAuditWriter auditWriter,
		Clock clock
	) {
		this.facilityReportUseCase = facilityReportUseCase;
		this.loadFacilityReportPhotoPort = loadFacilityReportPhotoPort;
		this.messages = messages;
		this.auditWriter = auditWriter;
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
		Map<FacilityReportStatus, Long> statusCounts = facilityReportUseCase.countReportsByStatus();
		EgovPaginationView pageView = EgovPaginationView.from(
			pageRequest.page(),
			pageRequest.size(),
			totalCountForStatus(status, statusCounts)
		);
		if (pageView.page() != pageRequest.page() || pageView.size() != pageRequest.size()) {
			return redirectToReportList(status, pageView);
		}

		PageResult<FacilityReportSummary> reportPage = facilityReportUseCase.listReportSummaries(status, pageRequest);
		List<FacilityReportListPageRow> reports = reportPage.items()
			.stream()
			.map(report -> FacilityReportListPageRow.from(report, messages))
			.toList();
		LocalDateTime surgeCutoff = LocalDateTime.now(clock).minusHours(REPORT_SURGE_LOOKBACK_HOURS);

		model.addAttribute("reports", reports);
		model.addAttribute("page", pageView);
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

	private static long totalCountForStatus(FacilityReportStatus status, Map<FacilityReportStatus, Long> statusCounts) {
		if (status != null) {
			return statusCounts.getOrDefault(status, 0L);
		}
		return statusCounts.values().stream().mapToLong(Long::longValue).sum();
	}

	private static String redirectToReportList(FacilityReportStatus status, EgovPaginationView pageView) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/admin/reports/page");
		if (status != null) {
			builder.queryParam("status", status);
		}
		builder.queryParam("page", pageView.page());
		builder.queryParam("size", pageView.size());
		return "redirect:" + builder.build().toUriString();
	}

	@GetMapping("/admin/reports/{reportId}/page")
	String reportDetailPage(
		@PathVariable String reportId,
		Model model,
		Authentication authentication,
		HttpServletRequest request
	) {
		populateReportDetailModel(reportId, model, null);
		auditReportDetailRead(authentication, request, reportId);
		return "admin/reports/detail";
	}

	private void populateReportDetailModel(String reportId, Model model, ReviewReportForm submittedForm) {
		model.addAttribute("report", FacilityReportDetailPageView.from(facilityReportUseCase.getReport(reportId), messages));
		model.addAttribute(
			"reviewAudits",
			facilityReportUseCase.listReviewAudits(reportId)
				.stream()
				.map(audit -> FacilityReportReviewAuditPageRow.from(audit, messages))
				.toList()
		);
		model.addAttribute("reviewActions", reviewActions());
		model.addAttribute("reviewForm", submittedForm == null ? new ReviewReportForm(null, "") : submittedForm);
	}

	@GetMapping("/admin/reports/photos")
	ResponseEntity<byte[]> reportPhoto(
		@RequestParam String objectKey,
		Authentication authentication,
		HttpServletRequest request
	) {
		return loadFacilityReportPhotoPort.loadFacilityReportPhoto(objectKey)
			.map(photo -> {
				auditWriter.privacyRead(
					authentication,
					request,
					"FACILITY_REPORT_PHOTO",
					auditWriter.sha256TargetId(objectKey),
					"VIEW_REPORT_PHOTO",
					"업무 맥락: 신고 사진 조회"
				);
				return ResponseEntity.ok()
					.contentType(MediaType.parseMediaType(photo.contentType()))
					.body(photo.bytes());
			})
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/admin/reports/{reportId}/page/review")
	@PreAuthorize("hasAuthority('admin.report.review')")
	String reviewReportFromPage(
		@PathVariable String reportId,
		@Valid @ModelAttribute("reviewForm") ReviewReportForm form,
		BindingResult bindingResult,
		Principal principal,
		RedirectAttributes redirectAttributes,
		Model model,
		HttpServletResponse response,
		Authentication authentication,
		HttpServletRequest request
	) {
		if (bindingResult.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			populateReportDetailModel(reportId, model, form);
			auditReportDetailRead(authentication, request, reportId);
			AdminFormErrorView.expose(model, bindingResult);
			return "admin/reports/detail";
		}
		try {
			facilityReportUseCase.reviewReport(
				new ReviewFacilityReportCommand(reportId, form.decision(), principal.getName(), form.duplicateOfReportId())
			);
		} catch (MasterDataWriteNotAllowedException exception) {
			redirectAttributes.addFlashAttribute("masterDataError", exception.getMessage());
		}
		return "redirect:/admin/reports/%s/page".formatted(reportId);
	}

	private void auditReportDetailRead(Authentication authentication, HttpServletRequest request, String reportId) {
		auditWriter.privacyRead(
			authentication,
			request,
			"FACILITY_REPORT",
			reportId,
			"VIEW_REPORT_DETAIL",
			"업무 맥락: 신고 상세 조회"
		);
	}

	private List<ReviewAction> reviewActions() {
		return List.of(
			new ReviewAction(
				FacilityReportReviewDecision.ACCEPT,
				messages.enumLabel("admin.report.review-decision", FacilityReportReviewDecision.ACCEPT)
			),
			new ReviewAction(
				FacilityReportReviewDecision.REJECT,
				messages.enumLabel("admin.report.review-decision", FacilityReportReviewDecision.REJECT)
			),
			new ReviewAction(
				FacilityReportReviewDecision.MARK_DUPLICATE,
				messages.enumLabel("admin.report.review-decision", FacilityReportReviewDecision.MARK_DUPLICATE)
			)
		);
	}

	private List<StatusOption> statusOptions() {
		return Arrays.stream(FacilityReportStatus.values())
			.map(status -> new StatusOption(status, messages.enumLabel("admin.report.status", status)))
			.toList();
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

		static FacilityReportListPageRow from(FacilityReportSummary report, WebMessageResolver messages) {
			return new FacilityReportListPageRow(
				report.id(),
				report.stationId(),
				report.facilityId(),
				messages.enumLabel("admin.report.type", report.reportType()),
				report.description(),
				messages.enumLabel("admin.report.status", report.status()),
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
		String photoObjectKey,
		String photoThumbnailObjectKey,
		String photoSha256,
		Long photoSizeBytes,
		String duplicateOfReportId,
		String coordinateLabel
	) {

		static FacilityReportDetailPageView from(FacilityReport report, WebMessageResolver messages) {
			return new FacilityReportDetailPageView(
				report.id(),
				report.userId(),
				report.stationId(),
				report.facilityId(),
				messages.enumLabel("admin.report.type", report.reportType()),
				report.description(),
				messages.enumLabel("admin.report.status", report.status()),
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

	record ReviewReportForm(
		@NotNull(message = "{validation.report.review-decision.required}")
		FacilityReportReviewDecision decision,
		String duplicateOfReportId
	) {
	}

	record FacilityReportReviewAuditPageRow(
		String reviewerId,
		String decisionLabel,
		String previousStatusLabel,
		String nextStatusLabel,
		LocalDateTime createdAt
	) {

		static FacilityReportReviewAuditPageRow from(FacilityReportReviewAudit audit, WebMessageResolver messages) {
			return new FacilityReportReviewAuditPageRow(
				audit.reviewerId(),
				messages.enumLabel("admin.report.review-decision", audit.decision()),
				messages.enumLabel("admin.report.status", audit.previousStatus()),
				messages.enumLabel("admin.report.status", audit.nextStatus()),
				audit.createdAt()
			);
		}
	}
}
