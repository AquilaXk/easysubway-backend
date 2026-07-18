package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easysubway.collection.adapter.out.persistence.JdbcDataCollectionRunRepository;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseDeliveryRepository;
import com.easysubway.datapack.domain.DatapackReleaseDelivery;
import com.easysubway.route.adapter.out.persistence.JdbcRouteV2AccessStore;
import com.easysubway.route.application.port.out.RouteV2AccessStore.RouteV2Session;
import com.easysubway.route.application.port.out.RouteV2AccessStore.SessionStatus;
import com.easysubway.train.adapter.out.persistence.JdbcTrainSearchCache;
import com.easysubway.train.application.TrainSearchCache.CachedLeg;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("운영 스키마 Flyway migration")
class DatabaseMigrationContainerTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	@Test
	@DisplayName("깨끗한 PostgreSQL DB는 versioned migration만으로 핵심 운영 테이블과 제약을 만든다")
	void flywayMigratesCleanPostgresqlSchema() {
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		var flyway = flyway(dataSource, "classpath:db/migration/postgresql", null).load();

		var result = flyway.migrate();

		assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		assertThat(tableNames(jdbcTemplate))
			.contains(
				"flyway_schema_history",
				"batch_job_instance",
				"facility_reports",
				"push_notification_outbox",
				"data_source_snapshots",
				"datapack_source_lineage_locks",
				"datapack_normalization_runs",
				"datapack_normalized_outputs",
				"datapack_candidates",
				"datapack_candidate_inputs",
				"datapack_release_evidence_bundles",
				"datapack_release_deliveries",
				"datapack_release_channels",
				"datapack_release_channel_events",
				"external_alias_approvals",
				"source_quarantine_records",
				"source_quarantine_resolutions",
				"facility_evidence",
				"manual_overrides",
				"route_edge_evidence",
				"route_v2_nonce_replays",
				"route_v2_sessions",
				"route_v2_states",
				"transit_master_overrides",
				"transit_master_override_audits",
				"timetable_snapshot_lock",
				"timetable_snapshot_history",
				"timetable_snapshot_active",
				"train_catalog_cache",
				"train_search_cache",
				"train_provider_call_quota_state"
			);
		assertThat(successfulMigrationVersions(jdbcTemplate)).contains("1", "14", "16", "17", "18", "19", "20", "21", "22", "23", "25", "26", "48", "51", "52", "53", "54", "55", "56", "57", "59", "60", "61", "65");
		assertThat(jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM pg_index i
			JOIN pg_class c ON c.oid = i.indexrelid
			JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE c.relname IN ('uq_data_source_snapshots_previous_child', 'uq_data_source_snapshots_source_root')
				AND n.nspname = 'public'
				AND i.indisvalid = TRUE
				AND i.indisready = TRUE
			""", Integer.class)).isEqualTo(2);
		assertAdPlacementsSeeded(jdbcTemplate);
		assertThat(foreignKeyNames(jdbcTemplate))
			.contains(
				"fk_facility_report_review_audits_report",
				"fk_data_source_snapshots_previous",
				"fk_data_source_snapshots_previous_source",
				"fk_datapack_normalization_runs_snapshot_source",
				"fk_datapack_normalized_outputs_run",
				"fk_external_alias_approvals_snapshot_source",
				"fk_external_alias_approvals_superseded",
				"fk_source_quarantine_records_snapshot_source",
				"fk_source_quarantine_resolutions_record",
				"fk_facility_evidence_manual_override",
				"fk_facility_evidence_snapshot_source",
				"fk_manual_overrides_superseded",
				"fk_route_edge_evidence_snapshot_source",
				"fk_datapack_candidate_inputs_candidate",
				"fk_datapack_release_evidence_candidate",
				"fk_datapack_release_channels_candidate",
				"fk_datapack_release_channels_previous_candidate",
				"fk_datapack_release_channel_events_channel",
				"fk_datapack_release_channel_events_next_candidate",
				"fk_timetable_snapshot_active_history"
			);
		assertThat(checkConstraintNames(jdbcTemplate))
			.contains(
				"chk_datapack_normalization_runs_counts",
				"chk_datapack_normalization_runs_finished_state",
				"chk_datapack_normalized_outputs_kind",
				"chk_external_alias_approvals_confidence",
				"chk_external_alias_approvals_approved_state",
				"chk_source_quarantine_records_resolution_state",
				"chk_source_quarantine_resolutions_status",
				"chk_data_source_snapshots_credential_redacted",
				"chk_data_source_snapshots_raw_object_uri",
				"chk_data_source_snapshots_raw_retention",
				"chk_data_source_snapshots_coverage_count",
				"chk_data_source_snapshots_governance_pair",
				"chk_data_source_snapshots_previous_not_self",
				"chk_facility_evidence_strict_route",
				"chk_manual_overrides_approval_state",
				"chk_manual_overrides_effective_window",
				"chk_manual_overrides_route_safety",
				"chk_route_edge_evidence_strict_route",
				"chk_route_v2_sessions_request_count",
				"chk_route_v2_sessions_scope",
				"chk_route_v2_states_expiry",
				"chk_route_v2_states_scope",
				"chk_datapack_candidates_gate_status",
				"chk_datapack_candidates_approval_status",
				"chk_datapack_release_evidence_status",
				"chk_datapack_release_delivery_state",
				"chk_datapack_release_delivery_sequence",
				"chk_datapack_release_channels_operation",
				"chk_datapack_release_channels_rollback_target",
				"chk_datapack_release_channel_events_operation",
				"chk_timetable_snapshot_lock_singleton",
				"chk_timetable_snapshot_counts",
				"chk_timetable_snapshot_active_singleton",
				"chk_train_catalog_cache_hash",
				"chk_train_catalog_cache_expiry",
				"chk_train_search_cache_payload",
				"chk_train_search_cache_lease",
				"chk_train_provider_call_quota_counts"
			);
		assertNormalizationRunGuards(jdbcTemplate);
		assertSnapshotSourceForeignKeysRejectMismatch(jdbcTemplate);
		assertSnapshotRawEvidencePolicyGuards(jdbcTemplate);
		assertSnapshotGovernanceGuards(jdbcTemplate);
		assertPostgresqlSnapshotLineageIsAppendOnly(jdbcTemplate);
		assertPostgresqlSnapshotRawEvidenceConstraintsAreStaged(jdbcTemplate);
		assertFacilityEvidenceStrictRouteGuards(jdbcTemplate);
		assertManualOverrideProductionGuards(jdbcTemplate);
		assertRouteEdgeEvidenceStrictRouteGuards(jdbcTemplate);
		assertDatapackPermissionMatrix(jdbcTemplate);
		assertRouteServiceIdentityHashGuards(jdbcTemplate);
		assertRouteServiceSourceIssueGuards(jdbcTemplate);
		assertRouteV2AllowlistSchema(jdbcTemplate);
	}

	@Test
	@DisplayName("PostgreSQL transaction 안의 동일 callback replay는 오류 없이 한 row로 수렴한다")
	void postgresqlCallbackReplayIsIdempotentInsideTransaction() {
		String schema = "datapack_callback_replay_" + System.nanoTime();
		var migrationDataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		migrate(migrationDataSource, "classpath:db/migration/postgresql", schema);
		try (var dataSource = new HikariDataSource()) {
			dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
			dataSource.setUsername(POSTGRES.getUsername());
			dataSource.setPassword(POSTGRES.getPassword());
			dataSource.setSchema(schema);
			var jdbcTemplate = new JdbcTemplate(dataSource);
			var repository = new JdbcDatapackReleaseDeliveryRepository(jdbcTemplate);
			var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
			var now = java.time.LocalDateTime.parse("2026-07-16T00:00:00");
			var delivery = DatapackReleaseDelivery.pending(
				"request-2057", 42, "a".repeat(64), "production", "candidate-2057",
				"b".repeat(64), "c".repeat(64), now);

			transaction.executeWithoutResult(ignored -> {
				repository.upsertSameDelivery(delivery);
				repository.upsertSameDelivery(delivery);
				assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM datapack_release_deliveries", Integer.class)).isEqualTo(1);
			});
		}
	}

	@Test
	@DisplayName("PostgreSQL 기차검색 cache는 lease 경쟁과 KST quota window를 원자적으로 조정한다")
	void postgresqlTrainSearchCacheCoordinatesLeaseAndQuota() throws Exception {
		String schema = "train_search_cache_" + System.nanoTime();
		var migrationDataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		migrate(migrationDataSource, "classpath:db/migration/postgresql", schema);
		try (var dataSource = new HikariDataSource()) {
			dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
			dataSource.setUsername(POSTGRES.getUsername());
			dataSource.setPassword(POSTGRES.getPassword());
			dataSource.setSchema(schema);
			var target = new JdbcTrainSearchCache(dataSource);
			var factory = new ProxyFactory(target);
			factory.setProxyTargetClass(true);
			factory.addAdvice(new TransactionInterceptor(
				new DataSourceTransactionManager(dataSource),
				new AnnotationTransactionAttributeSource()
			));
			var repository = (JdbcTrainSearchCache) factory.getProxy();
			var jdbcTemplate = new JdbcTemplate(dataSource);

			int callers = 8;
			var ready = new CountDownLatch(callers);
			var start = new CountDownLatch(1);
			var acquired = new AtomicInteger();
			var failed = new AtomicInteger();
			var executor = Executors.newFixedThreadPool(callers);
			try {
				for (int index = 0; index < callers; index++) {
					String owner = "owner-" + index;
					executor.submit(() -> {
						ready.countDown();
						start.await();
						try {
							if (repository.tryAcquireLease("shared", owner, Instant.EPOCH, Duration.ofSeconds(15))) {
								acquired.incrementAndGet();
							}
						} catch (RuntimeException exception) {
							failed.incrementAndGet();
						}
						return null;
					});
				}
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
				start.countDown();
				executor.shutdown();
				assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
				assertThat(failed).hasValue(0);
				assertThat(acquired).hasValue(1);
			} finally {
				executor.shutdownNow();
			}

			ZoneId korea = ZoneId.of("Asia/Seoul");
			assertThat(repository.tryAcquireProviderCall("tago-train", korea, 1, 1)).isTrue();
			assertThat(repository.tryAcquireProviderCall("tago-train", korea, 1, 1)).isFalse();
			jdbcTemplate.update("""
				UPDATE train_provider_call_quota_state
				SET minute_window = minute_window - 1, minute_calls = 99,
					day_window = day_window - 1, daily_calls = 99
				WHERE provider_id = 'tago-train'
				""");
			assertThat(repository.tryAcquireProviderCall("tago-train", korea, 1, 1)).isTrue();
			assertThat(jdbcTemplate.queryForMap("""
				SELECT minute_calls, daily_calls FROM train_provider_call_quota_state
				WHERE provider_id = 'tago-train'
				"""))
				.containsEntry("minute_calls", 1)
				.containsEntry("daily_calls", 1);

			Instant observedAt = Instant.parse("2026-07-19T00:00:00Z");
			var leg = new CachedLeg(
				"owned", "{}", "[]", "a".repeat(64), observedAt, observedAt.plusSeconds(300));
			assertThat(repository.tryAcquireLease("owned", "owner-a", observedAt, Duration.ofSeconds(15))).isTrue();
			assertThat(repository.storeLegAndRelease("owned", "owner-b", leg)).isFalse();
			assertThat(repository.storeLegAndRelease("owned", "owner-a", leg)).isTrue();
			assertThat(repository.freshLeg("owned", observedAt.plusSeconds(1))).contains(leg);
			assertThat(repository.tryAcquireLease("owned", "owner-c", observedAt, Duration.ofSeconds(15))).isFalse();
		}
	}

	@Test
	@DisplayName("PostgreSQL V64는 재시도 index artifact를 교체하고 RUNNING source를 고유하게 만든다")
	void postgresqlV64ReplacesRetryArtifactAndGuardsRunningSource() {
		String schema = "batch_run_v64_retry_" + System.nanoTime();
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		flyway(dataSource, "classpath:db/migration/postgresql", schema)
			.target(MigrationVersion.fromVersion("63"))
			.load()
			.migrate();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("CREATE INDEX ux_data_collection_runs_running_source ON "
			+ schema + ".data_collection_runs (source)");

		migrate(dataSource, "classpath:db/migration/postgresql", schema);

		assertThat(jdbcTemplate.queryForObject("""
			SELECT i.indisunique AND i.indisvalid AND i.indisready
			FROM pg_index i
			JOIN pg_class c ON c.oid = i.indexrelid
			JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = ?
				AND c.relname = 'ux_data_collection_runs_running_source'
			""", Boolean.class, schema)).isTrue();
		insertLegacyRunningRun(jdbcTemplate, schema, "legacy-running-a");
		assertThatThrownBy(() -> insertLegacyRunningRun(
			jdbcTemplate,
			schema,
			"legacy-running-b"
		)).isInstanceOf(DataAccessException.class);

		try (var scopedDataSource = new HikariDataSource()) {
			scopedDataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
			scopedDataSource.setUsername(POSTGRES.getUsername());
			scopedDataSource.setPassword(POSTGRES.getPassword());
			scopedDataSource.setSchema(schema);
			var scopedJdbc = new JdbcTemplate(scopedDataSource);
			var repository = new JdbcDataCollectionRunRepository(scopedDataSource);
			LocalDateTime staleBefore = LocalDateTime.now().plusMinutes(1);
			assertThat(repository.failOrphanedRunningRun(
				DataCollectionSource.TRANSIT_MASTER,
				staleBefore,
				LocalDateTime.now(),
				"배치 실행 소유권이 만료되어 고아 실행으로 정리되었습니다.",
				"이전 실행이 비정상 종료되었습니다. 새 실행 결과를 확인하세요."
			)).isTrue();

			insertLegacyRunningRun(scopedJdbc, null, "legacy-running-live");
			scopedJdbc.update("""
				INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
				VALUES (1, 0, 'transitMasterCollectionJob', 'live-job')
				""");
			scopedJdbc.update("""
				INSERT INTO BATCH_JOB_EXECUTION (
					JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME,
					START_TIME, STATUS, LAST_UPDATED
				) VALUES (1, 0, 1, ?, ?, 'STARTED', ?)
				""", LocalDateTime.now(), LocalDateTime.now(), staleBefore.plusMinutes(1));
			scopedJdbc.update("""
				INSERT INTO BATCH_JOB_EXECUTION_PARAMS (
					JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING
				) VALUES (1, 'runId', 'java.lang.String', 'legacy-running-live', 'Y')
				""");

			assertThat(repository.failOrphanedRunningRun(
				DataCollectionSource.TRANSIT_MASTER,
				staleBefore,
				LocalDateTime.now(),
				"배치 실행 소유권이 만료되어 고아 실행으로 정리되었습니다.",
				"이전 실행이 비정상 종료되었습니다. 새 실행 결과를 확인하세요."
			)).isFalse();
		}
	}

	@Test
	@DisplayName("PostgreSQL도 100개 동시 요청에서 session 전체 50회만 원자적으로 소비한다")
	void postgresqlConsumesRouteV2SessionAtMostFiftyTimesUnderConcurrency() throws Exception {
		String schema = "route_v2_concurrency_" + System.nanoTime();
		var migrationDataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		migrate(migrationDataSource, "classpath:db/migration/postgresql", schema);
		try (var dataSource = new HikariDataSource()) {
			dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
			dataSource.setUsername(POSTGRES.getUsername());
			dataSource.setPassword(POSTGRES.getPassword());
			dataSource.setSchema(schema);
			dataSource.setMaximumPoolSize(20);
			var store = new JdbcRouteV2AccessStore(dataSource, 50);
			Instant now = Instant.parse("2026-07-16T09:00:00Z");
			String tokenHash = "e".repeat(64);
			store.saveSession(new RouteV2Session(tokenHash, "route:v2:itx", now, now.plusSeconds(600), 0));
			var ready = new CountDownLatch(100);
			var start = new CountDownLatch(1);

			try (var executor = Executors.newFixedThreadPool(100)) {
				var attempts = java.util.stream.IntStream.range(0, 100)
					.mapToObj(ignored -> executor.submit(() -> {
						ready.countDown();
						start.await();
						return store.consumeSession(tokenHash, now.plusSeconds(1)).status();
					}))
					.toList();
				boolean allReady = ready.await(10, TimeUnit.SECONDS);
				start.countDown();
				assertThat(allReady).isTrue();
				var statuses = attempts.stream().map(future -> {
					try {
						return future.get(10, TimeUnit.SECONDS);
					} catch (Exception exception) {
						throw new IllegalStateException(exception);
					}
				}).toList();

				assertThat(statuses).filteredOn(SessionStatus.VALID::equals).hasSize(50);
				assertThat(statuses).filteredOn(SessionStatus.LIMITED::equals).hasSize(50);
			}
			assertThat(new JdbcTemplate(dataSource).queryForObject(
				"SELECT request_count FROM route_v2_sessions WHERE token_sha256 = ?",
				Integer.class,
				tokenHash
			)).isEqualTo(50);
		}
	}

	@Test
	@DisplayName("H2 migration도 ledger row의 source와 snapshot source 불일치를 차단한다")
	void h2MigrationRejectsMismatchedLedgerSnapshotSource() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-ledger-source-fk;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		var jdbcTemplate = new JdbcTemplate(dataSource);
		assertAdPlacementsSeeded(jdbcTemplate);
		assertSnapshotSourceForeignKeysRejectMismatch(jdbcTemplate);
		assertSnapshotGovernanceGuards(jdbcTemplate);
	}

	@Test
	@DisplayName("H2 V52는 기존 source의 다중 root lineage가 있으면 migration을 중단한다")
	void h2GovernanceMigrationRejectsExistingMultipleRoots() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-multiple-roots;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		flyway(dataSource, "classpath:db/migration/h2", null)
			.target(MigrationVersion.fromVersion("50"))
			.load()
			.migrate();
		var jdbcTemplate = new JdbcTemplate(dataSource);
		insertLegacySnapshotBeforeGovernance(jdbcTemplate, "legacy-root-a", "legacy-source");
		insertLegacySnapshotBeforeGovernance(jdbcTemplate, "legacy-root-b", "legacy-source");

		assertThatThrownBy(() -> migrate(dataSource, "classpath:db/migration/h2", null))
			.isInstanceOf(org.flywaydb.core.api.FlywayException.class)
			.hasMessageContaining("V52__datapack_source_governance.sql")
			.rootCause()
			.hasMessageContaining("uq_data_source_snapshots_source_root");
	}

	@Test
	@DisplayName("PostgreSQL V48은 기존 placement의 운영 표시명과 비활성 상태를 보존한다")
	void postgresqlAdPlacementSeedPreservesExistingRow() {
		String schema = "ad_seed_" + System.nanoTime();
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		migrateToV47(dataSource, "classpath:db/migration/postgresql", schema);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update("INSERT INTO " + schema + ".ad_placements (id, display_name, enabled) VALUES (?, ?, FALSE)",
			"route-result-bottom", "운영자 지정 경로 슬롯");

		migrate(dataSource, "classpath:db/migration/postgresql", schema);

		assertPreservedPlacement(jdbcTemplate, schema);
	}

	@Test
	@DisplayName("H2 V48은 기존 placement의 운영 표시명과 비활성 상태를 보존한다")
	void h2AdPlacementSeedPreservesExistingRow() {
		String database = "jdbc:h2:mem:ad-seed-preserve;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
		var dataSource = new DriverManagerDataSource(database, "sa", "");
		migrateToV47(dataSource, "classpath:db/migration/h2", null);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.update("INSERT INTO ad_placements (id, display_name, enabled) VALUES (?, ?, FALSE)",
			"route-result-bottom", "운영자 지정 경로 슬롯");

		migrate(dataSource, "classpath:db/migration/h2", null);

		assertPreservedPlacement(jdbcTemplate, null);
	}

	@Test
	@DisplayName("PostgreSQL V51은 참조되지 않은 route search result만 삭제한다")
	void postgresqlV51PurgesOnlyUnreferencedRouteSearchResults() {
		String schema = "route_purge_" + System.nanoTime();
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		migrateToV50(dataSource, "classpath:db/migration/postgresql", schema);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		seedRoutePurgeFixture(jdbcTemplate, schema);

		migrate(dataSource, "classpath:db/migration/postgresql", schema);

		assertRoutePurgeResult(jdbcTemplate, schema);
	}

	@Test
	@DisplayName("H2 V51은 참조되지 않은 route search result만 삭제한다")
	void h2V51PurgesOnlyUnreferencedRouteSearchResults() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:route-purge;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		migrateToV50(dataSource, "classpath:db/migration/h2", null);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		seedRoutePurgeFixture(jdbcTemplate, null);

		migrate(dataSource, "classpath:db/migration/h2", null);

		assertRoutePurgeResult(jdbcTemplate, null);
	}

	@Test
	@DisplayName("H2 migration도 source snapshot raw evidence policy를 차단한다")
	void h2MigrationRejectsUnsafeSourceSnapshotEvidence() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-source-snapshot-evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertSnapshotRawEvidencePolicyGuards(new JdbcTemplate(dataSource));
	}

	@Test
	@DisplayName("H2 migration도 production manual override guard를 차단한다")
	void h2MigrationRejectsUnsafeManualOverrides() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-manual-overrides;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertManualOverrideProductionGuards(new JdbcTemplate(dataSource));
	}

	@Test
	@DisplayName("H2 migration도 route edge evidence의 strict route guard를 차단한다")
	void h2MigrationRejectsUnsafeRouteEdgeEvidence() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-route-edge-evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertRouteEdgeEvidenceStrictRouteGuards(new JdbcTemplate(dataSource));
	}

	@Test
	@DisplayName("H2 migration도 facility evidence의 strict route guard를 차단한다")
	void h2MigrationRejectsUnsafeFacilityEvidence() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-facility-evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertFacilityEvidenceStrictRouteGuards(new JdbcTemplate(dataSource));
	}

	@Test
	@DisplayName("H2 migration도 route service artifact hash 형식을 차단한다")
	void h2MigrationRejectsUnsafeRouteServiceIdentityHashes() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:route-service-identity-hashes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		var jdbcTemplate = new JdbcTemplate(dataSource);
		assertRouteServiceIdentityHashGuards(jdbcTemplate);
		assertRouteServiceSourceIssueGuards(jdbcTemplate);
	}

	@Test
	@DisplayName("H2 migration도 normalization run과 output ledger guard를 차단한다")
	void h2MigrationRejectsUnsafeNormalizationRuns() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:datapack-normalization-runs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/h2")
			.load()
			.migrate();

		assertNormalizationRunGuards(new JdbcTemplate(dataSource));
		assertRouteV2AllowlistSchema(new JdbcTemplate(dataSource));
	}

	private void assertRouteV2AllowlistSchema(JdbcTemplate jdbcTemplate) {
		assertThat(columns(jdbcTemplate, "route_v2_sessions"))
			.containsExactly("expires_at", "issued_at", "request_count", "scope", "token_sha256");
		assertThat(columns(jdbcTemplate, "route_v2_nonce_replays"))
			.containsExactly("expires_at", "nonce_sha256");
		assertThat(columns(jdbcTemplate, "route_v2_states")).containsExactly(
			"created_at",
			"destination_station_id",
			"expires_at",
			"itinerary_json",
			"origin_station_id",
			"planned_arrival_at",
			"requested_departure_at",
			"route_state_id",
			"timetable_artifact_id",
			"transport_scope"
		);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			INSERT INTO route_v2_sessions (token_sha256, scope, issued_at, expires_at, request_count)
			VALUES (?, 'route:v2:itx', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes', 51)
			""", "a".repeat(64))).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			INSERT INTO route_v2_states (
				route_state_id, origin_station_id, destination_station_id, transport_scope,
				requested_departure_at, itinerary_json, timetable_artifact_id,
				created_at, planned_arrival_at, expires_at
			) VALUES (?, 'origin', 'destination', 'SUBWAY_AND_ITX_CHEONGCHUN',
				CURRENT_TIMESTAMP, '{}', 'artifact', CURRENT_TIMESTAMP,
				CURRENT_TIMESTAMP + INTERVAL '10 minutes', CURRENT_TIMESTAMP + INTERVAL '1 hour')
			""", "invalid-expiry")).isInstanceOf(DataAccessException.class);
		assertThat(indexNames(jdbcTemplate, "route_v2_sessions")).contains("idx_route_v2_sessions_expires_at");
		assertThat(indexNames(jdbcTemplate, "route_v2_nonce_replays")).contains("idx_route_v2_nonce_replays_expires_at");
	}

	private List<String> indexNames(JdbcTemplate jdbcTemplate, String tableName) {
		return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<List<String>>) connection -> {
			String physicalName = connection.getMetaData().storesUpperCaseIdentifiers()
				? tableName.toUpperCase(java.util.Locale.ROOT)
				: tableName;
			try (var indexes = connection.getMetaData().getIndexInfo(null, null, physicalName, false, false)) {
				var names = new java.util.ArrayList<String>();
				while (indexes.next()) {
					String name = indexes.getString("INDEX_NAME");
					if (name != null) {
						names.add(name.toLowerCase(java.util.Locale.ROOT));
					}
				}
				return names;
			}
		});
	}

	private List<String> columns(JdbcTemplate jdbcTemplate, String tableName) {
		return jdbcTemplate.queryForList("""
			SELECT LOWER(column_name)
			FROM information_schema.columns
			WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = ?
			ORDER BY LOWER(column_name)
			""", String.class, tableName);
	}

	private List<String> tableNames(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForList("""
			SELECT table_name
			FROM information_schema.tables
			WHERE table_schema = 'public'
			ORDER BY table_name
			""", String.class);
	}

	private void assertAdPlacementsSeeded(JdbcTemplate jdbcTemplate) {
		assertThat(jdbcTemplate.queryForList("""
			SELECT id
			FROM ad_placements
			WHERE enabled = TRUE
			ORDER BY id
			""", String.class))
			.containsExactly("route-result-bottom", "station-detail-bottom");
	}

	private void assertPreservedPlacement(JdbcTemplate jdbcTemplate, String schema) {
		String table = schema == null ? "ad_placements" : schema + ".ad_placements";
		List<Object> placement = jdbcTemplate.queryForObject(
			"SELECT display_name, enabled FROM " + table + " WHERE id = ?",
			(resultSet, rowNumber) -> List.of(resultSet.getString("display_name"), resultSet.getBoolean("enabled")),
			"route-result-bottom");
		assertThat(placement)
			.containsExactly("운영자 지정 경로 슬롯", false);
	}

	private void migrateToV47(javax.sql.DataSource dataSource, String location, String schema) {
		flyway(dataSource, location, schema)
			.target(MigrationVersion.fromVersion("47"))
			.load()
			.migrate();
	}

	private void migrateToV50(javax.sql.DataSource dataSource, String location, String schema) {
		flyway(dataSource, location, schema)
			.target(MigrationVersion.fromVersion("50"))
			.load()
			.migrate();
	}

	private void seedRoutePurgeFixture(JdbcTemplate jdbcTemplate, String schema) {
		String prefix = schema == null ? "" : schema + ".";
		for (String routeSearchId : List.of("favorite-route", "station-route", "feedback-route", "unreferenced-route")) {
			jdbcTemplate.update("""
				INSERT INTO %sroute_search_results (
					route_search_id, origin_station_id, origin_station_name,
					destination_station_id, destination_station_name, mobility_type,
					status, line_id, line_name, score, steps_json, warnings_json,
					blocked_reasons_json, created_at
				) VALUES (?, 'origin', 'Origin', 'destination', 'Destination', 'SENIOR',
					'FOUND', 'line', 'Line', 100, '[]', '[]', '[]', CURRENT_TIMESTAMP)
				""".formatted(prefix), routeSearchId);
		}
		jdbcTemplate.update("""
			INSERT INTO %sfavorite_routes (
				user_id, route_search_id, origin_station_id, origin_station_name,
				destination_station_id, destination_station_name, mobility_type,
				status, line_id, line_name, score, steps_json, warnings_json,
				blocked_reasons_json, route_created_at, added_at
			) VALUES ('favorite-user', 'favorite-route', 'origin', 'Origin',
				'destination', 'Destination', 'SENIOR', 'FOUND', 'line', 'Line',
				100, '[]', '[]', '[]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""".formatted(prefix));
		jdbcTemplate.update("""
			INSERT INTO %sfavorite_route_stations (user_id, route_search_id, station_id)
			VALUES ('station-user', 'station-route', 'station')
			""".formatted(prefix));
		jdbcTemplate.update("""
			INSERT INTO %sroute_feedbacks (
				feedback_id, route_search_id, user_id, rating, comment, created_at
			) VALUES ('feedback', 'feedback-route', 'feedback-user', 'HELPFUL', '', CURRENT_TIMESTAMP)
			""".formatted(prefix));
	}

	private void assertRoutePurgeResult(JdbcTemplate jdbcTemplate, String schema) {
		String prefix = schema == null ? "" : schema + ".";
		assertThat(jdbcTemplate.queryForList(
			"SELECT route_search_id FROM " + prefix + "route_search_results ORDER BY route_search_id",
			String.class
		)).containsExactly("favorite-route", "feedback-route", "station-route");
	}

	private void migrate(javax.sql.DataSource dataSource, String location, String schema) {
		flyway(dataSource, location, schema).load().migrate();
	}

	private void insertLegacyRunningRun(JdbcTemplate jdbcTemplate, String schema, String runId) {
		String prefix = schema == null ? "" : schema + ".";
		jdbcTemplate.update("""
			INSERT INTO %sdata_collection_runs (
				run_id, source, status, requested_by, started_at, completed_at,
				collected_count, failure_message, retryable, operator_action
			)
			VALUES (?, 'TRANSIT_MASTER', 'RUNNING', 'legacy-admin', CURRENT_TIMESTAMP,
				NULL, 0, NULL, FALSE, '수집 실행 중입니다.')
			""".formatted(prefix), runId);
	}

	private org.flywaydb.core.api.configuration.FluentConfiguration flyway(
		javax.sql.DataSource dataSource,
		String location,
		String schema
	) {
		var configuration = Flyway.configure()
			.configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
			.dataSource(dataSource)
			.locations(location);
		return schema == null ? configuration : configuration.schemas(schema).defaultSchema(schema);
	}

	private List<String> successfulMigrationVersions(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForList("""
			SELECT version
			FROM flyway_schema_history
			WHERE success = true
			ORDER BY installed_rank
			""", String.class);
	}

	private List<String> foreignKeyNames(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForList("""
			SELECT constraint_name
			FROM information_schema.table_constraints
			WHERE table_schema = 'public'
				AND constraint_type = 'FOREIGN KEY'
			ORDER BY constraint_name
			""", String.class);
	}

	private List<String> checkConstraintNames(JdbcTemplate jdbcTemplate) {
		return jdbcTemplate.queryForList("""
			SELECT constraint_name
			FROM information_schema.table_constraints
			WHERE table_schema = 'public'
				AND constraint_type = 'CHECK'
			ORDER BY constraint_name
			""", String.class);
	}

	private void assertPostgresqlSnapshotRawEvidenceConstraintsAreStaged(JdbcTemplate jdbcTemplate) {
		assertThat(jdbcTemplate.queryForList("""
			SELECT conname
			FROM pg_constraint constraint_row
			JOIN pg_namespace namespace_row ON namespace_row.oid = constraint_row.connamespace
			WHERE conname IN (
				'chk_data_source_snapshots_credential_redacted',
				'chk_data_source_snapshots_raw_object_uri',
				'chk_data_source_snapshots_raw_retention'
			)
				AND namespace_row.nspname = 'public'
				AND convalidated = false
			ORDER BY conname
			""", String.class))
			.containsExactly(
				"chk_data_source_snapshots_credential_redacted",
				"chk_data_source_snapshots_raw_object_uri",
				"chk_data_source_snapshots_raw_retention"
			);
	}

	private void assertDatapackPermissionMatrix(JdbcTemplate jdbcTemplate) {
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "ADMIN_VIEWER"))
			.contains("admin.datapack.read");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "REPORT_REVIEWER"))
			.doesNotContain("admin.datapack.read");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "DATA_OPERATOR"))
			.contains(
				"admin.datapack.read",
				"admin.datapack.source.run",
				"admin.datapack.candidate.build",
				"admin.datapack.staging.promote"
			)
			.doesNotContain(
				"admin.datapack.override.approve",
				"admin.datapack.production.approve",
				"admin.datapack.rollback"
			);
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "MASTER_EDITOR"))
			.contains(
				"admin.datapack.read",
				"admin.datapack.alias.review",
				"admin.datapack.quarantine.review",
				"admin.datapack.evidence.review",
				"admin.datapack.override.request"
			)
			.doesNotContain("admin.datapack.production.approve", "admin.datapack.rollback");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "FIELD_OPERATOR"))
			.contains(
				"admin.datapack.read",
				"admin.datapack.evidence.review",
				"admin.datapack.override.request"
			)
			.doesNotContain("admin.datapack.production.approve", "admin.datapack.rollback");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "SECURITY_ADMIN"))
			.contains("admin.datapack.audit.read")
			.doesNotContain("admin.datapack.production.approve");
		assertThat(permissionAuthoritiesForRole(jdbcTemplate, "SUPER_ADMIN"))
			.contains(
				"admin.datapack.read",
				"admin.datapack.source.run",
				"admin.datapack.alias.review",
				"admin.datapack.quarantine.review",
				"admin.datapack.evidence.review",
				"admin.datapack.override.request",
				"admin.datapack.override.approve",
				"admin.datapack.candidate.build",
				"admin.datapack.staging.promote",
				"admin.datapack.production.approve",
				"admin.datapack.rollback",
				"admin.datapack.audit.read"
			);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
			VALUES ('DATA_OPERATOR', 'admin.datapack.unlisted', CURRENT_TIMESTAMP)
			"""))
			.isInstanceOf(DataAccessException.class);
	}

	private List<String> permissionAuthoritiesForRole(JdbcTemplate jdbcTemplate, String roleCode) {
		return jdbcTemplate.queryForList("""
			SELECT permission_code
			FROM admin_role_permissions
			WHERE role_code = ?
			ORDER BY permission_code
			""", String.class, roleCode);
	}

	private void assertSnapshotSourceForeignKeysRejectMismatch(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "snapshot-a", "source-a");
		insertSnapshot(jdbcTemplate, "snapshot-b", "source-b");

		assertThatThrownBy(() -> insertAliasApproval(jdbcTemplate, "source-b", "snapshot-a"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertQuarantineRecord(jdbcTemplate, "source-b", "snapshot-a"))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertSnapshot(JdbcTemplate jdbcTemplate, String snapshotId, String sourceId) {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				coverage_count, raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status,
				redistribution_allowed, credential_redacted, previous_snapshot_id,
				diff_summary, freshness_expires_at, raw_retention_expires_at
			)
			VALUES (?, ?, 'KRIC', '2026-06-29 00:00:00', NULL, 1, 1, ?, ?, ?, ?,
				'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE, NULL, NULL,
				'2026-07-06 00:00:00', '2026-09-29 00:00:00')
			""",
			snapshotId,
			sourceId,
			"a".repeat(64),
			"s3://evidence/" + snapshotId,
			"b".repeat(64),
			"c".repeat(64)
		);
	}

	private void insertLegacySnapshotBeforeGovernance(
		JdbcTemplate jdbcTemplate,
		String snapshotId,
		String sourceId
	) {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status,
				redistribution_allowed, credential_redacted, previous_snapshot_id,
				diff_summary, freshness_expires_at, raw_retention_expires_at
			)
			VALUES (?, ?, 'KRIC', '2026-06-29 00:00:00', NULL, 1, ?, ?, ?, ?,
				'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE, NULL, NULL,
				'2026-07-06 00:00:00', '2026-09-29 00:00:00')
			""",
			snapshotId,
			sourceId,
			"a".repeat(64),
			"s3://evidence/" + snapshotId,
			"b".repeat(64),
			"c".repeat(64)
		);
	}

	private void assertSnapshotGovernanceGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "lineage-root", "lineage-source");
		assertThatThrownBy(() -> insertSnapshot(jdbcTemplate, "lineage-second-root", "lineage-source"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotChild(
			jdbcTemplate,
			"lineage-cross-source",
			"other-source",
			"lineage-root"
		)).isInstanceOf(DataAccessException.class);

		insertSnapshotChild(jdbcTemplate, "lineage-child", "lineage-source", "lineage-root");
		assertThatThrownBy(() -> insertSnapshotChild(
			jdbcTemplate,
			"lineage-fork",
			"lineage-source",
			"lineage-root"
		)).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			UPDATE data_source_snapshots
			SET governance_policy_version = '2026-07-15'
			WHERE snapshot_id = 'lineage-root'
			""")).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			UPDATE data_source_snapshots
			SET governance_policy_sha256 = ?
			WHERE snapshot_id = 'lineage-root'
			""", "d".repeat(64))).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("""
			UPDATE data_source_snapshots
			SET coverage_count = -1
			WHERE snapshot_id = 'lineage-root'
			""")).isInstanceOf(DataAccessException.class);
	}

	private void assertPostgresqlSnapshotLineageIsAppendOnly(JdbcTemplate jdbcTemplate) {
		assertThatThrownBy(() -> insertSnapshotChild(
			jdbcTemplate,
			"lineage-self",
			"lineage-self-source",
			"lineage-self"
		)).isInstanceOf(DataAccessException.class);

		insertSnapshot(jdbcTemplate, "lineage-cycle-root", "lineage-cycle-source");
		insertSnapshotChild(jdbcTemplate, "lineage-cycle-child", "lineage-cycle-source", "lineage-cycle-root");
		assertThatThrownBy(() -> jdbcTemplate.update("""
			UPDATE data_source_snapshots
			SET previous_snapshot_id = 'lineage-cycle-child'
			WHERE snapshot_id = 'lineage-cycle-root'
			""")).isInstanceOf(DataAccessException.class);
	}

	private void insertSnapshotChild(
		JdbcTemplate jdbcTemplate,
		String snapshotId,
		String sourceId,
		String previousSnapshotId
	) {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				coverage_count, raw_sha256, raw_object_uri, redacted_request_fingerprint,
				schema_fingerprint, snapshot_status, schema_status, license_status,
				fetch_status, redistribution_allowed, credential_redacted,
				previous_snapshot_id, diff_summary, diff_summary_json,
				freshness_expires_at, raw_retention_expires_at,
				governance_policy_version, governance_policy_sha256
			)
			VALUES (?, ?, 'KRIC', '2026-06-30 00:00:00', NULL, 2, 2, ?, ?, ?, ?,
				'LOCKED', 'PASS', 'PASS', 'SUCCESS', TRUE, TRUE, ?, 'CHANGED',
				'{"status":"CHANGED"}', '2026-07-07 00:00:00', '2026-09-30 00:00:00',
				'2026-07-15', ?)
			""",
			snapshotId,
			sourceId,
			"a".repeat(64),
			"s3://evidence/" + snapshotId,
			"b".repeat(64),
			"c".repeat(64),
			previousSnapshotId,
			"d".repeat(64)
		);
	}

	private void assertSnapshotRawEvidencePolicyGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "snapshot-raw-policy-ok", "source-raw-policy");

		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-unredacted",
			"s3://evidence/snapshot-raw-policy-unredacted.json",
			false,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-secret-uri",
			"s3://evidence/snapshot-raw-policy-secret-uri.json?serviceKey=secret",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-userinfo-uri",
			"s3://access:secret@evidence/snapshot-raw-policy-userinfo-uri.json",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-object-key-at-uri",
			"s3://evidence/raw/provider@example.json",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-fragment-uri",
			"oci://evidence/snapshot-raw-policy-fragment-uri.json#token=secret",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-empty-bucket-uri",
			"s3:///snapshot-raw-policy-empty-bucket-uri.json",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-bucket-only-uri",
			"s3://evidence",
			true,
			"2026-07-06 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertSnapshotEvidencePolicyCase(
			jdbcTemplate,
			"snapshot-raw-policy-expired-retention",
			"s3://evidence/snapshot-raw-policy-expired-retention.json",
			true,
			"2026-06-28 00:00:00"
		))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertSnapshotEvidencePolicyCase(
		JdbcTemplate jdbcTemplate,
		String snapshotId,
		String rawObjectUri,
		boolean credentialRedacted,
		String rawRetentionExpiresAt
	) {
		jdbcTemplate.update("""
			INSERT INTO data_source_snapshots (
				snapshot_id, source_id, provider, retrieved_at, source_updated_at, row_count,
				raw_sha256, raw_object_uri, redacted_request_fingerprint, schema_fingerprint,
				snapshot_status, schema_status, license_status, fetch_status,
				redistribution_allowed, credential_redacted, previous_snapshot_id,
				diff_summary, freshness_expires_at, raw_retention_expires_at
			)
			VALUES (?, 'source-raw-policy', 'KRIC', '2026-06-29 00:00:00', NULL, 1,
				?, ?, ?, ?, 'LOCKED', 'PASS', 'PASS', 'SUCCESS',
				TRUE, ?, NULL, NULL, '2026-07-06 00:00:00', ?)
			""",
			snapshotId,
			"a".repeat(64),
			rawObjectUri,
			"b".repeat(64),
			"c".repeat(64),
			credentialRedacted,
			Timestamp.valueOf(rawRetentionExpiresAt)
		);
	}

	private void insertAliasApproval(JdbcTemplate jdbcTemplate, String sourceId, String sourceSnapshotId) {
		jdbcTemplate.update("""
			INSERT INTO external_alias_approvals (
				id, source_id, source_snapshot_id, provider_entity_type, provider_entity_id,
				canonical_entity_type, canonical_entity_id, confidence, match_method,
				approval_status, requested_by, approved_by, approved_at, evidence_hash,
				superseded_by, created_at
			)
			VALUES ('alias-mismatch', ?, ?, 'STATION', 'provider-station',
				'STATION', 'station-1', 90, 'AUTO', 'PENDING', 'qa', NULL, NULL, ?,
				NULL, '2026-06-29 00:00:00')
			""", sourceId, sourceSnapshotId, "d".repeat(64));
	}

	private void insertQuarantineRecord(JdbcTemplate jdbcTemplate, String sourceId, String sourceSnapshotId) {
		jdbcTemplate.update("""
			INSERT INTO source_quarantine_records (
				id, source_id, source_snapshot_id, provider_record_hash, reason_code,
				severity, redacted_excerpt, resolution_status, resolved_by, resolved_at,
				created_at
			)
			VALUES ('quarantine-mismatch', ?, ?, ?, 'ALIAS_CONFLICT',
				'P1', NULL, 'OPEN', NULL, NULL, '2026-06-29 00:00:00')
			""", sourceId, sourceSnapshotId, "e".repeat(64));
	}

	private void assertNormalizationRunGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "normalization-snapshot-a", "normalization-source-a");
		insertSnapshot(jdbcTemplate, "normalization-snapshot-b", "normalization-source-b");
		insertNormalizationRun(jdbcTemplate, "normalization-ok", "normalization-source-a", "normalization-snapshot-a",
			10, 6, 2, 2, "COMPLETED", "2026-06-29 00:10:00");
		insertNormalizedOutput(jdbcTemplate, "normalization-output-accepted", "normalization-ok", "ACCEPTED_ROWS", 6);
		insertNormalizedOutput(jdbcTemplate, "normalization-output-schema-diff", "normalization-ok", "SCHEMA_DIFF", 0);

		assertThatThrownBy(() -> insertNormalizationRun(jdbcTemplate, "normalization-source-mismatch", "normalization-source-b", "normalization-snapshot-a",
			1, 1, 0, 0, "COMPLETED", "2026-06-29 00:10:00"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNormalizationRun(jdbcTemplate, "normalization-negative-count", "normalization-source-a", "normalization-snapshot-a",
			1, -1, 0, 0, "COMPLETED", "2026-06-29 00:10:00"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNormalizationRun(jdbcTemplate, "normalization-unfinished-completed", "normalization-source-a", "normalization-snapshot-a",
			1, 1, 0, 0, "COMPLETED", null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNormalizedOutput(jdbcTemplate, "normalization-output-bad-kind", "normalization-ok", "UNKNOWN", 1))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertNormalizedOutput(jdbcTemplate, "normalization-output-orphan", "missing-run", "ACCEPTED_ROWS", 1))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertNormalizationRun(
		JdbcTemplate jdbcTemplate,
		String runId,
		String sourceId,
		String sourceSnapshotId,
		int normalizedCount,
		int acceptedCount,
		int quarantineCount,
		int aliasReviewCount,
		String status,
		String completedAt
	) {
		jdbcTemplate.update("""
			INSERT INTO datapack_normalization_runs (
				id, source_id, source_snapshot_id, normalized_count, accepted_count,
				quarantine_count, alias_review_count, schema_diff_sha256,
				schema_diff_summary, status, started_at, completed_at
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'schema fields unchanged', ?,
				'2026-06-29 00:00:00', ?)
			""",
			runId,
			sourceId,
			sourceSnapshotId,
			normalizedCount,
			acceptedCount,
			quarantineCount,
			aliasReviewCount,
			"7".repeat(64),
			status,
			completedAt == null ? null : Timestamp.valueOf(completedAt)
		);
	}

	private void insertNormalizedOutput(
		JdbcTemplate jdbcTemplate,
		String outputId,
		String normalizationRunId,
		String outputKind,
		int rowCount
	) {
		jdbcTemplate.update("""
			INSERT INTO datapack_normalized_outputs (
				id, normalization_run_id, output_kind, row_count, output_sha256,
				object_uri, created_at
			)
			VALUES (?, ?, ?, ?, ?, ?, '2026-06-29 00:00:00')
			""",
			outputId,
			normalizationRunId,
			outputKind,
			rowCount,
			"6".repeat(64),
			"s3://evidence/normalized/" + outputId + ".json"
		);
	}

	private void assertManualOverrideProductionGuards(JdbcTemplate jdbcTemplate) {
		insertManualOverride(jdbcTemplate, "override-ok", "facility-1", "APPROVED", "qa", "reviewer", false, null, null);
		insertManualOverride(jdbcTemplate, "override-strict-pending", "facility-pending", "PENDING", "qa", null, true, null, null);

		assertThatThrownBy(() ->
			insertManualOverride(jdbcTemplate, "override-self-approved", "facility-self", "APPROVED", "qa", "qa", false, null, null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() ->
			insertManualOverride(jdbcTemplate, "override-strict-unsafe", "facility-strict", "APPROVED", "qa", "reviewer", true, null, null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() ->
			insertManualOverride(jdbcTemplate, "override-duplicate", "facility-1", "APPROVED", "qa2", "reviewer2", false, null, null))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertManualOverride(
		JdbcTemplate jdbcTemplate,
		String overrideId,
		String entityId,
		String approvalStatus,
		String requestedBy,
		String approvedBy,
		boolean strictRouteEligible,
		String routeSafetyApprovedBy,
		String supersededBy
	) {
		jdbcTemplate.update("""
			INSERT INTO manual_overrides (
				id, entity_type, entity_id, field_name, before_value, after_value,
				reason_code, reason, evidence_uri, evidence_hash, requested_by,
				approved_by, approved_at, route_safety_approved_by, approval_status,
				conflict_status, strict_route_eligible, effective_from, expires_at,
				superseded_by, created_at
			)
			VALUES (?, 'FACILITY', ?, 'operational_status', 'UNKNOWN', 'AVAILABLE',
				'FIELD_VERIFIED', '현장 검증 결과 반영', 's3://evidence/manual/1.json', ?,
				?, ?, '2026-06-29 01:00:00', ?, ?, 'NONE', ?,
				'2026-06-29 00:00:00', '2026-07-29 00:00:00', ?, '2026-06-29 00:00:00')
			""",
			overrideId,
			entityId,
			"f".repeat(64),
			requestedBy,
			approvedBy,
			routeSafetyApprovedBy,
			approvalStatus,
			strictRouteEligible,
			supersededBy
		);
	}

	private void assertFacilityEvidenceStrictRouteGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "facility-snapshot-a", "facility-source-a");
		insertSnapshot(jdbcTemplate, "facility-snapshot-b", "facility-source-b");
		insertFacilityEvidence(jdbcTemplate, "facility-evidence-ok", "facility-source-a", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "AVAILABLE", "OPERATOR_CONFIRMED", true, null);
		insertFacilityEvidence(jdbcTemplate, "facility-static-visible", "facility-source-a", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "UNKNOWN", "STATIC_LOCATION", false, "OPERATIONAL_STATUS_UNKNOWN");

		assertThatThrownBy(() -> insertFacilityEvidence(jdbcTemplate, "facility-source-mismatch", "facility-source-b", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "AVAILABLE", "OPERATOR_CONFIRMED", true, null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertFacilityEvidence(jdbcTemplate, "facility-unknown-strict", "facility-source-a", "facility-snapshot-a",
			"UNKNOWN_PENDING_REVIEW", "UNKNOWN", "UNKNOWN", "STATIC_LOCATION", true, "UNKNOWN_PENDING_REVIEW"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertFacilityEvidence(jdbcTemplate, "facility-static-strict", "facility-source-a", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "UNKNOWN", "STATIC_LOCATION", true, "OPERATIONAL_STATUS_UNKNOWN"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertFacilityEvidence(jdbcTemplate, "facility-orphan-override", "facility-source-a", "facility-snapshot-a",
			"EXISTS", "INSTALLED", "AVAILABLE", "OPERATOR_CONFIRMED", true, null, "missing-override"))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertFacilityEvidence(
		JdbcTemplate jdbcTemplate,
		String evidenceId,
		String sourceId,
		String sourceSnapshotId,
		String evidenceKind,
		String installationStatus,
		String operationalStatus,
		String statusMeaning,
		boolean strictRouteEligible,
		String strictRouteEligibleReason
	) {
		insertFacilityEvidence(
			jdbcTemplate,
			evidenceId,
			sourceId,
			sourceSnapshotId,
			evidenceKind,
			installationStatus,
			operationalStatus,
			statusMeaning,
			strictRouteEligible,
			strictRouteEligibleReason,
			null
		);
	}

	private void insertFacilityEvidence(
		JdbcTemplate jdbcTemplate,
		String evidenceId,
		String sourceId,
		String sourceSnapshotId,
		String evidenceKind,
		String installationStatus,
		String operationalStatus,
		String statusMeaning,
		boolean strictRouteEligible,
		String strictRouteEligibleReason,
		String manualOverrideId
	) {
		jdbcTemplate.update("""
			INSERT INTO facility_evidence (
				id, station_id, line_id, facility_type, evidence_kind, source_id,
				source_snapshot_id, provider_record_hash, status_meaning,
				installation_status, operational_status, verified_at, retrieved_at,
				freshness_expires_at, confidence, strict_route_eligible,
				strict_route_eligible_reason, conflict_status, manual_override_id, created_at
			)
			VALUES (?, 'station-1', 'line-1', 'ELEVATOR', ?, ?, ?, ?, ?, ?, ?,
				'2026-06-29 00:00:00', '2026-06-29 00:00:00', '2026-07-06 00:00:00',
				90, ?, ?, 'NONE', ?, '2026-06-29 00:00:00')
			""",
			evidenceId,
			evidenceKind,
			sourceId,
			sourceSnapshotId,
			"8".repeat(64),
			statusMeaning,
			installationStatus,
			operationalStatus,
			strictRouteEligible,
			strictRouteEligibleReason,
			manualOverrideId
		);
	}

	private void assertRouteEdgeEvidenceStrictRouteGuards(JdbcTemplate jdbcTemplate) {
		insertSnapshot(jdbcTemplate, "route-snapshot-a", "route-source-a");
		insertSnapshot(jdbcTemplate, "route-snapshot-b", "route-source-b");
		insertRouteEdgeEvidence(jdbcTemplate, "route-edge-ok", "route-source-a", "route-snapshot-a",
			"ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null);
		insertRouteEdgeEvidence(jdbcTemplate, "route-edge-generated-visible", "route-source-a", "route-snapshot-a",
			"GENERATED_CONNECTOR", "GENERATED", "GENERATED", false, "GENERATED_CONNECTOR_BLOCKED");

		assertThatThrownBy(() -> insertRouteEdgeEvidence(jdbcTemplate, "route-edge-source-mismatch", "route-source-b", "route-snapshot-a",
			"ENTRY", "OFFICIAL_SOURCE", "VERIFIED", true, null))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteEdgeEvidence(jdbcTemplate, "route-edge-unknown-strict", "route-source-a", "route-snapshot-a",
			"EXIT", "OFFICIAL_SOURCE", "UNKNOWN", true, "UNKNOWN_PENDING_REVIEW"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteEdgeEvidence(jdbcTemplate, "route-edge-generated-strict", "route-source-a", "route-snapshot-a",
			"GENERATED_CONNECTOR", "GENERATED", "GENERATED", true, "GENERATED_CONNECTOR_BLOCKED"))
			.isInstanceOf(DataAccessException.class);
	}

	private void insertRouteEdgeEvidence(
		JdbcTemplate jdbcTemplate,
		String evidenceId,
		String sourceId,
		String sourceSnapshotId,
		String edgeType,
		String provenanceKind,
		String verificationStatus,
		boolean strictRouteEligible,
		String blockerReason
	) {
		jdbcTemplate.update("""
			INSERT INTO route_edge_evidence (
				id, station_id, line_id, edge_id, edge_type, source_id, source_snapshot_id,
				provenance_kind, verification_status, last_verified_at, evidence_hash,
				strict_route_eligible, blocker_reason, created_at
			)
			VALUES (?, 'station-1', 'line-1', ?, ?, ?, ?, ?, ?,
				'2026-06-29 00:00:00', ?, ?, ?, '2026-06-29 00:00:00')
			""",
			evidenceId,
			"edge-" + evidenceId,
			edgeType,
			sourceId,
			sourceSnapshotId,
			provenanceKind,
			verificationStatus,
			"9".repeat(64),
			strictRouteEligible,
			blockerReason
		);
	}

	private void assertRouteServiceIdentityHashGuards(JdbcTemplate jdbcTemplate) {
		String validHash = "a".repeat(64);
		assertThatThrownBy(() -> insertRouteServiceIdentity(jdbcTemplate, "short", validHash, validHash))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteServiceIdentity(
			jdbcTemplate, "g".repeat(64), validHash, validHash))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteServiceIdentity(jdbcTemplate, validHash, "short", validHash))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteServiceIdentity(
			jdbcTemplate, validHash, "g".repeat(64), validHash))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteServiceIdentity(jdbcTemplate, validHash, validHash, "short"))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> insertRouteServiceIdentity(
			jdbcTemplate, validHash, validHash, "g".repeat(64)))
			.isInstanceOf(DataAccessException.class);
	}

	private void assertRouteServiceSourceIssueGuards(JdbcTemplate jdbcTemplate) {
		String validHash = "a".repeat(64);
		jdbcTemplate.update("""
			INSERT INTO route_service_artifact_evidence (
				service_class, timetable_artifact_id, timetable_artifact_sha256,
				canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256,
				admission_status, admission_eligible, fresh_until, source_issue
			) VALUES ('ITX_CHEONGCHUN', 'issue-2135-test', ?, 'capital', ?, ?,
				'MISSING', FALSE, NULL, 2135)
			""", validHash, validHash, validHash);
		jdbcTemplate.update("DELETE FROM route_service_artifact_evidence WHERE service_class = 'ITX_CHEONGCHUN'");
		assertThatThrownBy(() -> jdbcTemplate.update("""
			INSERT INTO route_service_artifact_evidence (
				service_class, timetable_artifact_id, timetable_artifact_sha256,
				canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256,
				admission_status, admission_eligible, fresh_until, source_issue
			) VALUES ('ITX_CHEONGCHUN', 'invalid-source-issue-test', ?, 'capital', ?, ?,
				'MISSING', FALSE, NULL, 9999)
			""", validHash, validHash, validHash)).isInstanceOf(DataAccessException.class);
	}

	private void insertRouteServiceIdentity(
		JdbcTemplate jdbcTemplate,
		String timetableHash,
		String canonicalPackHash,
		String canonicalPackSqliteHash
	) {
		jdbcTemplate.update("""
			INSERT INTO route_service_artifact_evidence (
				service_class, timetable_artifact_id, timetable_artifact_sha256,
				canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256,
				admission_status, admission_eligible, fresh_until, source_issue
			)
			VALUES ('ITX_CHEONGCHUN', 'invalid-hash-test', ?, 'capital', ?, ?,
				'MISSING', FALSE, NULL, 2116)
			""", timetableHash, canonicalPackHash, canonicalPackSqliteHash);
	}
}
