package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("운영기관 리포트 표준 테이블 query")
class OperatorReportQueryTest {

	@Test
	@DisplayName("검색어는 여러 텍스트 열 중 하나라도 포함하면 행을 유지한다")
	void keywordMatchesAnyTextColumn() {
		OperatorReportQuery query = new OperatorReportQuery("상록수", null, null, "station", "asc");

		assertThat(query.matches("상록수", "1번 출구 엘리베이터")).isTrue();
		assertThat(query.matches("사당", "장애인 화장실")).isFalse();
	}

	@Test
	@DisplayName("기간 필터는 yyyy-MM-dd HH:mm 라벨의 날짜를 포함 범위로 판정한다")
	void dateRangeIncludesLabelDate() {
		OperatorReportQuery query = new OperatorReportQuery(
			"",
			LocalDate.parse("2026-06-18"),
			LocalDate.parse("2026-06-19"),
			"createdAt",
			"desc"
		);

		assertThat(query.includesDateLabel("2026-06-18 10:00")).isTrue();
		assertThat(query.includesDateLabel("2026-06-17 23:59")).isFalse();
	}
}
