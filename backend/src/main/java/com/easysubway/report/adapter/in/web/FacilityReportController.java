package com.easysubway.report.adapter.in.web;

import com.easysubway.common.domain.PageResult;
import com.easysubway.common.web.ApiResponse;
import com.easysubway.report.application.port.in.CreateFacilityReportCommand;
import com.easysubway.report.application.port.in.CreatedFacilityReport;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.application.port.in.FacilityReportUseCase;
import com.easysubway.report.application.port.in.ReviewFacilityReportCommand;
import com.easysubway.report.application.port.out.DeleteFacilityReportPhotoPort;
import com.easysubway.report.application.port.out.StoreFacilityReportUploadedPhotoPort;
import com.easysubway.report.application.port.out.StoreFacilityReportUploadedPhotoPort.StoreUploadedReportPhotoCommand;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.InvalidFacilityReportException;
import com.easysubway.report.domain.FacilityReportReviewDecision;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class FacilityReportController {

	private final FacilityReportUseCase facilityReportUseCase;
	private final StoreFacilityReportUploadedPhotoPort storeFacilityReportUploadedPhotoPort;
	private final DeleteFacilityReportPhotoPort deleteFacilityReportPhotoPort;
	private final FacilityReportUploadIntents uploadIntents;
	private final FacilityReportUploadUrlSigner uploadUrlSigner;
	private final Environment environment;

	FacilityReportController(
		FacilityReportUseCase facilityReportUseCase,
		StoreFacilityReportUploadedPhotoPort storeFacilityReportUploadedPhotoPort,
		DeleteFacilityReportPhotoPort deleteFacilityReportPhotoPort,
		FacilityReportUploadIntents uploadIntents,
		FacilityReportUploadUrlSigner uploadUrlSigner,
		Environment environment
	) {
		this.facilityReportUseCase = facilityReportUseCase;
		this.storeFacilityReportUploadedPhotoPort = storeFacilityReportUploadedPhotoPort;
		this.deleteFacilityReportPhotoPort = deleteFacilityReportPhotoPort;
		this.uploadIntents = uploadIntents;
		this.uploadUrlSigner = uploadUrlSigner;
		this.environment = environment;
	}

	@PostMapping("/api/v1/report-uploads")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<FacilityReportUploadIntentResponse> createReportUploadIntent(
		@RequestBody FacilityReportUploadIntentRequest request
	) {
		var intent = uploadIntents.create(
			request.clientSubmissionId(),
			request.normalizedPhotoContentType(),
			request.normalizedPhotoSha256(),
			request.requiredPhotoSizeBytes(),
			deleteFacilityReportPhotoPort::deleteFacilityReportPhoto
		);
		FacilityReportUploadUrlSigner.SignedUploadUrl signedUploadUrl = uploadUrlSigner.sign(intent);
		Map<String, String> uploadHeaders = new LinkedHashMap<>(signedUploadUrl.uploadHeaders());
		uploadHeaders.put("content-type", request.normalizedPhotoContentType());
		uploadHeaders.put("x-easysubway-upload-sha256", request.normalizedPhotoSha256());
		uploadHeaders.put("x-easysubway-upload-size", String.valueOf(request.requiredPhotoSizeBytes()));
		return ApiResponse.ok(new FacilityReportUploadIntentResponse(
			intent.objectKey(),
			signedUploadUrl.uploadUrl(),
			signedUploadUrl.uploadMethod(),
			uploadHeaders,
			intent.expiresAt().toString()
		));
	}

	@PutMapping("/api/v1/report-uploads/{uploadId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void uploadReportPhoto(
		@PathVariable String uploadId,
		@RequestHeader(name = "Content-Type", required = false) String contentType,
		@RequestHeader(name = "x-easysubway-upload-sha256", required = false) String uploadSha256,
		@RequestHeader(name = "x-easysubway-upload-size", required = false) String uploadSizeBytes,
		@RequestBody byte[] body
	) {
		if (body == null || body.length < 1 || body.length > 900 * 1024) {
			throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
		}
		if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
		}
		FacilityReportUploadIntents.UploadIntent intent = uploadIntents.requireUpload(
			uploadId,
			contentType,
			uploadSha256,
			requiredUploadSize(uploadSizeBytes),
			body.length
		);
		storeFacilityReportUploadedPhotoPort.storeUploadedReportPhoto(new StoreUploadedReportPhotoCommand(
			intent.objectKey(),
			body
		));
	}

	@PostMapping("/api/v1/reports")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<FacilityReportCreatedResponse> createReport(
		@RequestBody CreateFacilityReportRequest request,
		Principal principal
	) {
		if (request.hasReceiptSubmission() && principal == null) {
			CreatedFacilityReport created = facilityReportUseCase.createReportWithReceipt(request.toReceiptCommand());
			uploadIntents.consumeObjectKey(request.photoObjectKey());
			return ApiResponse.ok(FacilityReportCreatedResponse.from(created.report(), created.receiptToken()));
		}
		if (principal != null) {
			FacilityReport report = facilityReportUseCase.createReport(request.toCommand(principal.getName()));
			uploadIntents.consumeObjectKey(request.photoObjectKey());
			return ApiResponse.ok(FacilityReportCreatedResponse.from(report, null));
		}
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
	}

	@GetMapping("/api/v1/reports/{reportId}")
	ApiResponse<FacilityReportStatusResponse> report(
		@PathVariable String reportId,
		Principal principal,
		@RequestHeader(name = "X-Easysubway-Report-Receipt-Token", required = false) String receiptToken
	) {
		if (receiptToken != null && !receiptToken.isBlank()) {
			return ApiResponse.ok(FacilityReportStatusResponse.from(
				facilityReportUseCase.getReportByReceiptToken(reportId, receiptToken)
			));
		}
		if (principal == null) {
			throw new com.easysubway.report.domain.FacilityReportNotFoundException();
		}
		return ApiResponse.ok(FacilityReportStatusResponse.from(
			facilityReportUseCase.getUserReport(reportId, principal.getName())
		));
	}

	@PostMapping("/api/v1/reports/{reportId}/confirm")
	ApiResponse<FacilityReportStatusResponse> confirmReportResult(
		@PathVariable String reportId,
		Principal principal,
		@RequestHeader(name = "X-Easysubway-Report-Receipt-Token", required = false) String receiptToken
	) {
		if (receiptToken != null && !receiptToken.isBlank()) {
			return ApiResponse.ok(FacilityReportStatusResponse.from(
				facilityReportUseCase.confirmReportResultByReceiptToken(reportId, receiptToken)
			));
		}
		if (principal == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
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
		String clientSubmissionId,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String photoFileName,
		String photoContentType,
		String photoDataBase64,
		String photoObjectKey,
		String photoSha256,
		Long photoSizeBytes,
		BigDecimal latitude,
		BigDecimal longitude
	) {

		boolean hasReceiptSubmission() {
			return clientSubmissionId != null && !clientSubmissionId.isBlank();
		}

		CreateFacilityReportCommand toCommand(String authenticatedUserId) {
			return new CreateFacilityReportCommand(
				authenticatedUserId,
				clientSubmissionId,
				stationId,
				facilityId,
				reportType,
				description,
				photoFileName,
				photoContentType,
				photoDataBase64,
				photoObjectKey,
				photoSha256,
				photoSizeBytes,
				null,
				latitude,
				longitude
			);
		}

		CreateFacilityReportCommand toReceiptCommand() {
			return new CreateFacilityReportCommand(
				null,
				clientSubmissionId,
				stationId,
				facilityId,
				reportType,
				description,
				photoFileName,
				photoContentType,
				null,
				photoObjectKey,
				photoSha256,
				photoSizeBytes,
				null,
				latitude,
				longitude
			);
		}
	}

	record FacilityReportUploadIntentRequest(
		String clientSubmissionId,
		String photoFileName,
		String photoContentType,
		String photoSha256,
		Long photoSizeBytes
	) {

		Long requiredPhotoSizeBytes() {
			if (photoSizeBytes == null || photoSizeBytes < 1 || photoSizeBytes > 900L * 1024L) {
				throw new InvalidFacilityReportException("사진 파일 크기를 줄여야 합니다.");
			}
			return photoSizeBytes;
		}

		String normalizedPhotoSha256() {
			if (photoSha256 == null || !photoSha256.trim().matches("[0-9a-f]{64}")) {
				throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
			}
			return photoSha256.trim();
		}

		String normalizedPhotoContentType() {
			return switch (photoContentType == null ? "" : photoContentType.trim().toLowerCase(Locale.ROOT)) {
				case "image/png" -> "image/png";
				case "image/webp" -> "image/webp";
				case "image/jpeg" -> "image/jpeg";
				default -> throw new InvalidFacilityReportException("사진 파일 형식을 확인해야 합니다.");
			};
		}
	}

	record FacilityReportUploadIntentResponse(
		String objectKey,
		String uploadUrl,
		String uploadMethod,
		Map<String, String> uploadHeaders,
		String expiresAt
	) {
	}

	private static long requiredUploadSize(String uploadSizeBytes) {
		try {
			if (uploadSizeBytes == null) {
				throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
			}
			return Long.parseLong(uploadSizeBytes.trim());
		} catch (NumberFormatException exception) {
			throw new InvalidFacilityReportException("사진 첨부 정보를 확인해야 합니다.");
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

	record FacilityReportCreatedResponse(
		String id,
		String stationId,
		String facilityId,
		FacilityReportType reportType,
		String description,
		String duplicateOfReportId,
		FacilityReportStatus status,
		LocalDateTime createdAt,
		LocalDateTime reviewedAt,
		String receiptToken
	) {

		static FacilityReportCreatedResponse from(FacilityReport report, String receiptToken) {
			return new FacilityReportCreatedResponse(
				report.id(),
				report.stationId(),
				report.facilityId(),
				report.reportType(),
				report.description(),
				report.duplicateOfReportId(),
				report.status(),
				report.createdAt(),
				report.reviewedAt(),
				receiptToken
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
