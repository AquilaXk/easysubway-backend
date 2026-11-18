package com.easysubway.report.application.port.in;

import com.easysubway.common.domain.PageResult;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportReviewAudit;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The receipt-token report boundary is the only unauthenticated report status and
 * confirmation path. A plain receipt token must never be logged or returned after issuance.
 * Callers receive it once from {@link #createReportWithReceipt}.
 */
public interface FacilityReportUseCase {

	FacilityReport createReport(CreateFacilityReportCommand command);

	CreatedFacilityReport createReportWithReceipt(CreateFacilityReportCommand command);

	Optional<FacilityReport> findReportByClientSubmissionId(String clientSubmissionId);

	FacilityReport getReport(String reportId);

	FacilityReport getUserReport(String reportId, String userId);

	FacilityReport getReportByReceiptToken(String reportId, String receiptToken);

	List<FacilityReport> listUserReports(String userId);

	PageResult<FacilityReportSummary> listUserReportSummaries(
		String userId,
		FacilityReportPageRequest pageRequest
	);

	List<FacilityReport> listReports(FacilityReportStatus status);

	PageResult<FacilityReportSummary> listReportSummaries(
		FacilityReportStatus status,
		FacilityReportPageRequest pageRequest
	);

	PageResult<FacilityReportSummary> searchReportSummaries(FacilityReportListQuery query);

	long countReports(FacilityReportListQuery query);

	Map<FacilityReportStatus, Long> countReportsByStatus();

	long countReportsCreatedSince(LocalDateTime cutoff);

	ReportProcessingTimeSummary summarizeReportProcessingTime();

	List<RepeatedBrokenFacilityReportSummary> listRepeatedBrokenReportFacilities();

	/** 같은 역·시설로 접수된 신고 요약(최신순, 상한 적용). 드로어의 "같은 시설 신고 목록"에 쓴다. */
	List<FacilityReportSummary> listReportsForFacility(String stationId, String facilityId);

	/** 대기 제보 수를 역 단위로 집계한다(역 목록 "미확인 제보" 뱃지용). 대기 없는 역은 결과에 없다. */
	java.util.Map<String, Long> countPendingReportsByStation();

	FacilityReport reviewReport(ReviewFacilityReportCommand command);

	FacilityReport confirmReportResult(String reportId, String userId);

	FacilityReport confirmReportResultByReceiptToken(String reportId, String receiptToken);

	List<FacilityReportReviewAudit> listReviewAudits(String reportId);
}
