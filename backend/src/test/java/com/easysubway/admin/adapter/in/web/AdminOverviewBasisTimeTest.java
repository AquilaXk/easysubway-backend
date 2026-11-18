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

	@Test
	@DisplayName("관리자 개요 추이 차트 직렬화 실패 시 빈 시리즈 fallback 없이 예외를 던진다")
	void overviewTrendsFailsClosedWhenSerializationFails() {
		var metricQueryService = org.mockito.Mockito.mock(
			com.easysubway.admin.metric.application.service.AdminMetricQueryService.class
		);
		org.mockito.Mockito.when(
			metricQueryService.chart(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt())
		).thenReturn(
			new com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart(
				7, java.util.List.of(), java.util.List.of()
			)
		);
		org.mockito.Mockito.when(
			metricQueryService.compare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt())
		).thenReturn(java.util.List.of());
		var failingMapper = new com.fasterxml.jackson.databind.ObjectMapper() {
			@Override
			public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
				throw new com.fasterxml.jackson.core.JsonGenerationException("boom", (com.fasterxml.jackson.core.JsonGenerator) null);
			}
		};
		var controller = new AdminOverviewPageController(
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			metricQueryService,
			failingMapper,
			null
		);

		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			controller.dashboardTrends(7, new org.springframework.ui.ConcurrentModel())
		).isInstanceOf(IllegalStateException.class);
	}
}
