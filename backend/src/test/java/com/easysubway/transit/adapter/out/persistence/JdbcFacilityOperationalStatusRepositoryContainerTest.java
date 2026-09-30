package com.easysubway.transit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedApplyResult;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import com.easysubway.transit.domain.FacilityStatusSource;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("PostgreSQL 시설 운영 상태 저장소(V76)")
class JdbcFacilityOperationalStatusRepositoryContainerTest {

	private static final String SCHEMA = "facility_status_container";
	private static final String FEED = "SEOUL_METRO_ELEVATOR";
	private static final String EXIT_1 = "smrt-elev:0201:2:1번 출입구";
	private static final Instant T0 = Instant.parse("2026-09-30T01:00:00.123456Z");
	private static final Instant ADMIN_AT = Instant.parse("2026-09-30T01:00:30.654321Z");
	private static final Instant T1 = Instant.parse("2026-09-30T01:01:00Z");

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@BeforeAll
	static void createSchemaOnce() {
		var adminDataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
		);
		new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
		try (var dataSource = dataSource()) {
			new ResourceDatabasePopulator(
				new ClassPathResource("db/migration/postgresql/V76__facility_operational_status.sql")
			).execute(dataSource);
		}
	}

	@BeforeEach
	void truncate() {
		try (var dataSource = dataSource()) {
			var jdbcTemplate = new JdbcTemplate(dataSource);
			jdbcTemplate.execute("TRUNCATE TABLE facility_operational_status");
			jdbcTemplate.execute("TRUNCATE TABLE facility_status_feed_heartbeat");
		}
	}

	@Test
	@DisplayName("TIMESTAMPTZ 마이크로초 왕복, 관리자 확인 우선순위 양방향, 심장박동이 PostgreSQL에서도 같다")
	void appliesPriorityAndHeartbeatOnPostgresql() {
		try (var dataSource = dataSource()) {
			var repository = new JdbcFacilityOperationalStatusRepository(dataSource);

			repository.applyFeedCollection(FEED, List.of(new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")), T0);
			assertThat(repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, ADMIN_AT)).isTrue();
			FeedApplyResult unchanged = repository.applyFeedCollection(FEED, List.of(
				new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")
			), T1);

			assertThat(unchanged).isEqualTo(new FeedApplyResult(0, 1));
			assertThat(repository.loadStatuses()).containsExactly(new FacilityOperationalStatus(
				EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.ADMIN_VERIFIED, "M", ADMIN_AT, ADMIN_AT
			));
			assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(T1);

			FeedApplyResult changed = repository.applyFeedCollection(FEED, List.of(
				new FeedObservation(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, "S")
			), T1.plusSeconds(60));

			assertThat(changed).isEqualTo(new FeedApplyResult(1, 0));
			assertThat(repository.loadStatuses()).containsExactly(new FacilityOperationalStatus(
				EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED, "S",
				T1.plusSeconds(60), T1.plusSeconds(60)
			));
		}
	}

	@Test
	@DisplayName("PostgreSQL 스키마는 정해진 상태·출처만 받고 관측 시각을 비울 수 없다")
	void postgresqlSchemaEnforcesChecks() {
		try (var dataSource = dataSource()) {
			var jdbcTemplate = new JdbcTemplate(dataSource);
			assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO facility_operational_status VALUES ('x', 'BROKEN', 'SEOUL_METRO_FEED', 'S', now(), now())"
			)).isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO facility_operational_status VALUES ('x', 'OPERATING', 'USER_REPORTED', NULL, now(), now())"
			)).isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO facility_operational_status VALUES ('x', 'OPERATING', 'ADMIN_VERIFIED', NULL, NULL, now())"
			)).isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO facility_status_feed_heartbeat VALUES ('SEOUL_METRO_ELEVATOR', NULL)"
			)).isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	private static HikariDataSource dataSource() {
		var dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		dataSource.setSchema(SCHEMA);
		return dataSource;
	}
}
