package com.easysubway.admin.metric.application.port.out;

import com.easysubway.admin.metric.domain.AdminMetricDaily;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 일별 지표 스냅샷 저장소(#1739).
 *
 * <p>{@link #save}는 (지표 키, 날짜) 기준 upsert로 멱등하다(집계 배치 재실행 안전).
 */
public interface AdminMetricDailyRepository {

	void save(AdminMetricDaily metric);

	Optional<AdminMetricDaily> find(String metricKey, LocalDate metricDate);

	/** 지표 키들의 [from, to] 날짜 범위 스냅샷을 지표 키·날짜 오름차순으로 돌려준다. */
	List<AdminMetricDaily> findByKeysAndDateRange(
		Collection<String> metricKeys, LocalDate fromInclusive, LocalDate toInclusive);
}
