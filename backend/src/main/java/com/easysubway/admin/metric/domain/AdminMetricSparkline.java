package com.easysubway.admin.metric.domain;

import java.util.List;

/**
 * 카드 스파크라인(#1739)을 서버에서 SVG polyline 좌표 문자열로 만든다.
 *
 * <p>JS 없이도 추이가 보이고(진화형 향상·접근성), 결측(null) 점은 건너뛴다. 값이 1개 이하면
 * 그릴 게 없어 빈 문자열을 돌려준다(템플릿이 스파크라인을 생략).
 */
public final class AdminMetricSparkline {

	private AdminMetricSparkline() {
	}

	/**
	 * {@code viewBox="0 0 width height"} 기준 polyline points 문자열.
	 * 값은 min~max를 세로로 정규화하고(위가 큰 값), 인덱스를 가로로 균등 배치한다.
	 */
	public static String points(List<Double> values, int width, int height) {
		List<Double> present = values.stream().filter(value -> value != null).toList();
		if (present.size() < 2) {
			return "";
		}
		double min = present.stream().mapToDouble(Double::doubleValue).min().orElse(0);
		double max = present.stream().mapToDouble(Double::doubleValue).max().orElse(0);
		double span = max - min;
		int lastIndex = values.size() - 1;
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index <= lastIndex; index++) {
			Double value = values.get(index);
			if (value == null) {
				continue;
			}
			double x = lastIndex == 0 ? 0 : (double) index * width / lastIndex;
			double y = span == 0 ? height / 2.0 : height - (value - min) / span * height;
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(round(x)).append(',').append(round(y));
		}
		return builder.toString();
	}

	private static String round(double value) {
		return String.valueOf(Math.round(value * 10) / 10.0);
	}
}
