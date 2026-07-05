package com.easysubway.admin.metric.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.admin.metric.domain.AdminMetricDaily;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 일별 지표 스냅샷 저장소")
class JdbcAdminMetricDailyRepositoryTest {

	private JdbcAdminMetricDailyRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:admin-metric-daily;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS admin_metric_daily");
		jdbcTemplate.execute("""
			CREATE TABLE admin_metric_daily (
				metric_key VARCHAR(80) NOT NULL,
				metric_date DATE NOT NULL,
				metric_value DOUBLE PRECISION NOT NULL,
				dimensions VARCHAR(2000),
				PRIMARY KEY (metric_key, metric_date)
			)
			""");
		repository = new JdbcAdminMetricDailyRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("저장한 스냅샷을 키·날짜로 다시 읽는다")
	void savesAndFinds() {
		repository.save(AdminMetricDaily.scalar("reports.submitted", LocalDate.of(2026, 7, 5), 12));

		Optional<AdminMetricDaily> found = repository.find("reports.submitted", LocalDate.of(2026, 7, 5));

		assertThat(found).hasValueSatisfying(metric -> {
			assertThat(metric.value()).isEqualTo(12);
			assertThat(metric.dimensions()).isNull();
		});
	}

	@Test
	@DisplayName("같은 키·날짜 재저장은 값을 덮어쓰고 행을 늘리지 않는다(멱등 upsert)")
	void reSaveIsIdempotentUpsert() {
		LocalDate date = LocalDate.of(2026, 7, 5);
		repository.save(AdminMetricDaily.scalar("reports.submitted", date, 12));
		repository.save(AdminMetricDaily.scalar("reports.submitted", date, 20));

		assertThat(repository.find("reports.submitted", date))
			.hasValueSatisfying(metric -> assertThat(metric.value()).isEqualTo(20));
		assertThat(repository.findByKeysAndDateRange(List.of("reports.submitted"), date, date)).hasSize(1);
	}

	@Test
	@DisplayName("키·날짜 범위로 조회하고 범위 밖·다른 키는 제외한다")
	void findsByKeysAndDateRange() {
		repository.save(AdminMetricDaily.scalar("reports.submitted", LocalDate.of(2026, 7, 3), 3));
		repository.save(AdminMetricDaily.scalar("reports.submitted", LocalDate.of(2026, 7, 4), 4));
		repository.save(AdminMetricDaily.scalar("reports.submitted", LocalDate.of(2026, 7, 6), 6));
		repository.save(AdminMetricDaily.scalar("push.failed", LocalDate.of(2026, 7, 4), 1));

		List<AdminMetricDaily> range = repository.findByKeysAndDateRange(
			List.of("reports.submitted"), LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 5));

		assertThat(range)
			.extracting(AdminMetricDaily::metricDate)
			.containsExactly(LocalDate.of(2026, 7, 4));
	}
}
