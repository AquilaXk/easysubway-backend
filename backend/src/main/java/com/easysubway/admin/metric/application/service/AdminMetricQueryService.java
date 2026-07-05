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

	/**
	 * 지표 키별로 최근 기간과 직전 동일 기간의 합계를 비교해 증감을 계산한다("어제보다 나빠졌는가"에 즉답).
	 *
	 * <p>기간은 7/30/90일로 정규화한다. 스냅샷이 없는 날은 0으로 본다(경계 안정). 증감률은
	 * 직전 기간 합계가 0이면 정의하지 않는다(null): 0에서의 증가는 비율로 표현할 수 없다.
	 */
	public List<AdminMetricComparison> compare(Collection<String> requestedKeys, int requestedDays) {
		int days = ALLOWED_DAYS.contains(requestedDays) ? requestedDays : DEFAULT_DAYS;
		List<String> keys = normalizeKeys(requestedKeys);
		LocalDate today = LocalDate.now(clock);
		LocalDate currentFrom = today.minusDays(days - 1L);
		LocalDate previousTo = currentFrom.minusDays(1);
		LocalDate previousFrom = previousTo.minusDays(days - 1L);

		Map<String, Map<LocalDate, Double>> byKey = new HashMap<>();
		for (AdminMetricDaily row : repository.findByKeysAndDateRange(keys, previousFrom, today)) {
			byKey.computeIfAbsent(row.metricKey(), key -> new HashMap<>()).put(row.metricDate(), row.value());
		}

		return keys.stream()
			.map(key -> {
				Map<LocalDate, Double> valuesByDate = byKey.getOrDefault(key, Map.of());
				boolean rate = AdminMetricKeys.isRate(key);
				double current = aggregate(valuesByDate, currentFrom, today, rate);
				double previous = aggregate(valuesByDate, previousFrom, previousTo, rate);
				Double deltaPercent = previous == 0.0 ? null : (current - previous) * 100 / previous;
				return new AdminMetricComparison(
					key, AdminMetricKeys.label(key), days, current, previous, current - previous, deltaPercent);
			})
			.toList();
	}

	/**
	 * 기간 집계: 건수 지표는 합계(결측일 0), 비율·평균 지표는 값이 있는 날의 평균(결측일 제외)으로 모은다.
	 * 비율을 합산하면(예: 7일 차단률 합) 무의미하므로 평균으로 본다.
	 */
	private static double aggregate(Map<LocalDate, Double> valuesByDate, LocalDate from, LocalDate to, boolean rate) {
		if (!rate) {
			return from.datesUntil(to.plusDays(1))
				.mapToDouble(date -> valuesByDate.getOrDefault(date, 0.0))
				.sum();
		}
		double[] present = from.datesUntil(to.plusDays(1))
			.filter(valuesByDate::containsKey)
			.mapToDouble(valuesByDate::get)
			.toArray();
		if (present.length == 0) {
			return 0.0;
		}
		double sum = 0.0;
		for (double value : present) {
			sum += value;
		}
		return sum / present.length;
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

	/**
	 * 지표 키의 기간 비교.
	 *
	 * @param key          지표 키
	 * @param label        한글 표시 라벨
	 * @param days         비교 기간(일)
	 * @param current      최근 기간 합계(결측일 0)
	 * @param previous     직전 동일 기간 합계(결측일 0)
	 * @param delta        current - previous
	 * @param deltaPercent 증감률(%), 직전 기간이 0이면 null(정의 불가)
	 */
	public record AdminMetricComparison(
		String key,
		String label,
		int days,
		double current,
		double previous,
		double delta,
		Double deltaPercent
	) {

		public boolean improved(boolean higherIsBetter) {
			return higherIsBetter ? delta > 0 : delta < 0;
		}
	}
}
