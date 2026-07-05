package com.easysubway.admin.metric.adapter.in.web;

import com.easysubway.admin.audit.application.service.AdminAuditWriter;
import com.easysubway.admin.audit.domain.AdminAuditOutcome;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricComparison;
import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricSeries;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경로·사용 분석 데이터 API(#1744). 분석 화면(경로 검색·피드백·사용 현황)이 소비할 시계열과
 * 기간 비교(전 기간 대비 증감)를 돌려주고, 현재 필터 상태 그대로 CSV로 내보낸다. 대시보드 요약
 * API({@code /admin/dashboard/metrics})와 같은 {@link AdminMetricQueryService}를 재사용해 수치 정합을 보장한다.
 *
 * <p>권한은 /admin/** 기본 규칙(ADMIN_VIEW)을 따른다. 기간 전환은 이 엔드포인트 재호출로 부분 갱신한다.
 */
@RestController
class AdminAnalyticsMetricController {

	// CSV는 UTF-8 BOM으로 시작해 엑셀에서 한글이 깨지지 않게 한다.
	private static final String UTF8_BOM = "﻿";
	// 기간이 7/30/90으로 정규화돼 최대 90행이지만, 방어적으로 상한을 둔다.
	private static final int MAX_EXPORT_ROWS = 366;

	private final AdminMetricQueryService metricQueryService;
	private final AdminAuditWriter auditWriter;

	AdminAnalyticsMetricController(AdminMetricQueryService metricQueryService, AdminAuditWriter auditWriter) {
		this.metricQueryService = metricQueryService;
		this.auditWriter = auditWriter;
	}

	@GetMapping("/admin/analytics/metrics")
	AdminMetricChart metrics(
		@RequestParam(name = "keys", required = false) List<String> keys,
		@RequestParam(name = "days", defaultValue = "7") int days
	) {
		return metricQueryService.chart(keys == null ? List.of() : keys, days);
	}

	@GetMapping("/admin/analytics/comparison")
	List<AdminMetricComparison> comparison(
		@RequestParam(name = "keys", required = false) List<String> keys,
		@RequestParam(name = "days", defaultValue = "7") int days
	) {
		return metricQueryService.compare(keys == null ? List.of() : keys, days);
	}

	/**
	 * 현재 필터(keys·days) 상태 그대로 시계열을 CSV로 내보낸다. UTF-8 BOM(엑셀 호환)·행 수 상한을
	 * 적용하고, 누가 무엇을 내보냈는지 감사에 기록한다(집계 값이라 PII 없음).
	 */
	@GetMapping("/admin/analytics/metrics/export")
	ResponseEntity<byte[]> exportCsv(
		@RequestParam(name = "keys", required = false) List<String> keys,
		@RequestParam(name = "days", defaultValue = "7") int days,
		Authentication authentication,
		HttpServletRequest request
	) {
		AdminMetricChart chart = metricQueryService.chart(keys == null ? List.of() : keys, days);
		int rowCount = Math.min(chart.labels().size(), MAX_EXPORT_ROWS);
		byte[] body = toCsv(chart, rowCount).getBytes(StandardCharsets.UTF_8);

		auditWriter.analyticsExport(
			authentication,
			request,
			"analytics-metrics",
			"EXPORT_ANALYTICS_CSV",
			AdminAuditOutcome.SUCCESS,
			"keys=%d days=%d rows=%d".formatted(chart.series().size(), chart.days(), rowCount));

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
		headers.setContentDisposition(ContentDisposition.attachment()
			.filename("analytics-metrics-%dd.csv".formatted(chart.days()))
			.build());
		return ResponseEntity.ok().headers(headers).body(body);
	}

	private static String toCsv(AdminMetricChart chart, int rowCount) {
		StringBuilder csv = new StringBuilder(UTF8_BOM);
		csv.append("date");
		for (AdminMetricSeries series : chart.series()) {
			csv.append(',').append(csvField(series.label()));
		}
		csv.append("\r\n");
		for (int row = 0; row < rowCount; row++) {
			csv.append(csvField(chart.labels().get(row)));
			for (AdminMetricSeries series : chart.series()) {
				Double value = series.values().get(row);
				csv.append(',').append(value == null ? "" : csvField(stripTrailingZero(value)));
			}
			csv.append("\r\n");
		}
		return csv.toString();
	}

	private static String stripTrailingZero(double value) {
		return value == Math.rint(value) ? "%.0f".formatted(value) : Double.toString(value);
	}

	private static String csvField(String value) {
		if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
			return '"' + value.replace("\"", "\"\"") + '"';
		}
		return value;
	}
}
