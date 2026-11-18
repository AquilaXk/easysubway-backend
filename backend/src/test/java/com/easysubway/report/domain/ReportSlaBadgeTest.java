package com.easysubway.report.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("제보 SLA 경과 뱃지")
class ReportSlaBadgeTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 5, 12, 0);

	@Test
	@DisplayName("대기 상태가 72시간을 넘기면 위반(bad) 뱃지를 준다")
	void breachAfter72Hours() {
		ReportSlaBadge badge = ReportSlaBadge.of(
			FacilityReportStatus.SUBMITTED, NOW.minusHours(80), NOW);

		assertThat(badge.present()).isTrue();
		assertThat(badge.label()).isEqualTo("72시간 초과");
		assertThat(badge.tone()).isEqualTo("bad");
	}

	@Test
	@DisplayName("대기 상태가 24시간을 넘기면 경고(warn) 뱃지를 준다")
	void warnAfter24Hours() {
		ReportSlaBadge badge = ReportSlaBadge.of(
			FacilityReportStatus.UNDER_REVIEW, NOW.minusHours(30), NOW);

		assertThat(badge.label()).isEqualTo("24시간 초과");
		assertThat(badge.tone()).isEqualTo("warn");
	}

	@Test
	@DisplayName("24시간 미만이면 뱃지가 없다")
	void noneWithinThreshold() {
		assertThat(ReportSlaBadge.of(FacilityReportStatus.SUBMITTED, NOW.minusHours(10), NOW).present())
			.isFalse();
	}

	@Test
	@DisplayName("종결 상태는 경과가 길어도 뱃지가 없다")
	void terminalStatusHasNoBadge() {
		assertThat(ReportSlaBadge.of(FacilityReportStatus.ACCEPTED, NOW.minusHours(100), NOW).present())
			.isFalse();
		assertThat(ReportSlaBadge.of(FacilityReportStatus.DUPLICATE, NOW.minusHours(100), NOW).present())
			.isFalse();
	}

	@Test
	@DisplayName("접수 시간이 없으면 뱃지가 없다")
	void nullCreatedAtHasNoBadge() {
		assertThat(ReportSlaBadge.of(FacilityReportStatus.SUBMITTED, null, NOW).present()).isFalse();
	}
}
