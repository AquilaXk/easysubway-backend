package com.easysubway.admin.metric.domain;

import java.time.LocalDate;

/**
 * 통합 대시보드(#1739)의 일별 지표 스냅샷 한 점.
 *
 * <p>집계 배치가 하루 1회 지표 키별 값을 적재하고, 같은 (지표 키, 날짜) 재실행은 upsert로 멱등하게
 * 갱신한다. 추이·전일 대비·스파크라인은 이 스냅샷들을 날짜 범위로 읽어 구성한다.
 *
 * @param metricKey  지표 식별자(reports.submitted 등)
 * @param metricDate 집계 대상 날짜(하루 단위)
 * @param value      집계 값(건수·소요시간·비율 모두 double로 담는다)
 * @param dimensions 세부 분해 JSON 텍스트(스칼라 지표는 null)
 */
public record AdminMetricDaily(String metricKey, LocalDate metricDate, double value, String dimensions) {

	public static final int MAX_KEY_LENGTH = 80;
	public static final int MAX_DIMENSIONS_LENGTH = 2000;

	public AdminMetricDaily {
		if (metricKey == null || metricKey.isBlank()) {
			throw new InvalidAdminMetricException("지표 키가 필요합니다.");
		}
		metricKey = metricKey.trim();
		if (metricKey.length() > MAX_KEY_LENGTH) {
			throw new InvalidAdminMetricException("지표 키는 %d자 이하여야 합니다.".formatted(MAX_KEY_LENGTH));
		}
		if (metricDate == null) {
			throw new InvalidAdminMetricException("지표 날짜가 필요합니다.");
		}
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			throw new InvalidAdminMetricException("지표 값은 유한한 수여야 합니다.");
		}
		if (dimensions != null) {
			dimensions = dimensions.isBlank() ? null : dimensions.trim();
			if (dimensions != null && dimensions.length() > MAX_DIMENSIONS_LENGTH) {
				throw new InvalidAdminMetricException(
					"지표 분해 JSON은 %d자 이하여야 합니다.".formatted(MAX_DIMENSIONS_LENGTH));
			}
		}
	}

	/** 세부 분해 없는 스칼라 지표 한 점을 만든다. */
	public static AdminMetricDaily scalar(String metricKey, LocalDate metricDate, double value) {
		return new AdminMetricDaily(metricKey, metricDate, value, null);
	}
}
