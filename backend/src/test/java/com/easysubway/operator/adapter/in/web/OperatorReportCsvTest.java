package com.easysubway.operator.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("운영기관 리포트 CSV 직렬화")
class OperatorReportCsvTest {

	@Test
	@DisplayName("문서는 UTF-8 BOM으로 시작하고 CRLF로 줄을 나눈다")
	void documentStartsWithBomAndUsesCrlf() {
		String csv = OperatorReportCsv.document(
			List.of("이름", "값"),
			List.of(List.of("가", "1"), List.of("나", "2"))
		);

		assertThat(csv).startsWith("﻿이름,값\r\n");
		assertThat(csv).isEqualTo("﻿이름,값\r\n가,1\r\n나,2\r\n");
	}

	@Test
	@DisplayName("쉼표·따옴표·개행이 있는 값은 따옴표로 감싸고 따옴표는 중복한다")
	void quotesFieldsWithSpecialCharacters() {
		assertThat(OperatorReportCsv.field("가,나")).isEqualTo("\"가,나\"");
		assertThat(OperatorReportCsv.field("따\"옴")).isEqualTo("\"따\"\"옴\"");
		assertThat(OperatorReportCsv.field("줄\n바꿈")).isEqualTo("\"줄\n바꿈\"");
	}

	@Test
	@DisplayName("일반 값과 빈 값은 그대로 둔다")
	void leavesPlainAndEmptyValues() {
		assertThat(OperatorReportCsv.field("상록수역")).isEqualTo("상록수역");
		assertThat(OperatorReportCsv.field("")).isEqualTo("");
		assertThat(OperatorReportCsv.field(null)).isEqualTo("");
	}

	@Test
	@DisplayName("수식 문자로 시작하는 값은 작은따옴표를 앞에 붙여 수식 인젝션을 막는다")
	void guardsAgainstFormulaInjection() {
		assertThat(OperatorReportCsv.field("=SUM(A1)")).isEqualTo("'=SUM(A1)");
		assertThat(OperatorReportCsv.field("=1,2")).isEqualTo("\"'=1,2\"");
		assertThat(OperatorReportCsv.field("+1")).isEqualTo("'+1");
		assertThat(OperatorReportCsv.field("-cmd")).isEqualTo("'-cmd");
		assertThat(OperatorReportCsv.field("@ref")).isEqualTo("'@ref");
		assertThat(OperatorReportCsv.field("\n=SUM(A1)")).isEqualTo("\"'\n=SUM(A1)\"");
	}
}
