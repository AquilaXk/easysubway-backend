package com.easysubway.admin.metric.application.service;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 차트 데이터 조회(#1739). Chart.js가 소비할 시계열을 조립한다: 날짜 라벨과 지표 키별 값 배열
 * (스냅샷이 없는 날짜는 null로 비워 차트가 끊어 보이게 한다).
 *
 * <p>허용 기간은 7/30/90일이며, 벗어난 값·미등록 키는 기본값으로 정규화한다(입력 견고성).
 */
@Service
public class AdminMetricQueryService {

	private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);
	private static final int DEFAULT_DAYS = 7;
	private static final List<String> DEFAULT_KEYS =
		List.of(AdminMetricKeys.REPORTS_RECENT_24H, AdminMetricKeys.ROUTE_BLOCKED_RATE);

	private final AdminMetricDailyRepository repository;
	private final Clock clock;

	@Autowired
	public AdminMetricQueryService(AdminMetricDailyRepository repository, ObjectProvider<Clock> clockProvider) {
		this(repository, clockProvider.getIfAvailable(Clock::systemDefaultZone));
	}

	AdminMetricQueryService(AdminMetricDailyRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public AdminMetricChart chart(Collection<String> requestedKeys, int requestedDays) {
		int days = ALLOWED_DAYS.contains(requestedDays) ? requestedDays : DEFAULT_DAYS;
		List<String> keys = normalizeKeys(requestedKeys);
		LocalDate today = LocalDate.now(clock);
		LocalDate from = today.minusDays(days - 1L);
		List<LocalDate> dates = from.datesUntil(today.plusDays(1)).toList();

		Map<String, Map<LocalDate, Double>> byKey = new HashMap<>();
		for (AdminMetricDaily row : repository.findByKeysAndDateRange(keys, from, today)) {
			byKey.computeIfAbsent(row.metricKey(), key -> new HashMap<>()).put(row.metricDate(), row.value());
		}

		List<AdminMetricSeries> series = keys.stream()
			.map(key -> {
				Map<LocalDate, Double> valuesByDate = byKey.getOrDefault(key, Map.of());
				return new AdminMetricSeries(
					key,
					AdminMetricKeys.label(key),
					dates.stream().map(valuesByDate::get).toList());
			})
			.toList();
		List<String> labels = dates.stream().map(LocalDate::toString).toList();
		return new AdminMetricChart(days, labels, series);
	}

	private static List<String> normalizeKeys(Collection<String> requestedKeys) {
		List<String> keys = requestedKeys.stream()
			.filter(AdminMetricKeys::isKnown)
			.distinct()
			.toList();
		return keys.isEmpty() ? DEFAULT_KEYS : keys;
	}

	/**
	 * @param days   조회 기간(일)
	 * @param labels 날짜 라벨(ISO yyyy-MM-dd)
	 * @param series 지표 키별 시계열
	 */
	public record AdminMetricChart(int days, List<String> labels, List<AdminMetricSeries> series) {
	}

	/**
	 * @param key    지표 키
	 * @param label  한글 표시 라벨
	 * @param values labels와 같은 길이의 값 배열(결측일은 null)
	 */
	public record AdminMetricSeries(String key, String label, List<Double> values) {
	}
}
