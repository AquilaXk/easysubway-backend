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
	ApiResponse<FacilityReportStatusResponse> createReport(
		@RequestBody CreateFacilityReportRequest request,
		Principal principal
	) {
		FacilityReport report = facilityReportUseCase.createReport(request.toCommand(principal.getName()));
		return ApiResponse.ok(FacilityReportStatusResponse.from(report));
	}

	@GetMapping("/api/v1/reports/{reportId}")
	ApiResponse<FacilityReportStatusResponse> report(@PathVariable String reportId) {
		return ApiResponse.ok(FacilityReportStatusResponse.from(facilityReportUseCase.getReport(reportId)));
	}

	@GetMapping("/api/v1/me/reports")
	ApiResponse<List<FacilityReportListResponse>> myReports(Principal principal) {
		List<FacilityReportListResponse> reports = facilityReportUseCase.listUserReports(principal.getName())
			.stream()
			.map(FacilityReportListResponse::from)
			.toList();
		return ApiResponse.ok(reports);
	}

	@GetMapping("/admin/reports")
	ApiResponse<List<FacilityReportListResponse>> adminReports(@RequestParam(required = false) FacilityReportStatus status) {
		List<FacilityReportListResponse> reports = facilityReportUseCase.listReports(status)
			.stream()
			.map(FacilityReportListResponse::from)
			.toList();
		return ApiResponse.ok(reports);
	}

	@GetMapping("/admin/reports/{reportId}")
	ApiResponse<FacilityReportResponse> adminReport(@PathVariable String reportId) {
		return ApiResponse.ok(FacilityReportResponse.from(facilityReportUseCase.getReport(reportId)));
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
		String photoFileName,
		String photoContentType,
		String photoDataBase64,
		BigDecimal latitude,
		BigDecimal longitude
	) {

		CreateFacilityReportCommand toCommand(String authenticatedUserId) {
			return new CreateFacilityReportCommand(
				authenticatedUserId,
				stationId,
				facilityId,
				reportType,
				description,
				photoFileName,
				photoContentType,
				photoDataBase64,
				latitude,
				longitude
			);
		}
	}

	record ReviewFacilityReportRequest(
		FacilityReportReviewDecision decision,
		String duplicateOfReportId
	) {

		ReviewFacilityReportCommand toCommand(String reportId, String reviewedBy) {
			return new ReviewFacilityReportCommand(reportId, decision, reviewedBy, duplicateOfReportId);
		}
	}

	record FacilityReportListResponse(
		String id,
		String userId,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String photoFileName,
		String photoContentType,
		BigDecimal latitude,
		BigDecimal longitude,
		String duplicateOfReportId,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String reviewedBy
	) {

		static FacilityReportListResponse from(FacilityReport report) {
			return new FacilityReportListResponse(
				report.id(),
				report.userId(),
				report.stationId(),
				report.facilityId(),
				report.reportType(),
				report.description(),
				report.photoFileName(),
				report.photoContentType(),
				report.latitude(),
				report.longitude(),
				report.duplicateOfReportId(),
				report.status(),
				report.createdAt(),
				report.reviewedAt(),
				report.reviewedBy()
			);
		}
	}

	record FacilityReportStatusResponse(
		String id,
		String userId,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String photoFileName,
		String photoContentType,
		BigDecimal latitude,
		BigDecimal longitude,
		String duplicateOfReportId,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String reviewedBy
	) {

		static FacilityReportStatusResponse from(FacilityReport report) {
			// 공개 상태 조회는 모바일 진행 상태 확인용이므로 첨부 사진 본문은 관리자 상세에서만 내려준다.
			return new FacilityReportStatusResponse(
				report.id(),
				report.userId(),
				report.stationId(),
				report.facilityId(),
				report.reportType(),
				report.description(),
				report.photoFileName(),
				report.photoContentType(),
				report.latitude(),
				report.longitude(),
				report.duplicateOfReportId(),
				report.status(),
				report.createdAt(),
				report.reviewedAt(),
				report.reviewedBy()
			);
		}
	}

	record FacilityReportResponse(
		String id,
		String userId,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String photoFileName,
		String photoContentType,
		String photoDataBase64,
		BigDecimal latitude,
		BigDecimal longitude,
		String duplicateOfReportId,
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
				report.photoFileName(),
				report.photoContentType(),
				report.photoDataBase64(),
				report.latitude(),
				report.longitude(),
				report.duplicateOfReportId(),
				report.status(),
				report.createdAt(),
				report.reviewedAt(),
				report.reviewedBy()
			);
		}
	}
}
