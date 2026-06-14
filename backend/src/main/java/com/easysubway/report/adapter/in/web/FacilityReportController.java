package com.easysubway.report.adapter.in.web;

import com.easysubway.common.web.ApiResponse;
import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FacilityReportController {

	private final FacilityReportUseCase facilityReportUseCase;

	FacilityReportController(FacilityReportUseCase facilityReportUseCase) {
		this.facilityReportUseCase = facilityReportUseCase;
	}

	@PostMapping("/api/v1/reports")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<FacilityReportResponse> createReport(@RequestBody CreateFacilityReportRequest request) {
		FacilityReport report = facilityReportUseCase.createReport(request.toCommand());
		return ApiResponse.ok(FacilityReportResponse.from(report));
	}

	@GetMapping("/api/v1/reports/{reportId}")
	ApiResponse<FacilityReportResponse> report(@PathVariable String reportId) {
		return ApiResponse.ok(FacilityReportResponse.from(facilityReportUseCase.getReport(reportId)));
	}

	@GetMapping("/admin/reports")
	ApiResponse<List<FacilityReportResponse>> adminReports(@RequestParam(required = false) FacilityReportStatus status) {
		List<FacilityReportResponse> reports = facilityReportUseCase.listReports(status)
			.stream()
			.map(FacilityReportResponse::from)
			.toList();
		return ApiResponse.ok(reports);
	}

	@PostMapping("/admin/reports/{reportId}/review")
	ApiResponse<FacilityReportResponse> reviewReport(
		@PathVariable String reportId,
		@RequestBody ReviewFacilityReportRequest request,
		Principal principal
	) {
		FacilityReport report = facilityReportUseCase.reviewReport(request.toCommand(reportId, principal.getName()));
		return ApiResponse.ok(FacilityReportResponse.from(report));
	}

	record CreateFacilityReportRequest(
		String userId,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String photoUrl,
		BigDecimal latitude,
		BigDecimal longitude
	) {

		CreateFacilityReportCommand toCommand() {
			return new CreateFacilityReportCommand(
				userId,
				stationId,
				facilityId,
				reportType,
				description,
				photoUrl,
				latitude,
				longitude
			);
		}
	}

	record ReviewFacilityReportRequest(
		FacilityReportReviewDecision decision
	) {

		ReviewFacilityReportCommand toCommand(String reportId, String reviewedBy) {
			return new ReviewFacilityReportCommand(reportId, decision, reviewedBy);
		}
	}

	record FacilityReportResponse(
		String id,
		String userId,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String photoUrl,
		BigDecimal latitude,
		BigDecimal longitude,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String reviewedBy
	) {

		static FacilityReportResponse from(FacilityReport report) {
			return new FacilityReportResponse(
				report.id(),
				report.userId(),
				report.stationId(),
				report.facilityId(),
				report.reportType(),
				report.description(),
				report.photoUrl(),
				report.latitude(),
				report.longitude(),
				report.status(),
				report.createdAt(),
				report.reviewedAt(),
				report.reviewedBy()
			);
		}
	}
}
