package com.easysubway.report.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.report.domain.ReportProcessingTimeSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("시설 신고 처리 시간 지표")
class FacilityReportProcessingTimeViewTest {

	@Test
	@DisplayName("검수 완료 신고의 평균 처리 시간과 처리 완료 건수를 표시한다")
	void processingTimeViewShowsAverageDurationForReviewedReports() {
		var view = FacilityReportAdminPageController.ReportProcessingTimeView.from(
			new ReportProcessingTimeSummary(2, 60)
		);

		assertThat(view.title()).isEqualTo("신고 처리 시간");
		assertThat(view.label()).isEqualTo("평균 1시간");
		assertThat(view.description()).isEqualTo("처리 완료 신고 2건 기준입니다.");
		assertThat(view.metricClass()).isEqualTo("ok");
	}

	@Test
	@DisplayName("검수 완료 신고가 없으면 빈 처리 상태를 표시한다")
	void processingTimeViewShowsEmptyStateWithoutReviewedReports() {
		var view = FacilityReportAdminPageController.ReportProcessingTimeView.from(ReportProcessingTimeSummary.empty());

		assertThat(view.title()).isEqualTo("신고 처리 시간");
		assertThat(view.label()).isEqualTo("처리 완료 신고 없음");
		assertThat(view.description()).isEqualTo("검수 완료 후 평균 처리 시간을 표시합니다.");
		assertThat(view.metricClass()).isEqualTo("empty");
	}
}
