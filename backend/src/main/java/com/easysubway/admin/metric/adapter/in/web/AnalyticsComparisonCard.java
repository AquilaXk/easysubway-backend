package com.easysubway.admin.metric.adapter.in.web;

import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricComparison;

/**
 * 분석 화면(#1744)의 전 기간 대비 증감 카드 뷰. 최근 기간 합계·증감률·개선 여부(tone)를 표시용으로
 * 정리한다. 지표마다 "높을수록 좋은지"가 달라(검색량·도움됨은 증가가 개선, 차단률·오류율은 감소가
 * 개선) {@code higherIsBetter}로 tone을 판정한다. 경로 검색·피드백·사용 현황 화면이 공유한다.
 */
public record AnalyticsComparisonCard(
	String label,
	String currentLabel,
	String previousLabel,
	String deltaPercentLabel,
	String tone,
	boolean up
) {

	public static AnalyticsComparisonCard from(AdminMetricComparison comparison, boolean higherIsBetter) {
		String tone = comparison.delta() == 0
			? "neutral"
			: (comparison.improved(higherIsBetter) ? "good" : "bad");
		return new AnalyticsComparisonCard(
			comparison.label(),
			formatValue(comparison.current()),
			formatValue(comparison.previous()),
			comparison.deltaPercent() == null ? "직전 없음" : "%+.1f%%".formatted(comparison.deltaPercent()),
			tone,
			comparison.delta() > 0
		);
	}

	private static String formatValue(double value) {
		return value == Math.rint(value) ? "%.0f".formatted(value) : "%.1f".formatted(value);
	}
}
