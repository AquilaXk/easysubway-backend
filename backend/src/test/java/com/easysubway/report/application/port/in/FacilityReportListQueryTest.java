package com.easysubway.report.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 신고 목록 질의의 접수일 경계")
class FacilityReportListQueryTest {

	@Test
	@DisplayName("기간을 지정하지 않으면 경계가 비어 있다")
	void boundsAreEmptyWithoutDates() {
		var query = FacilityReportListQuery.of(null, null, null, null, null, null, null, null, null, null);

		assertThat(query.createdFromUtc()).isEmpty();
		assertThat(query.createdBeforeUtc()).isEmpty();
	}

	@Test
	@DisplayName("한국 날짜 하루는 UTC 벽시계의 전날 15시 이상, 다음 날 15시 미만이다")
	void koreanDayMapsToUtcRange() {
		var query = FacilityReportListQuery.of(
			null, null, null, null, null, LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), null, null, null);

		assertThat(query.createdFromUtc()).contains(LocalDateTime.of(2026, 6, 16, 15, 0));
		assertThat(query.createdBeforeUtc()).contains(LocalDateTime.of(2026, 6, 17, 15, 0));
	}
}
