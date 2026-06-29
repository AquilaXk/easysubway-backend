package com.easysubway.report.adapter.out.persistence;

import com.easysubway.common.domain.PageResult;
import com.easysubway.report.application.port.in.FacilityReportPageRequest;
import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportSummary;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import com.easysubway.report.domain.RepeatedBrokenFacilityReportSummary;
import com.easysubway.report.domain.ReportProcessingTimeSummary;
import com.easysubway.user.application.port.out.AnonymizeUserFacilityReportPort;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryFacilityReportRepository implements
	LoadFacilityReportPort,
	SaveFacilityReportPort,
	AnonymizeUserFacilityReportPort {

	private static final String DELETED_DESCRIPTION = "사용자 데이터 삭제로 신고 내용이 삭제되었습니다.";

	private final Map<String, FacilityReport> reports = new ConcurrentHashMap<>();

	@Override
	public Optional<FacilityReport> loadReport(String reportId) {
		return Optional.ofNullable(reports.get(reportId));
	}

	@Override
	public Optional<FacilityReport> loadReportByClientSubmissionId(String clientSubmissionId) {
		if (clientSubmissionId == null || clientSubmissionId.isBlank()) {
			return Optional.empty();
		}
		return reports.values()
			.stream()
			.filter(report -> clientSubmissionId.trim().equals(report.clientSubmissionId()))
			.findFirst();
	}

	@Override
	public List<FacilityReport> loadReports() {
		return List.copyOf(reports.values());
	}

	@Override
	public PageResult<FacilityReportSummary> loadUserReportSummaries(
		String userId,
		FacilityReportPageRequest pageRequest
	) {
		List<FacilityReportSummary> summaries = reports.values()
			.stream()
			.filter(report -> !report.isAnonymizedUserData())
			.filter(report -> userId.equals(report.userId()))
			.sorted(reportOrder())
			.map(FacilityReportSummary::from)
			.toList();
		return page(summaries, pageRequest);
	}

	@Override
	public PageResult<FacilityReportSummary> loadReportSummaries(
		FacilityReportStatus status,
		FacilityReportPageRequest pageRequest
	) {
		List<FacilityReportSummary> summaries = reports.values()
			.stream()
			.filter(report -> status == null || report.status() == status)
			.sorted(reportOrder())
			.map(FacilityReportSummary::from)
			.toList();
		return page(summaries, pageRequest);
	}

	@Override
	public Map<FacilityReportStatus, Long> loadReportStatusCounts() {
		Map<FacilityReportStatus, Long> counts = new EnumMap<>(FacilityReportStatus.class);
		for (FacilityReport report : reports.values()) {
			counts.merge(report.status(), 1L, Long::sum);
		}
		return Map.copyOf(counts);
	}

	@Override
	public long countReportsCreatedSince(LocalDateTime cutoff) {
		return reports.values()
			.stream()
			.filter(report -> !report.createdAt().isBefore(cutoff))
			.count();
	}

	@Override
	public ReportProcessingTimeSummary loadReportProcessingTimeSummary() {
		List<Long> processingMinutes = reports.values()
			.stream()
			.filter(report -> report.reviewedAt() != null)
			.map(report -> Duration.between(report.createdAt(), report.reviewedAt()).toMinutes())
			.filter(minutes -> minutes >= 0)
			.toList();
		if (processingMinutes.isEmpty()) {
			return ReportProcessingTimeSummary.empty();
		}
		long averageMinutes = processingMinutes.stream()
			.mapToLong(Long::longValue)
			.sum() / processingMinutes.size();
		return new ReportProcessingTimeSummary(processingMinutes.size(), averageMinutes);
	}

	@Override
	public List<RepeatedBrokenFacilityReportSummary> loadRepeatedBrokenReportFacilities() {
		Map<FacilityKey, Long> counts = new HashMap<>();
		for (FacilityReport report : reports.values()) {
			if (report.reportType() != FacilityReportType.BROKEN) {
				continue;
			}
			counts.merge(new FacilityKey(report.stationId(), report.facilityId()), 1L, Long::sum);
		}
		return counts.entrySet()
			.stream()
			.filter(entry -> entry.getValue() >= 2)
			.map(entry -> new RepeatedBrokenFacilityReportSummary(
				entry.getKey().stationId(),
				entry.getKey().facilityId(),
				entry.getValue()
			))
			.sorted(Comparator
				.comparingLong(RepeatedBrokenFacilityReportSummary::reportCount)
				.reversed()
				.thenComparing(RepeatedBrokenFacilityReportSummary::stationId)
				.thenComparing(RepeatedBrokenFacilityReportSummary::facilityId))
			.toList();
	}

	@Override
	public FacilityReport saveReport(FacilityReport report) {
		reports.put(report.id(), report);
		return report;
	}

	@Override
	public synchronized Optional<FacilityReport> saveReviewedReportIfStatus(
		FacilityReport report,
		FacilityReportStatus expectedStatus
	) {
		FacilityReport currentReport = reports.get(report.id());
		if (currentReport == null || currentReport.status() != expectedStatus) {
			return Optional.empty();
		}
		reports.put(report.id(), report);
		return Optional.of(report);
	}

	@Override
	public int anonymizeFacilityReportsByUserId(String userId) {
		int anonymizedCount = 0;
		for (FacilityReport report : loadReports()) {
			if (!userId.equals(report.userId())) {
				continue;
			}
			reports.put(report.id(), anonymized(report));
			anonymizedCount++;
		}
		return anonymizedCount;
	}

	private FacilityReport anonymized(FacilityReport report) {
		// 삭제 요청 이후에는 운영 검수 이력만 남기고 사용자가 남긴 본문, 사진, 위치는 제거한다.
		return new FacilityReport(
			report.id(),
			report.publicReceiptCode(),
			FacilityReport.ANONYMIZED_USER_ID,
			report.stationId(),
			report.facilityId(),
			report.reportType(),
			DELETED_DESCRIPTION,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			report.duplicateOfReportId(),
			report.status(),
			report.createdAt(),
			report.reviewedAt(),
			report.reviewedBy(),
			null,
			null
		);
	}

	private Comparator<FacilityReport> reportOrder() {
		return Comparator
			.comparing(FacilityReport::createdAt)
			.reversed()
			.thenComparing(FacilityReport::id);
	}

	private PageResult<FacilityReportSummary> page(
		List<FacilityReportSummary> summaries,
		FacilityReportPageRequest pageRequest
	) {
		int fromIndex = Math.min(pageRequest.offset(), summaries.size());
		int toIndex = Math.min(fromIndex + pageRequest.size(), summaries.size());
		boolean hasNext = toIndex < summaries.size();
		return new PageResult<>(summaries.subList(fromIndex, toIndex), pageRequest.page(), pageRequest.size(), hasNext);
	}

	private record FacilityKey(String stationId, String facilityId) {
	}
}
