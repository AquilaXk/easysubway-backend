package com.easysubway.usage.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("PostgreSQL 사용자 활동 저장소")
class JdbcUserActivityRepositoryContainerTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Test
	@DisplayName("UTC로 저장된 활동은 한국 날짜로 묶고 한국 자정 경계로 기간을 자른다")
	void summarizeUserActivityGroupsByKoreanDate() {
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		Flyway.configure()
			.configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
			.dataSource(dataSource)
			.locations("classpath:db/migration/postgresql")
			.load()
			.migrate();
		var repository = new JdbcUserActivityRepository(new JdbcTemplate(dataSource));

		var summary = KoreanDateActivityScenario.seedAndSummarize(repository);

		KoreanDateActivityScenario.assertGroupedByKoreanDate(summary);
	}
}
