package com.easysubway.admin.metric.application.service;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import com.easysubway.admin.metric.domain.AdminMetricKeys;
import com.easysubway.admin.metric.domain.AdminMetricKeys.AdminMetricKind;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
	 * 지표 키별로 최근 기간과 직전 동일 기간을 비교해 증감을 계산한다("어제보다 나빠졌는가"에 즉답).
	 *
	 * <p>집계 의미는 지표 종류가 정한다(#2273): {@code DAILY_COUNTER}만 {@code [from, to]} 기간
	 * 합계로 비교하고, {@code GAUGE}·{@code RATE}·{@code ROLLING_WINDOW}는 누계·비율·이동 기간이라
	 * 합산이 중복이므로 기간 내 최신 스냅샷끼리 비교한다.
	 *
	 * <p>기간은 7/30/90일로 정규화한다. 증감률은 직전 기간 값이 0이면 정의하지 않는다(null):
	 * 0에서의 증가는 비율로 표현할 수 없다.
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
				AdminMetricKind kind = AdminMetricKeys.kind(key);
				double current = aggregate(valuesByDate, currentFrom, today, kind);
				double previous = aggregate(valuesByDate, previousFrom, previousTo, kind);
				boolean previousPresent = hasSnapshot(valuesByDate, previousFrom, previousTo);
				Double deltaPercent = previous == 0.0 ? null : (current - previous) * 100 / previous;
				return new AdminMetricComparison(
					key, AdminMetricKeys.label(key), days, current, previous, current - previous, deltaPercent,
					previousPresent);
			})
			.toList();
	}

	/**
	 * 지표 종류별 기간 집계(#2273). 일별 counter만 기간 합계(결측일 0)로 모으고, 누계·비율·이동
	 * 기간 지표는 기간 내 최신 스냅샷 값을 그대로 쓴다(스냅샷이 하나도 없으면 0).
	 */
	private static double aggregate(
		Map<LocalDate, Double> valuesByDate, LocalDate from, LocalDate to, AdminMetricKind kind) {
		return switch (kind) {
			case DAILY_COUNTER -> from.datesUntil(to.plusDays(1))
				.mapToDouble(date -> valuesByDate.getOrDefault(date, 0.0))
				.sum();
			case GAUGE, RATE, ROLLING_WINDOW -> latestSnapshot(valuesByDate, from, to);
		};
	}

	/** 기간 {@code [from, to]} 안에서 가장 최근 날짜의 스냅샷 값. 값이 하나도 없으면 0. */
	private static double latestSnapshot(Map<LocalDate, Double> valuesByDate, LocalDate from, LocalDate to) {
		for (LocalDate date = to; !date.isBefore(from); date = date.minusDays(1)) {
			Double value = valuesByDate.get(date);
			if (value != null) {
				return value;
			}
		}
		return 0.0;
	}

	/**
	 * 기간 {@code [from, to]} 안에 스냅샷이 하나라도 있는지 여부. 집계값이 0.0이어도 실측된 0인지
	 * 스냅샷 자체가 없어 0으로 채운 것인지 구분한다(#2273: "실측 0"과 "직전 없음" 구분).
	 */
	private static boolean hasSnapshot(Map<LocalDate, Double> valuesByDate, LocalDate from, LocalDate to) {
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			if (valuesByDate.containsKey(date)) {
				return true;
			}
		}
		return false;
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

		public AdminMetricChart {
			labels = labels == null ? null : new ArrayList<>(labels);
			series = series == null ? null : new ArrayList<>(series);
		}

		@Override
		public List<String> labels() {
			return labels == null ? null : new ArrayList<>(labels);
		}

		@Override
		public List<AdminMetricSeries> series() {
			return series == null ? null : new ArrayList<>(series);
		}

		/** 조회 기간 내 모든 시리즈가 결측(null)이면 true — 빈 상태 렌더 분기(#2327)에 쓰인다. */
		public boolean empty() {
			return series.stream().allMatch(s -> s.values().stream().allMatch(Objects::isNull));
		}
	}

	/**
	 * @param key    지표 키
	 * @param label  한글 표시 라벨
	 * @param values labels와 같은 길이의 값 배열(결측일은 null)
	 */
	public record AdminMetricSeries(String key, String label, List<Double> values) {

		public AdminMetricSeries {
			values = values == null ? null : new ArrayList<>(values);
		}

		@Override
		public List<Double> values() {
			return values == null ? null : new ArrayList<>(values);
		}
	}

	/**
	 * 지표 키의 기간 비교.
	 *
	 * @param key          지표 키
	 * @param label        한글 표시 라벨
	 * @param days         비교 기간(일)
	 * @param current         최근 기간 집계(일별 counter는 합계, 그 외는 기간 내 최신 스냅샷)
	 * @param previous        직전 동일 기간 집계(집계 방식은 current와 동일)
	 * @param delta           current - previous
	 * @param deltaPercent    증감률(%), 직전 기간이 0이면 null(정의 불가)
	 * @param previousPresent 직전 기간에 스냅샷이 하나라도 있었는지. previous가 0.0일 때 실측된 0인지
	 *                        (true) 스냅샷 부재로 0으로 채운 것인지(false)를 구분한다(#2273)
	 */
	public record AdminMetricComparison(
		String key,
		String label,
		int days,
		double current,
		double previous,
		double delta,
		Double deltaPercent,
		boolean previousPresent
	) {

		public boolean improved(boolean higherIsBetter) {
			return higherIsBetter ? delta > 0 : delta < 0;
		}
	}
}
