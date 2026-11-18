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
			deltaPercentLabel(comparison),
			tone,
			comparison.delta() > 0
		);
	}

	/**
	 * 증감률 표시 문구. 직전 기간에 스냅샷이 없으면 "직전 없음", 직전 실측값이 0이라 증감률이 정의
	 * 불가하면 "기준 0 — 증가율 산정 불가"로 구분한다(#2273: 실측 0과 스냅샷 부재 구분).
	 */
	private static String deltaPercentLabel(AdminMetricComparison comparison) {
		if (comparison.deltaPercent() != null) {
			return "%+.1f%%".formatted(comparison.deltaPercent());
		}
		return comparison.previousPresent() ? "기준 0 — 증가율 산정 불가" : "직전 없음";
	}

	private static String formatValue(double value) {
		return value == Math.rint(value) ? "%.0f".formatted(value) : "%.1f".formatted(value);
	}
}
