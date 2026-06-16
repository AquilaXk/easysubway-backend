package com.easysubway.report.adapter.out.persistence;

import com.easysubway.report.application.port.out.LoadFacilityReportPort;
import com.easysubway.report.application.port.out.SaveFacilityReportPort;
import com.easysubway.report.domain.FacilityReport;
import com.easysubway.user.application.port.out.AnonymizeUserFacilityReportPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod")
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
	public List<FacilityReport> loadReports() {
		return List.copyOf(reports.values());
	}

	@Override
	public FacilityReport saveReport(FacilityReport report) {
		reports.put(report.id(), report);
		return report;
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
			report.duplicateOfReportId(),
			report.status(),
			report.createdAt(),
			report.reviewedAt(),
			report.reviewedBy()
		);
	}
}
