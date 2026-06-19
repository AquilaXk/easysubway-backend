package com.easysubway.report.adapter.in.web;

import com.easysubway.common.domain.PageResult;
import com.easysubway.common.web.ApiResponse;
import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportSummary;
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
	ApiResponse<FacilityReportStatusResponse> report(
		@PathVariable String reportId,
		Principal principal
	) {
		return ApiResponse.ok(FacilityReportStatusResponse.from(
			facilityReportUseCase.getUserReport(reportId, principal.getName())
		));
	}

	@GetMapping("/api/v1/me/reports")
	ApiResponse<PageResponse<FacilityReportStatusResponse>> myReports(
		Principal principal,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size
	) {
		PageResult<FacilityReportSummary> reports = facilityReportUseCase.listUserReportSummaries(
			principal.getName(),
			FacilityReportPageRequest.of(page, size)
		);
		return ApiResponse.ok(PageResponse.from(reports, FacilityReportStatusResponse::from));
	}

	@PostMapping("/api/v1/reports/{reportId}/confirm")
	ApiResponse<FacilityReportStatusResponse> confirmReportResult(
		@PathVariable String reportId,
		Principal principal
	) {
		return ApiResponse.ok(FacilityReportStatusResponse.from(
			facilityReportUseCase.confirmReportResult(reportId, principal.getName())
		));
	}

	@GetMapping("/admin/reports")
	ApiResponse<PageResponse<FacilityReportListResponse>> adminReports(
		@RequestParam(required = false) FacilityReportStatus status,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size
	) {
		PageResult<FacilityReportSummary> reports = facilityReportUseCase.listReportSummaries(
			status,
			FacilityReportPageRequest.of(page, size)
		);
		return ApiResponse.ok(PageResponse.from(reports, FacilityReportListResponse::from));
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
		boolean hasPhoto,
		BigDecimal latitude,
		BigDecimal longitude,
		String duplicateOfReportId,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String reviewedBy
	) {

		static FacilityReportListResponse from(FacilityReportSummary report) {
			return new FacilityReportListResponse(
				report.id(),
				report.userId(),
				report.stationId(),
				report.facilityId(),
				report.reportType(),
				report.description(),
				report.hasPhoto(),
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
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String duplicateOfReportId,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt
	) {

		static FacilityReportStatusResponse from(FacilityReportSummary report) {
			return new FacilityReportStatusResponse(
				report.id(),
				report.stationId(),
				report.facilityId(),
				report.reportType(),
				report.description(),
				report.duplicateOfReportId(),
				report.status(),
				report.createdAt(),
				report.reviewedAt()
			);
		}

		static FacilityReportStatusResponse from(FacilityReport report) {
			// 사용자용 상태 조회는 소유자에게도 내부 식별자와 정확한 위치 메타데이터를 숨긴다.
			return new FacilityReportStatusResponse(
				report.id(),
				report.stationId(),
				report.facilityId(),
				report.reportType(),
				report.description(),
				report.duplicateOfReportId(),
				report.status(),
				report.createdAt(),
				report.reviewedAt()
			);
		}
	}

	record PageResponse<T>(
		List<T> items,
		int page,
		int size,
		boolean hasNext
	) {

		static <T, R> PageResponse<R> from(PageResult<T> page, java.util.function.Function<T, R> mapper) {
			return new PageResponse<>(
				page.items()
					.stream()
					.map(mapper)
					.toList(),
				page.page(),
				page.size(),
				page.hasNext()
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
		String photoObjectKey,
		String photoThumbnailObjectKey,
		String photoSha256,
		Long photoSizeBytes,
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
				report.photoObjectKey(),
				report.photoThumbnailObjectKey(),
				report.photoSha256(),
				report.photoSizeBytes(),
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
