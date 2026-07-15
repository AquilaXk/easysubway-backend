package com.easysubway.admin.metric.application.service;

import com.easysubway.admin.metric.application.service.AdminMetricQueryService.AdminMetricChart;
import com.easysubway.admin.metric.domain.AdminMetricSparkline;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 대시보드 핵심 카드(#1739) 조립. 현재 값에 7일 스냅샷 스파크라인과 전일 대비 증감을 얹는다.
 *
 * <p>값·스파크라인·증감이 한 카드에서 "지금 얼마 → 어떻게 흘러왔나 → 어제보다"를 보여준다.
 * 스냅샷 이력이 없으면 스파크라인·증감은 비고 카드는 현재 값만 보여준다(진화형 향상).
 */
@Service
public class AdminDashboardCardService {

	private static final int SPARK_DAYS = 7;
	static final int SPARK_WIDTH = 100;
	static final int SPARK_HEIGHT = 24;

	private final AdminMetricQueryService metricQueryService;

	public AdminDashboardCardService(AdminMetricQueryService metricQueryService) {
		this.metricQueryService = metricQueryService;
	}

	public DashboardCard card(String title, String href, String metricKey, double currentValue, String valueLabel) {
		AdminMetricChart chart = metricQueryService.chart(List.of(metricKey), SPARK_DAYS);
		List<Double> values = chart.series().isEmpty() ? List.of() : chart.series().getFirst().values();
		String sparkPoints = AdminMetricSparkline.points(values, SPARK_WIDTH, SPARK_HEIGHT);
		Delta delta = delta(values, currentValue);
		return new DashboardCard(
			title, valueLabel, href, sparkPoints, delta.label(), delta.tone(), SPARK_WIDTH, SPARK_HEIGHT);
	}

	// 전일(마지막 인덱스 직전의 가장 최근 비결측 스냅샷) 대비 증감. 이력이 없으면 증감을 비운다.
	private static Delta delta(List<Double> values, double currentValue) {
		Double previous = null;
		for (int index = values.size() - 2; index >= 0; index--) {
			if (values.get(index) != null) {
				previous = values.get(index);
				break;
			}
		}
		if (previous == null) {
			return new Delta("전일 데이터 없음", "flat");
		}
		double diff = currentValue - previous;
		if (Math.abs(diff) < 0.05) {
			return new Delta("전일과 같음", "flat");
		}
		String magnitude = formatMagnitude(Math.abs(diff));
		return diff > 0
			? new Delta("전일 대비 +" + magnitude, "up")
			: new Delta("전일 대비 -" + magnitude, "down");
	}

	private static String formatMagnitude(double magnitude) {
		if (magnitude == Math.rint(magnitude)) {
			return String.valueOf((long) magnitude);
		}
		return "%.1f".formatted(magnitude);
	}

	/**
	 * @param title      카드 제목
	 * @param value      현재 값 표시 문자열
	 * @param href       카드 전체 클릭 시 이동할 화면
	 * @param sparkPoints SVG polyline points(비면 스파크라인 생략)
	 * @param deltaLabel 전일 대비 설명
	 * @param deltaTone  up·down·flat
	 * @param sparkWidth SVG viewBox 너비
	 * @param sparkHeight SVG viewBox 높이
	 */
	public record DashboardCard(
		String title,
		String value,
		String href,
		String sparkPoints,
		String deltaLabel,
		String deltaTone,
		int sparkWidth,
		int sparkHeight
	) {
	}

	private record Delta(String label, String tone) {
	}
}
