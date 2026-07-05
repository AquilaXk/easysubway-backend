package com.easysubway.report.application.port.out;

import com.easysubway.common.domain.PageResult;
import com.easysubway.report.application.port.in.FacilityReportListQuery;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LoadFacilityReportPort {

	Optional<FacilityReport> loadReport(String reportId);

	Optional<FacilityReport> loadReportByClientSubmissionId(String clientSubmissionId);

	List<FacilityReport> loadReports();

	PageResult<FacilityReportSummary> loadUserReportSummaries(String userId, FacilityReportPageRequest pageRequest);

	PageResult<FacilityReportSummary> loadReportSummaries(
		FacilityReportStatus status,
		FacilityReportPageRequest pageRequest
	);

	PageResult<FacilityReportSummary> loadReportSummaries(FacilityReportListQuery query);

	long countReports(FacilityReportListQuery query);

	Map<FacilityReportStatus, Long> loadReportStatusCounts();

	long countReportsCreatedSince(LocalDateTime cutoff);

	ReportProcessingTimeSummary loadReportProcessingTimeSummary();

	List<RepeatedBrokenFacilityReportSummary> loadRepeatedBrokenReportFacilities();

	/**
	 * 같은 역·시설로 접수된 신고 요약을 최신순으로 조회한다(드로어의 "같은 시설 신고 목록"용).
	 * 목록이 폭주하지 않도록 limit로 상한을 둔다. 호출부에서 현재 신고는 제외한다.
	 */
	List<FacilityReportSummary> loadReportsForFacility(String stationId, String facilityId, int limit);

	/**
	 * 대기 상태(SUBMITTED·UNDER_REVIEW) 제보 수를 역 단위로 집계한다(역 목록 "미확인 제보" 뱃지용).
	 * 단일 GROUP BY 집계라 역별 N+1 조회를 피한다. 대기 제보가 없는 역은 결과에 담기지 않는다.
	 */
	Map<String, Long> countPendingReportsByStation();
}
