package com.easysubway.admin.metric.adapter.out.persistence;

import com.easysubway.admin.metric.application.port.out.AdminMetricDailyRepository;
import com.easysubway.admin.metric.domain.AdminMetricDaily;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcAdminMetricDailyRepository implements AdminMetricDailyRepository {

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	JdbcAdminMetricDailyRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcAdminMetricDailyRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void save(AdminMetricDaily metric) {
		// dialect 무관 upsert: 먼저 UPDATE, 없으면 INSERT. (지표 키, 날짜) 재실행이 멱등하다.
		int updated = jdbcTemplate.update(
			"UPDATE admin_metric_daily SET metric_value = ?, dimensions = ? WHERE metric_key = ? AND metric_date = ?",
			metric.value(),
			metric.dimensions(),
			metric.metricKey(),
			metric.metricDate()
		);
		if (updated == 0) {
			jdbcTemplate.update(
				"INSERT INTO admin_metric_daily (metric_key, metric_date, metric_value, dimensions) VALUES (?, ?, ?, ?)",
				metric.metricKey(),
				metric.metricDate(),
				metric.value(),
				metric.dimensions()
			);
		}
	}

	@Override
	public Optional<AdminMetricDaily> find(String metricKey, LocalDate metricDate) {
		return jdbcTemplate.query(
			"SELECT metric_key, metric_date, metric_value, dimensions FROM admin_metric_daily "
				+ "WHERE metric_key = ? AND metric_date = ?",
			this::mapMetric,
			metricKey,
			metricDate
		).stream().findFirst();
	}

	@Override
	public List<AdminMetricDaily> findByKeysAndDateRange(
		Collection<String> metricKeys, LocalDate fromInclusive, LocalDate toInclusive) {
		if (metricKeys.isEmpty()) {
			return List.of();
		}
		String placeholders = String.join(", ", metricKeys.stream().map(key -> "?").toList());
		Object[] args = new Object[metricKeys.size() + 2];
		int index = 0;
		for (String key : metricKeys) {
			args[index++] = key;
		}
		args[index++] = fromInclusive;
		args[index] = toInclusive;
		return jdbcTemplate.query(
			"SELECT metric_key, metric_date, metric_value, dimensions FROM admin_metric_daily "
				+ "WHERE metric_key IN (" + placeholders + ") AND metric_date BETWEEN ? AND ? "
				+ "ORDER BY metric_key ASC, metric_date ASC",
			this::mapMetric,
			args
		);
	}

	private AdminMetricDaily mapMetric(ResultSet rs, int rowNum) throws SQLException {
		return new AdminMetricDaily(
			rs.getString("metric_key"),
			rs.getObject("metric_date", LocalDate.class),
			rs.getDouble("metric_value"),
			rs.getString("dimensions")
		);
	}
}
