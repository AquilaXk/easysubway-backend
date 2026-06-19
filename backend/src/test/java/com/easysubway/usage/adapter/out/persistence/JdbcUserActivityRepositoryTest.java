package com.easysubway.usage.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.usage.domain.InvalidUserActivityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 사용자 활동 저장소")
class JdbcUserActivityRepositoryTest {

	private JdbcTemplate jdbcTemplate;
	private JdbcUserActivityRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:user-activity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS api_traffic_events");
		jdbcTemplate.execute("DROP TABLE IF EXISTS user_activity_events");
		jdbcTemplate.execute("""
			CREATE TABLE user_activity_events (
				user_id VARCHAR(120) NOT NULL,
				occurred_at TIMESTAMP NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE api_traffic_events (
				status_code INTEGER NOT NULL,
				duration_millis BIGINT NOT NULL,
				occurred_at TIMESTAMP NOT NULL
			)
			""");
		repository = new JdbcUserActivityRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("최근 기간의 사용자 활동과 API 트래픽을 저장 후 집계한다")
	void summarizeUserActivityCountsPersistedActivityAndTraffic() {
		repository.recordUserActivity("anonymous-user-1", LocalDateTime.of(2026, 6, 17, 9, 0));
		repository.recordUserActivity("anonymous-user-1", LocalDateTime.of(2026, 6, 17, 10, 0));
		repository.recordUserActivity("anonymous-user-2", LocalDateTime.of(2026, 6, 16, 11, 0));
		repository.recordUserActivity("anonymous-user-old", LocalDateTime.of(2026, 6, 15, 11, 0));
		repository.recordApiTraffic(200, 120, LocalDateTime.of(2026, 6, 17, 9, 0));
		repository.recordApiTraffic(404, 280, LocalDateTime.of(2026, 6, 17, 9, 5));
		repository.recordApiTraffic(500, 900, LocalDateTime.of(2026, 6, 16, 11, 0));
		repository.recordApiTraffic(200, 50, LocalDateTime.of(2026, 6, 15, 11, 0));

		var summary = repository.summarizeUserActivity(LocalDate.of(2026, 6, 17), 2);

		assertThat(summary.totalActiveUsers()).isEqualTo(2);
		assertThat(summary.totalApiRequests()).isEqualTo(3);
		assertThat(summary.totalApiErrors()).isEqualTo(2);
		assertThat(summary.apiErrorRatePercent()).isEqualTo("66.7%");
		assertThat(summary.averageApiResponseMillis()).isEqualTo(433);
		assertThat(summary.dailyActivities())
			.extracting(row -> row.date() + ":" + row.activeUserCount() + ":" + row.apiRequestCount() + ":" + row.apiErrorCount() + ":" + row.apiErrorRatePercent() + ":" + row.averageApiResponseMillis())
			.containsExactly("2026-06-17:1:2:1:50.0%:200", "2026-06-16:1:1:1:100.0%:900");
	}

	@Test
	@DisplayName("새 저장소 인스턴스도 기존 사용자 활동 기록을 집계한다")
	void summarizeUserActivityAfterRepositoryRecreation() {
		repository.recordUserActivity("anonymous-user-1", LocalDateTime.of(2026, 6, 17, 9, 0));
		repository.recordApiTraffic(200, 120, LocalDateTime.of(2026, 6, 17, 9, 0));

		var recreatedRepository = new JdbcUserActivityRepository(jdbcTemplate);
		var summary = recreatedRepository.summarizeUserActivity(LocalDate.of(2026, 6, 17), 1);

		assertThat(summary.totalActiveUsers()).isEqualTo(1);
		assertThat(summary.totalApiRequests()).isEqualTo(1);
		assertThat(summary.totalApiErrors()).isZero();
	}

	@Test
	@DisplayName("DB 컬럼 길이를 넘는 사용자 식별자는 활동 기록으로 저장하지 않는다")
	void recordUserActivityRejectsUserIdOverColumnLimit() {
		String tooLongUserId = "a".repeat(121);

		assertThatThrownBy(() -> repository.recordUserActivity(tooLongUserId, LocalDateTime.of(2026, 6, 17, 9, 0)))
			.isInstanceOf(InvalidUserActivityException.class)
			.hasMessage("사용자 활동 식별자는 120자 이하여야 합니다.");
	}
}
