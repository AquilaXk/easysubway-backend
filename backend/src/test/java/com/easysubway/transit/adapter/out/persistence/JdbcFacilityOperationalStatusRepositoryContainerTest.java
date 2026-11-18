package com.easysubway.transit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.AdminVerifiedResult;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedApplyResult;
import com.easysubway.transit.application.port.out.FacilityOperationalStatusStore.FeedObservation;
import com.easysubway.transit.domain.FacilityOperationalState;
import com.easysubway.transit.domain.FacilityOperationalStatus;
import com.easysubway.transit.domain.FacilityStatusSource;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
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
	private static final Instant FEED_BEFORE_ADMIN_AT = Instant.parse("2026-09-30T01:00:10Z");
	private static final Duration WAIT = Duration.ofSeconds(10);

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
	@DisplayName("TIMESTAMPTZ 마이크로초 왕복, 관리자 확인 우선순위 양방향, 심장박동, 원천에서 빠진 행 제거가 PostgreSQL에서도 같다")
	void appliesPriorityAndHeartbeatOnPostgresql() {
		try (var dataSource = dataSource()) {
			var repository = new JdbcFacilityOperationalStatusRepository(dataSource);

			repository.applyFeedCollection(FEED, List.of(new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")), T0);
			assertThat(repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, ADMIN_AT).recorded()).isTrue();
			FeedApplyResult unchanged = repository.applyFeedCollection(FEED, List.of(
				new FeedObservation(EXIT_1, FacilityOperationalState.OPERATING, "M")
			), T1);

			assertThat(unchanged).isEqualTo(new FeedApplyResult(0, 1, 0, 0));
			assertThat(repository.loadStatuses()).containsExactly(new FacilityOperationalStatus(
				EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.ADMIN_VERIFIED, "M", ADMIN_AT, ADMIN_AT
			));
			assertThat(repository.lastSuccessfulCollectionAt(FEED)).contains(T1);

			FeedApplyResult changed = repository.applyFeedCollection(FEED, List.of(
				new FeedObservation(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, "S")
			), T1.plusSeconds(60));

			assertThat(changed).isEqualTo(new FeedApplyResult(1, 0, 0, 0));
			assertThat(repository.loadStatuses()).containsExactly(new FacilityOperationalStatus(
				EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, FacilityStatusSource.SEOUL_METRO_FEED, "S",
				T1.plusSeconds(60), T1.plusSeconds(60)
			));

			FeedApplyResult absent = repository.applyFeedCollection(FEED, List.of(), T1.plusSeconds(120));

			assertThat(absent).isEqualTo(new FeedApplyResult(0, 0, 1, 0));
			assertThat(repository.loadStatuses()).isEmpty();
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

	@Test
	@DisplayName("행이 없는 시설의 첫 기록이 바깥 트랜잭션 안에서 겹치면 늦은 쪽은 앞선 커밋을 기다린 뒤 recorded=false를 받고, 같은 트랜잭션을 계속 쓸 수 있으며 앞선 상태만 남는다")
	void concurrentFirstRecordsKeepTheWinnerAndLeaveTheLoserTransactionUsable() throws Exception {
		try (var dataSource = dataSource()) {
			var transactionManager = new DataSourceTransactionManager(dataSource);
			var repository = new JdbcFacilityOperationalStatusRepository(dataSource, transactionManager);
			var outerTransaction = new TransactionTemplate(transactionManager);
			var jdbcTemplate = new JdbcTemplate(dataSource);
			var winnerInserted = new CountDownLatch(1);
			var releaseWinner = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<AdminVerifiedResult> winner = executor.submit(() -> outerTransaction.execute(status -> {
					AdminVerifiedResult result = repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OPERATING, ADMIN_AT);
					winnerInserted.countDown();
					await(releaseWinner);
					return result;
				}));
				assertThat(winnerInserted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

				Future<LoserOutcome> loser = executor.submit(() -> outerTransaction.execute(status -> new LoserOutcome(
					repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OUT_OF_SERVICE, ADMIN_AT),
					jdbcTemplate.queryForObject(
						"SELECT status FROM facility_operational_status WHERE facility_id = ?", String.class, EXIT_1
					)
				)));
				awaitLockWaiter(jdbcTemplate);
				assertThat(loser.isDone()).as("늦은 쪽은 앞선 트랜잭션의 같은 시설 INSERT를 기다린다").isFalse();
				releaseWinner.countDown();

				assertThat(winner.get(WAIT.toSeconds(), TimeUnit.SECONDS))
					.isEqualTo(new AdminVerifiedResult(true, Optional.empty(), Optional.empty()));
				LoserOutcome loserOutcome = loser.get(WAIT.toSeconds(), TimeUnit.SECONDS);
				assertThat(loserOutcome.result()).isEqualTo(new AdminVerifiedResult(false, Optional.empty(), Optional.empty()));
				assertThat(loserOutcome.statusReadInSameTransaction()).isEqualTo(FacilityOperationalState.OPERATING.name());
			} finally {
				releaseWinner.countDown();
				executor.shutdownNow();
			}

			assertThat(repository.loadStatuses()).containsExactly(new FacilityOperationalStatus(
				EXIT_1, FacilityOperationalState.OPERATING, FacilityStatusSource.ADMIN_VERIFIED, null, ADMIN_AT, ADMIN_AT
			));
		}
	}

	@Test
	@DisplayName("원천 갱신이 커밋 전이면 관리자 기록은 그 행 잠금을 기다렸다가, 실제로 덮어쓴 원천 갱신 후 상태·출처를 이전 값으로 돌려준다")
	void adminRecordWaitsForInFlightFeedWriteAndReportsTheOverwrittenRow() throws Exception {
		try (var dataSource = dataSource()) {
			var transactionManager = new DataSourceTransactionManager(dataSource);
			var repository = new JdbcFacilityOperationalStatusRepository(dataSource, transactionManager);
			var outerTransaction = new TransactionTemplate(transactionManager);
			var jdbcTemplate = new JdbcTemplate(dataSource);
			assertThat(repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OPERATING, T0).recorded()).isTrue();
			ExecutorService executor = Executors.newSingleThreadExecutor();
			try (Connection feed = dataSource.getConnection()) {
				feed.setAutoCommit(false);
				try (var update = feed.prepareStatement("""
					UPDATE facility_operational_status
					SET status = 'OUT_OF_SERVICE', source = 'SEOUL_METRO_FEED', source_code = 'S', observed_at = ?, updated_at = ?
					WHERE facility_id = ?
					""")) {
					OffsetDateTime feedAt = OffsetDateTime.ofInstant(FEED_BEFORE_ADMIN_AT, ZoneOffset.UTC);
					update.setObject(1, feedAt);
					update.setObject(2, feedAt);
					update.setString(3, EXIT_1);
					assertThat(update.executeUpdate()).isEqualTo(1);
				}

				Future<AdminVerifiedResult> admin = executor.submit(() -> outerTransaction.execute(status ->
					repository.recordAdminVerified(EXIT_1, FacilityOperationalState.OPERATING, ADMIN_AT)
				));
				awaitLockWaiter(jdbcTemplate);
				assertThat(admin.isDone()).as("관리자 기록은 커밋 전 원천 갱신의 행 잠금을 기다린다").isFalse();
				feed.commit();

				assertThat(admin.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new AdminVerifiedResult(
					true,
					Optional.of(FacilityOperationalState.OUT_OF_SERVICE),
					Optional.of(FacilityStatusSource.SEOUL_METRO_FEED)
				));
			} finally {
				executor.shutdownNow();
			}

			assertThat(repository.loadStatuses()).containsExactly(new FacilityOperationalStatus(
				EXIT_1, FacilityOperationalState.OPERATING, FacilityStatusSource.ADMIN_VERIFIED, "S", ADMIN_AT, ADMIN_AT
			));
		}
	}

	private static void awaitLockWaiter(JdbcTemplate jdbcTemplate) throws InterruptedException {
		Instant deadline = Instant.now().plus(WAIT);
		while (Instant.now().isBefore(deadline)) {
			Integer waiting = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
				Integer.class
			);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("행 잠금을 기다리는 세션이 " + WAIT + " 안에 생기지 않았다");
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
				throw new AssertionError("latch was not released");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private record LoserOutcome(AdminVerifiedResult result, String statusReadInSameTransaction) {
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
