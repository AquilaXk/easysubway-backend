package com.easysubway.admin.metric.adapter.out.persistence;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!prod & !staging & !release & !prod-like")
public class InMemoryAdminMetricDailyRepository implements AdminMetricDailyRepository {

	private final Map<String, AdminMetricDaily> metrics = new ConcurrentHashMap<>();

	@Override
	public void save(AdminMetricDaily metric) {
		metrics.put(key(metric.metricKey(), metric.metricDate()), metric);
	}

	@Override
	public Optional<AdminMetricDaily> find(String metricKey, LocalDate metricDate) {
		return Optional.ofNullable(metrics.get(key(metricKey, metricDate)));
	}

	@Override
	public List<AdminMetricDaily> findByKeysAndDateRange(
		Collection<String> metricKeys, LocalDate fromInclusive, LocalDate toInclusive) {
		return metrics.values().stream()
			.filter(metric -> metricKeys.contains(metric.metricKey()))
			.filter(metric -> !metric.metricDate().isBefore(fromInclusive)
				&& !metric.metricDate().isAfter(toInclusive))
			.sorted(Comparator.comparing(AdminMetricDaily::metricKey).thenComparing(AdminMetricDaily::metricDate))
			.toList();
	}

	private static String key(String metricKey, LocalDate metricDate) {
		return metricKey + "@" + metricDate;
	}
}
