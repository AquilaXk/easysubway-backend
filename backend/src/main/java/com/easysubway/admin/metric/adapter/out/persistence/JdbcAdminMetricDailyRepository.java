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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("prod | staging | release | prod-like")
public class JdbcAdminMetricDailyRepository implements AdminMetricDailyRepository {

	private final JdbcTemplate jdbcTemplate;
	private final DatabaseDialect databaseDialect;

	@Autowired
	JdbcAdminMetricDailyRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcAdminMetricDailyRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.databaseDialect = detectDatabaseDialect(jdbcTemplate);
	}

	@Override
	public void save(AdminMetricDaily metric) {
		// 원자적 upsert(#2273): 한 문장으로 처리해 (지표 키, 날짜) 동시 저장 경합에도 PK 충돌 없이 한 행만
		// 남기고, 재실행은 값을 덮어써 멱등하다. ON CONFLICT(PostgreSQL)/MERGE KEY(H2)는 방언별 표기만
		// 다를 뿐 같은 원자 동작을 보장한다. H2는 ON CONFLICT 문법을 파싱하지 못해 방언으로 분기한다.
		if (databaseDialect == DatabaseDialect.POSTGRESQL) {
			upsertWithOnConflict(metric);
			return;
		}
		upsertWithMergeKey(metric);
	}

	private void upsertWithOnConflict(AdminMetricDaily metric) {
		jdbcTemplate.update(
			"""
				INSERT INTO admin_metric_daily (metric_key, metric_date, metric_value, dimensions)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (metric_key, metric_date) DO UPDATE
				SET metric_value = EXCLUDED.metric_value,
					dimensions = EXCLUDED.dimensions
				""",
			metric.metricKey(),
			metric.metricDate(),
			metric.value(),
			metric.dimensions()
		);
	}

	private void upsertWithMergeKey(AdminMetricDaily metric) {
		jdbcTemplate.update(
			"""
				MERGE INTO admin_metric_daily (metric_key, metric_date, metric_value, dimensions)
				KEY (metric_key, metric_date)
				VALUES (?, ?, ?, ?)
				""",
			metric.metricKey(),
			metric.metricDate(),
			metric.value(),
			metric.dimensions()
		);
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

	// 원자적 upsert 문법이 방언별로 달라(ON CONFLICT vs MERGE KEY) 저장 시 분기하기 위해 감지한다.
	private static DatabaseDialect detectDatabaseDialect(JdbcTemplate jdbcTemplate) {
		DatabaseDialect dialect = jdbcTemplate.execute((ConnectionCallback<DatabaseDialect>) connection -> {
			String productName = connection.getMetaData().getDatabaseProductName();
			return "H2".equalsIgnoreCase(productName) ? DatabaseDialect.H2 : DatabaseDialect.POSTGRESQL;
		});
		return dialect == null ? DatabaseDialect.POSTGRESQL : dialect;
	}

	private enum DatabaseDialect {
		POSTGRESQL,
		H2
	}
}
