package com.easysubway.report.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.report.domain.FacilityReport;
import com.easysubway.report.domain.FacilityReportStatus;
import com.easysubway.report.domain.FacilityReportType;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("인메모리 시설 신고 저장소")
class InMemoryFacilityReportRepositoryTest {

	private final InMemoryFacilityReportRepository repository = new InMemoryFacilityReportRepository();

	@Test
	@DisplayName("역별 미처리 신고 수는 접수됨·검토 중 상태만 센다")
	void countsPendingReportsByStationForSubmittedAndUnderReviewOnly() {
		repository.saveReport(report("a-1", "station-a", FacilityReportStatus.SUBMITTED));
		repository.saveReport(report("a-2", "station-a", FacilityReportStatus.UNDER_REVIEW));
		repository.saveReport(report("a-3", "station-a", FacilityReportStatus.ACCEPTED));
		repository.saveReport(report("b-1", "station-b", FacilityReportStatus.REJECTED));

		assertThat(repository.countPendingReportsByStation()).isEqualTo(Map.of("station-a", 2L));
	}

	private static FacilityReport report(String reportId, String stationId, FacilityReportStatus status) {
		return new FacilityReport(
			reportId, "anonymous-user-1", stationId, "facility-1", FacilityReportType.BROKEN, "신고 내용",
			null, null, null, null, null, null, status, LocalDateTime.of(2026, 6, 17, 9, 0), null, null);
	}
}
