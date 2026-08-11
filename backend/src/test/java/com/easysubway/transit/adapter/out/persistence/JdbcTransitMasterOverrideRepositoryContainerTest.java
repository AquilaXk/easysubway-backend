package com.easysubway.transit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.SimplifiedStationLayout;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("PostgreSQL 도시철도 마스터 override 저장소")
class JdbcTransitMasterOverrideRepositoryContainerTest {

	private static final String SCHEMA = "transit_override_container";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@BeforeAll
	static void migrateSchemaOnce() {
		migrate(SCHEMA);
	}

	@BeforeEach
	void resetOverrides() {
		try (var dataSource = dataSource(SCHEMA)) {
			var jdbcTemplate = new JdbcTemplate(dataSource);
			jdbcTemplate.execute("DROP TRIGGER IF EXISTS pause_transit_master_override_insert ON transit_master_overrides");
			jdbcTemplate.execute("DROP FUNCTION IF EXISTS pause_transit_master_override_insert()");
			jdbcTemplate.execute("TRUNCATE TABLE transit_master_override_audits");
			jdbcTemplate.execute("TRUNCATE TABLE transit_master_overrides");
			jdbcTemplate.execute("TRUNCATE TABLE transit_master_override_locks");
		}
	}

	@Test
	@DisplayName("동일 target 최초 저장은 PostgreSQL transaction 안에서 직렬화되고 정확한 pre-image audit을 남긴다")
	void concurrentFirstWritesRemainUsableAndRecordExactAuditChain() throws Exception {
		try (var dataSource = dataSource(SCHEMA)) {
			var jdbcTemplate = new JdbcTemplate(dataSource);
			installInsertDelay(jdbcTemplate);
			var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());
			var ready = new CountDownLatch(2);
			var start = new CountDownLatch(1);
			var executor = Executors.newFixedThreadPool(2);

			try {
				var first = executor.submit(() -> {
					saveAfterStart(
						transaction, jdbcTemplate, repository, ready, start,
						AccessibilityFacilityStatus.BROKEN, "first-admin", LocalDate.of(2026, 6, 27)
					);
					return null;
				});
				var second = executor.submit(() -> {
					saveAfterStart(
						transaction, jdbcTemplate, repository, ready, start,
						AccessibilityFacilityStatus.CLOSED, "second-admin", LocalDate.of(2026, 6, 28)
					);
					return null;
				});

				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
				start.countDown();
				first.get(10, TimeUnit.SECONDS);
				second.get(10, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}

			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_overrides", Integer.class))
				.isEqualTo(1);
			List<String> previousPayloads = jdbcTemplate.queryForList("""
				SELECT previous_payload_json
				FROM transit_master_override_audits
				WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT'
				ORDER BY audit_id
				""", String.class, JdbcTransitMasterOverrideRepository.FACILITY, facilityId());
			List<String> payloads = jdbcTemplate.queryForList("""
				SELECT payload_json
				FROM transit_master_override_audits
				WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT'
				ORDER BY audit_id
				""", String.class, JdbcTransitMasterOverrideRepository.FACILITY, facilityId());
			assertThat(previousPayloads).containsExactly(null, payloads.getFirst());
			assertThat(jdbcTemplate.queryForList("""
				SELECT updated_by
				FROM transit_master_override_audits
				WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT'
				ORDER BY audit_id
				""", String.class, JdbcTransitMasterOverrideRepository.FACILITY, facilityId()))
				.containsExactlyInAnyOrder("first-admin", "second-admin");
		}
	}

	@Test
	@DisplayName("시설 status 저장은 target lock 뒤의 full save payload를 변환하고 정확한 pre-image audit을 남긴다")
	void facilityStatusUsesFullSavePayloadReadAfterTargetLock() throws Exception {
		try (var dataSource = dataSource(SCHEMA)) {
			var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());
			repository.saveAccessibilityFacility(facility(AccessibilityFacilityStatus.NORMAL, LocalDate.of(2026, 6, 27), "seed"), "seed");
			var changed = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			var executor = Executors.newFixedThreadPool(2);

			try {
				Future<?> fullSave = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
					repository.saveAccessibilityFacility(facility(AccessibilityFacilityStatus.NORMAL, LocalDate.of(2026, 6, 28), "full-save"), "full");
					changed.countDown();
					await(release);
				}));
				assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
				Future<?> statusSave = executor.submit(() -> transaction.executeWithoutResult(ignored ->
					repository.saveFacilityStatus(facilityId(), AccessibilityFacilityStatus.CLOSED, LocalDate.of(2026, 6, 29), "status")
				));
				assertWaiting(statusSave);
				release.countDown();
				fullSave.get(5, TimeUnit.SECONDS);
				statusSave.get(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}

			assertThat(repository.loadAccessibilityFacility(facilityId())).hasValueSatisfying(facility -> {
				assertThat(facility.name()).isEqualTo("full-save");
				assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.CLOSED);
			});
			assertThat(new JdbcTemplate(dataSource).queryForList("""
				SELECT previous_payload_json
				FROM transit_master_override_audits
				WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT'
				ORDER BY audit_id
				""", String.class, JdbcTransitMasterOverrideRepository.FACILITY, facilityId()).getLast())
				.contains("\"name\":\"full-save\"");
		}
	}

	@Test
	@DisplayName("시설 status 저장은 target lock 뒤의 rollback payload를 변환한다")
	void facilityStatusUsesRollbackPayloadReadAfterTargetLock() throws Exception {
		try (var dataSource = dataSource(SCHEMA)) {
			var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());
			repository.saveAccessibilityFacility(facility(AccessibilityFacilityStatus.NORMAL, LocalDate.of(2026, 6, 27), "first"), "first");
			repository.saveAccessibilityFacility(facility(AccessibilityFacilityStatus.NORMAL, LocalDate.of(2026, 6, 28), "second"), "second");
			var changed = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			var executor = Executors.newFixedThreadPool(2);

			try {
				Future<?> rollback = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
					repository.rollbackMasterDataOverride(JdbcTransitMasterOverrideRepository.FACILITY, facilityId(), "rollback");
					changed.countDown();
					await(release);
				}));
				assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
				Future<?> statusSave = executor.submit(() -> transaction.executeWithoutResult(ignored ->
					repository.saveFacilityStatus(facilityId(), AccessibilityFacilityStatus.CLOSED, LocalDate.of(2026, 6, 29), "status")
				));
				assertWaiting(statusSave);
				release.countDown();
				rollback.get(5, TimeUnit.SECONDS);
				statusSave.get(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}

			assertThat(repository.loadAccessibilityFacility(facilityId())).hasValueSatisfying(facility -> {
				assertThat(facility.name()).isEqualTo("first");
				assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.CLOSED);
			});
		}
	}

	@Test
	@DisplayName("layout status 저장은 target lock 뒤의 version을 하나만 증가시키고 pre-image audit을 남긴다")
	void layoutStatusSerializesVersionAfterTargetLock() throws Exception {
		try (var dataSource = dataSource(SCHEMA)) {
			var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());
			SimplifiedStationLayout base = repository.loadSimplifiedStationLayouts().getFirst();
			var changed = new CountDownLatch(1);
			var release = new CountDownLatch(1);
			var executor = Executors.newFixedThreadPool(2);

			try {
				Future<?> fullSave = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
					repository.saveSimplifiedStationLayoutStatus(
						base.id(), SimplifiedStationLayoutStatus.READY_FOR_REVIEW, "full", LocalDate.of(2026, 6, 28)
					);
					changed.countDown();
					await(release);
				}));
				assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
				Future<?> statusSave = executor.submit(() -> transaction.executeWithoutResult(ignored ->
					repository.saveSimplifiedStationLayoutStatus(base.id(), SimplifiedStationLayoutStatus.PUBLISHED, "status", LocalDate.of(2026, 6, 29))
				));
				assertWaiting(statusSave);
				release.countDown();
				fullSave.get(5, TimeUnit.SECONDS);
				statusSave.get(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}

			assertThat(repository.loadSimplifiedStationLayouts())
				.anySatisfy(layout -> {
					assertThat(layout.id()).isEqualTo(base.id());
					assertThat(layout.version()).isEqualTo(3);
					assertThat(layout.status()).isEqualTo(SimplifiedStationLayoutStatus.PUBLISHED);
				});
			var jdbcTemplate = new JdbcTemplate(dataSource);
			List<String> payloads = jdbcTemplate.queryForList("""
				SELECT payload_json FROM transit_master_override_audits
				WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT'
				ORDER BY audit_id
				""", String.class, JdbcTransitMasterOverrideRepository.LAYOUT, base.id());
			List<String> previousPayloads = jdbcTemplate.queryForList("""
				SELECT previous_payload_json FROM transit_master_override_audits
				WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT'
				ORDER BY audit_id
				""", String.class, JdbcTransitMasterOverrideRepository.LAYOUT, base.id());
			assertThat(payloads).hasSize(2);
			assertThat(previousPayloads).containsExactly(null, payloads.getFirst());
			assertThat(previousPayloads.getLast())
				.contains("\"version\":2", "\"status\":\"READY_FOR_REVIEW\"");
		}
	}

	private void saveAfterStart(
		TransactionTemplate transaction,
		JdbcTemplate jdbcTemplate,
		JdbcTransitMasterOverrideRepository repository,
		CountDownLatch ready,
		CountDownLatch start,
		AccessibilityFacilityStatus status,
		String updatedBy,
		LocalDate updatedAt
	) throws InterruptedException {
		ready.countDown();
		assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
		transaction.executeWithoutResult(ignored -> {
			repository.saveAccessibilityFacility(facility(status, updatedAt), updatedBy);
			assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
		});
	}

	private void assertWaiting(Future<?> operation) {
		assertThatThrownBy(() -> operation.get(250, TimeUnit.MILLISECONDS))
			.isInstanceOf(TimeoutException.class);
	}

	private void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("동시성 회귀 test가 중단되었습니다.", exception);
		}
	}

	private void installInsertDelay(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute("""
			CREATE FUNCTION pause_transit_master_override_insert()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				PERFORM pg_sleep(0.5);
				RETURN NEW;
			END;
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TRIGGER pause_transit_master_override_insert
			BEFORE INSERT ON transit_master_overrides
			FOR EACH ROW EXECUTE FUNCTION pause_transit_master_override_insert()
			""");
	}

	private static void migrate(String schema) {
		var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/postgresql")
			.schemas(schema)
			.createSchemas(true)
			.load()
			.migrate();
	}

	private HikariDataSource dataSource(String schema) {
		var dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		dataSource.setSchema(schema);
		return dataSource;
	}

	private AccessibilityFacility facility(AccessibilityFacilityStatus status, LocalDate updatedAt) {
		return facility(status, updatedAt, "승강장 엘리베이터");
	}

	private AccessibilityFacility facility(AccessibilityFacilityStatus status, LocalDate updatedAt, String name) {
		return new AccessibilityFacility(
			facilityId(),
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.ELEVATOR,
			name,
			"B1",
			"1F",
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221"),
			"동시성 회귀 검증",
			status,
			DataConfidenceLevel.HIGH,
			DataSourceType.ADMIN_VERIFIED,
			updatedAt
		);
	}


	private String facilityId() {
		return "facility-concurrent-first-write";
	}

	private ObjectMapper objectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}
}
