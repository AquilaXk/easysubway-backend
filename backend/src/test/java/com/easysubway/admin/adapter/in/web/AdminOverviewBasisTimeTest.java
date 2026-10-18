package com.easysubway.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 개요의 기준 시각 표시")
class AdminOverviewBasisTimeTest {

	@Test
	@DisplayName("기준 시각은 UTC가 아니라 한국 표준시로 표시한다")
	void basisTimeIsShownInKoreanStandardTime() {
		assertThat(AdminOverviewPageController.basisTimeLabel(Instant.parse("2026-10-18T03:04:05Z")))
			.isEqualTo("12:04");
	}

	@Test
	@DisplayName("UTC 전날 오후도 한국 날짜가 넘어간 새벽 시각으로 표시한다")
	void crossesMidnightInKoreanStandardTime() {
		assertThat(AdminOverviewPageController.basisTimeLabel(Instant.parse("2026-10-17T16:00:00Z")))
			.isEqualTo("01:00");
	}
}
