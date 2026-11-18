package com.easysubway.transit.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.easysubway.common.error.TransitDataAccessException;
import com.easysubway.journey.bundle.LoadScenarioBundles;
import com.easysubway.journey.bundle.RouteBundleActivationRegistry;
import com.easysubway.journey.bundle.RouteBundleFacilityCatalog;
import com.easysubway.journey.bundle.RouteBundleRuntimeView;
import com.easysubway.transit.application.port.out.MasterDataCapabilityStatus;
import com.easysubway.transit.domain.AccessibilityFacility;
import com.easysubway.transit.domain.AccessibilityFacilityStatus;
import com.easysubway.transit.domain.AccessibilityFacilityType;
import com.easysubway.transit.domain.DataConfidenceLevel;
import com.easysubway.transit.domain.DataQualityLevel;
import com.easysubway.transit.domain.DataSourceType;
import com.easysubway.transit.domain.RouteEdge;
import com.easysubway.transit.domain.RouteEdgeType;
import com.easysubway.transit.domain.RouteNode;
import com.easysubway.transit.domain.RouteNodeType;
import com.easysubway.transit.domain.SimplifiedStationLayoutStatus;
import com.easysubway.transit.domain.StationLayoutSource;
import com.easysubway.transit.domain.StationLayoutSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("JDBC 도시철도 마스터 override 저장소")
class JdbcTransitMasterOverrideRepositoryTest {

	@Test
	@DisplayName("override table readiness가 writable capability를 결정한다")
	void readinessControlsWritableCapability() {
		var ready = new JdbcTransitMasterOverrideRepository(overrideDataSource(), objectMapper()).masterDataCapability();
		var v14Only = new JdbcTransitMasterOverrideRepository(v14OverrideDataSource(), objectMapper()).masterDataCapability();
		var unready = new JdbcTransitMasterOverrideRepository(emptyDataSource(), objectMapper()).masterDataCapability();

		assertThat(ready.status()).isEqualTo(MasterDataCapabilityStatus.UP);
		assertThat(ready.writable()).isTrue();
		assertThat(v14Only.status()).isEqualTo(MasterDataCapabilityStatus.READ_ONLY);
		assertThat(v14Only.writable()).isFalse();
		assertThat(unready.status()).isEqualTo(MasterDataCapabilityStatus.READ_ONLY);
		assertThat(unready.writable()).isFalse();
	}

	@Test
	@DisplayName("database product가 없거나 지원 대상이 아니면 초기화를 거부한다")
	void initializationRejectsMissingAndUnsupportedDatabaseProduct() throws Exception {
		var missing = new JdbcTransitMasterOverrideRepository(databaseProductDataSource(null), objectMapper());
		var unsupported = new JdbcTransitMasterOverrideRepository(databaseProductDataSource("SQLite"), objectMapper());

		assertThatThrownBy(missing::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("database product name is unavailable");
		assertThatThrownBy(unsupported::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("지원하지 않는 transit master override database: SQLite");
	}

	@Test
	@DisplayName("시설 상태와 metadata override는 저장 후 새 repository에서도 effective model로 읽힌다")
	void facilityOverridesPersistAndMergeWithCatalog() {
		DataSource dataSource = overrideDataSource();
		var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());

		repository.saveFacilityStatus(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			LocalDate.of(2026, 6, 27),
			"facility-admin"
		);
		repository.saveAccessibilityFacility(new AccessibilityFacility(
			"facility-ops-new",
			"station-sangnoksu",
			"exit-sangnoksu-1",
			AccessibilityFacilityType.RAMP,
			"임시 경사로",
			"지상",
			"대합실",
			new BigDecimal("37.302500"),
			new BigDecimal("126.866300"),
			"운영자가 추가한 임시 이동 동선입니다.",
			AccessibilityFacilityStatus.ADMIN_VERIFIED,
			DataConfidenceLevel.MEDIUM,
			DataSourceType.ADMIN_VERIFIED,
			LocalDate.of(2026, 6, 27)
		), "metadata-admin");

		var reloaded = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());
		assertThat(reloaded.loadAccessibilityFacility("facility-sangnoksu-elevator-1")).hasValueSatisfying(facility -> {
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.BROKEN);
			assertThat(facility.dataSourceType()).isEqualTo(DataSourceType.ADMIN_VERIFIED);
			assertThat(facility.lastUpdatedAt()).isEqualTo(LocalDate.of(2026, 6, 27));
		});
		assertThat(reloaded.loadAccessibilityFacility("facility-ops-new")).hasValueSatisfying(facility ->
			assertThat(facility.name()).isEqualTo("임시 경사로")
		);
		assertThat(reloaded.listMasterDataOverrideAudits(
			JdbcTransitMasterOverrideRepository.FACILITY,
			"facility-sangnoksu-elevator-1"
		)).hasSize(1);
		assertThat(new JdbcTemplate(dataSource).queryForObject("""
			SELECT updated_by
			FROM transit_master_override_audits
			WHERE entity_type = ? AND entity_id = ?
			""", String.class, JdbcTransitMasterOverrideRepository.FACILITY, "facility-sangnoksu-elevator-1"))
			.isEqualTo("facility-admin");
	}

	@Test
	@DisplayName("생성 뒤 ObjectMapper 변경은 저장 JSON 형식을 바꾸지 않는다")
	void mapperMutationAfterConstructionDoesNotChangePersistedJsonShape() {
		DataSource dataSource = overrideDataSource();
		ObjectMapper mapper = objectMapper();
		var repository = new JdbcTransitMasterOverrideRepository(dataSource, mapper);

		mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		repository.saveFacilityStatus(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			LocalDate.of(2026, 6, 27),
			"mapper-contract-admin"
		);

		String payload = new JdbcTemplate(dataSource).queryForObject("""
			SELECT payload_json
			FROM transit_master_overrides
			WHERE entity_type = ? AND entity_id = ?
			""", String.class, JdbcTransitMasterOverrideRepository.FACILITY, "facility-sangnoksu-elevator-1");
		assertThat(payload).contains("\"stationId\"").doesNotContain("\"station_id\"");
	}

	@Test
	@DisplayName("override가 없는 target rollback은 override와 audit을 만들지 않는다")
	void rollbackWithoutActiveOverrideLeavesOverrideAndAuditEmpty() {
		DataSource dataSource = overrideDataSource();
		var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());

		repository.rollbackMasterDataOverride(
			JdbcTransitMasterOverrideRepository.FACILITY,
			"facility-not-overridden",
			"rollback-admin"
		);

		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_overrides", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_override_audits", Integer.class)).isZero();
	}

	@Test
	@DisplayName("구조도 source/status, route node/edge override를 저장하고 재조회한다")
	void layoutAndRouteOverridesPersist() {
		DataSource dataSource = overrideDataSource();
		var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());

		repository.saveStationLayoutSource(new StationLayoutSource(
			"layout-source-sangnoksu-station-map",
			"station-sangnoksu",
			StationLayoutSourceType.FIELD_SURVEY,
			"현장 검수 구조도",
			"https://ops.easysubway.example/layout",
			"운영 검수 자료",
			true,
			false,
			LocalDate.of(2026, 6, 20),
			LocalDate.of(2026, 6, 27)
		), "source-admin");
		repository.saveSimplifiedStationLayoutStatus(
			"layout-sangnoksu-draft",
			SimplifiedStationLayoutStatus.PUBLISHED,
			"layout-admin",
			LocalDate.of(2026, 6, 27)
		);
		repository.saveRouteNode(new RouteNode(
			"node-sangnoksu-elevator-1",
			"station-sangnoksu",
			RouteNodeType.ELEVATOR,
			"1번 출구 엘리베이터",
			"B1",
			new BigDecimal("37.302421"),
			new BigDecimal("126.866221"),
			"facility-sangnoksu-elevator-1",
			"layout-sangnoksu-draft",
			140,
			260,
			"운영 보정 엘리베이터",
			"출입구 앞 점자블록 확인"
		), "node-admin");
		repository.saveRouteEdge(new RouteEdge(
			"edge-sangnoksu-elevator-to-faregate",
			"station-sangnoksu",
			"node-sangnoksu-elevator-1",
			"node-sangnoksu-faregate",
			RouteEdgeType.WALK,
			30,
			80,
			false,
			true,
			false,
			1,
			3,
			88,
			true
		), "edge-admin");

		var reloaded = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());
		assertThat(reloaded.loadStationLayoutSources()).anySatisfy(source -> {
			assertThat(source.id()).isEqualTo("layout-source-sangnoksu-station-map");
			assertThat(source.sourceType()).isEqualTo(StationLayoutSourceType.FIELD_SURVEY);
			assertThat(source.reviewedAt()).isEqualTo(LocalDate.of(2026, 6, 27));
		});
		assertThat(reloaded.loadSimplifiedStationLayouts()).anySatisfy(layout -> {
			assertThat(layout.id()).isEqualTo("layout-sangnoksu-draft");
			assertThat(layout.status()).isEqualTo(SimplifiedStationLayoutStatus.PUBLISHED);
			assertThat(layout.version()).isEqualTo(2);
		});
		assertThat(reloaded.loadRouteNodes()).anySatisfy(node -> {
			assertThat(node.id()).isEqualTo("node-sangnoksu-elevator-1");
			assertThat(node.displayX()).isEqualTo(140);
			assertThat(node.displayLabel()).isEqualTo("운영 보정 엘리베이터");
		});
		assertThat(reloaded.loadRouteEdges()).anySatisfy(edge -> {
			assertThat(edge.id()).isEqualTo("edge-sangnoksu-elevator-to-faregate");
			assertThat(edge.distanceMeters()).isEqualTo(30);
			assertThat(edge.widthLevel()).isEqualTo(3);
		});
		assertThat(new JdbcTemplate(dataSource).queryForList("""
			SELECT updated_by
			FROM transit_master_override_audits
			WHERE entity_type IN (?, ?, ?)
			ORDER BY audit_id
			""", String.class,
			JdbcTransitMasterOverrideRepository.LAYOUT_SOURCE,
			JdbcTransitMasterOverrideRepository.ROUTE_NODE,
			JdbcTransitMasterOverrideRepository.ROUTE_EDGE
		)).containsExactly("source-admin", "node-admin", "edge-admin");
	}

	@Test
	@DisplayName("rollback은 마지막 override를 audit으로 남기고 catalog 원본으로 되돌린다")
	void rollbackRestoresCatalogValueAndWritesAudit() {
		DataSource dataSource = overrideDataSource();
		var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());

		repository.saveFacilityStatus(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			LocalDate.of(2026, 6, 27),
			"admin-user"
		);
		repository.rollbackMasterDataOverride(
			JdbcTransitMasterOverrideRepository.FACILITY,
			"facility-sangnoksu-elevator-1",
			"admin-user"
		);

		assertThat(repository.loadAccessibilityFacility("facility-sangnoksu-elevator-1")).hasValueSatisfying(facility ->
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.NORMAL)
		);
		assertThat(repository.listMasterDataOverrideAudits(
			JdbcTransitMasterOverrideRepository.FACILITY,
			"facility-sangnoksu-elevator-1"
		))
			.extracting(com.easysubway.transit.application.port.out.TransitMasterOverrideAudit::action)
			.containsExactly("ROLLBACK", "UPSERT");
	}

	@Test
	@DisplayName("rollback은 rollback audit을 건너뛰고 여러 override를 단계적으로 되돌린다")
	void repeatedRollbackUsesUpsertHistoryOnly() {
		DataSource dataSource = overrideDataSource();
		var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());

		repository.saveFacilityStatus(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.BROKEN,
			LocalDate.of(2026, 6, 27),
			"first-admin"
		);
		repository.saveFacilityStatus(
			"facility-sangnoksu-elevator-1",
			AccessibilityFacilityStatus.CLOSED,
			LocalDate.of(2026, 6, 28),
			"second-admin"
		);

		repository.rollbackMasterDataOverride(
			JdbcTransitMasterOverrideRepository.FACILITY,
			"facility-sangnoksu-elevator-1",
			"rollback-admin"
		);
		assertThat(repository.loadAccessibilityFacility("facility-sangnoksu-elevator-1")).hasValueSatisfying(facility ->
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.BROKEN)
		);

		repository.rollbackMasterDataOverride(
			JdbcTransitMasterOverrideRepository.FACILITY,
			"facility-sangnoksu-elevator-1",
			"rollback-admin"
		);
		assertThat(repository.loadAccessibilityFacility("facility-sangnoksu-elevator-1")).hasValueSatisfying(facility ->
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.NORMAL)
		);
		assertThat(repository.listMasterDataOverrideAudits(
			JdbcTransitMasterOverrideRepository.FACILITY,
			"facility-sangnoksu-elevator-1"
		))
			.extracting(com.easysubway.transit.application.port.out.TransitMasterOverrideAudit::action)
			.containsExactly("ROLLBACK", "ROLLBACK", "UPSERT", "UPSERT");
	}

	@Test
	@DisplayName("같은 entity 최초 override 저장은 transaction 안에서 직렬화되고 정확한 pre-image audit을 남긴다")
	void concurrentFirstOverrideWritesDoNotFail() throws Exception {
		DataSource dataSource = overrideDataSource();
		var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		var executor = Executors.newFixedThreadPool(2);
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);

		try {
			var first = executor.submit(() -> {
				saveFacilityStatusAfterStart(
					dataSource,
					transaction,
					ready,
					start,
					AccessibilityFacilityStatus.BROKEN,
					"first-admin",
					LocalDate.of(2026, 6, 27)
				);
				return null;
			});
			var second = executor.submit(() -> {
				saveFacilityStatusAfterStart(
					dataSource,
					transaction,
					ready,
					start,
					AccessibilityFacilityStatus.CLOSED,
					"second-admin",
					LocalDate.of(2026, 6, 28)
				);
				return null;
			});

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());
		assertThat(repository.loadAccessibilityFacility("facility-sangnoksu-elevator-1")).hasValueSatisfying(facility ->
			assertThat(facility.status()).isIn(AccessibilityFacilityStatus.BROKEN, AccessibilityFacilityStatus.CLOSED)
		);
		assertThat(repository.listMasterDataOverrideAudits(
			JdbcTransitMasterOverrideRepository.FACILITY,
			"facility-sangnoksu-elevator-1"
		)).hasSize(2);
		var jdbcTemplate = new JdbcTemplate(dataSource);
		var payloads = jdbcTemplate.queryForList("""
			SELECT payload_json
			FROM transit_master_override_audits
			WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT'
			ORDER BY audit_id
			""", String.class, JdbcTransitMasterOverrideRepository.FACILITY, "facility-sangnoksu-elevator-1");
		assertThat(jdbcTemplate.queryForList("""
			SELECT previous_payload_json
			FROM transit_master_override_audits
			WHERE entity_type = ? AND entity_id = ? AND action = 'UPSERT'
			ORDER BY audit_id
			""", String.class, JdbcTransitMasterOverrideRepository.FACILITY, "facility-sangnoksu-elevator-1"))
			.containsExactly(null, payloads.getFirst());
	}

	@Test
	@DisplayName("저장 transaction이 rollback되면 override, audit, lock identity도 남지 않는다")
	void rolledBackSaveLeavesNoOverrideAuditOrLockIdentity() {
		DataSource dataSource = overrideDataSource();
		var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		var repository = new JdbcTransitMasterOverrideRepository(dataSource, objectMapper());

		transaction.executeWithoutResult(status -> {
			repository.saveFacilityStatus(
				"facility-sangnoksu-elevator-1",
				AccessibilityFacilityStatus.BROKEN,
				LocalDate.of(2026, 6, 27),
				"rollback-admin"
			);
			status.setRollbackOnly();
		});

		var jdbcTemplate = new JdbcTemplate(dataSource);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_overrides", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_override_audits", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_master_override_locks", Integer.class)).isZero();
	}

	private void saveFacilityStatusAfterStart(
		DataSource dataSource,
		TransactionTemplate transaction,
		CountDownLatch ready,
		CountDownLatch start,
		AccessibilityFacilityStatus status,
		String updatedBy,
		LocalDate updatedAt
	) throws InterruptedException {
		ready.countDown();
		assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
		transaction.executeWithoutResult(ignored -> {
			new JdbcTransitMasterOverrideRepository(dataSource, objectMapper()).saveFacilityStatus(
				"facility-sangnoksu-elevator-1",
				status,
				updatedAt,
				updatedBy
			);
			assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("PostgreSQL 조회 장애 시 static-seed로 마스킹하지 않고 TransitDataAccessException 방출 및 메트릭 증가")
	void dataAccessExceptionThrowsTransitDataAccessException() {
		DataSource brokenDataSource = emptyDataSource();
		var meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
		var repository = new JdbcTransitMasterOverrideRepository(brokenDataSource, objectMapper(), meterRegistry);

		assertThatThrownBy(repository::loadAccessibilityFacilities)
			.isInstanceOf(TransitDataAccessException.class);
		assertThat(meterRegistry.counter("transit_master_db_failure_total").count()).isEqualTo(1.0);
	}

	private DataSource databaseProductDataSource(String productName) throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		DatabaseMetaData metadata = mock(DatabaseMetaData.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.getMetaData()).thenReturn(metadata);
		when(metadata.getDatabaseProductName()).thenReturn(productName);
		return dataSource;
	}

	private DataSource overrideDataSource() {
		var dataSource = v14OverrideDataSource();
		new ResourceDatabasePopulator(
			new ClassPathResource("db/migration/h2/V70__transit_master_override_locks.sql")
		)
			.execute(dataSource);
		return dataSource;
	}

	private DataSource v14OverrideDataSource() {
		var dataSource = emptyDataSource();
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V14__transit_master_overrides.sql"))
			.execute(dataSource);
		return dataSource;
	}

	private DataSource emptyDataSource() {
		return new EmbeddedDatabaseBuilder()
			.setType(EmbeddedDatabaseType.H2)
			.generateUniqueName(true)
			.build();
	}

	private ObjectMapper objectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}

	@Test
	@DisplayName("활성 경로 번들이 있으면 전국 역·출구·시설과 PostgreSQL override를 병합하고 상태 갱신을 허용한다")
	void activeBundleExposesNationwideMasterAndSupportsOverrides() {
		Instant t0 = Instant.parse("2026-08-12T00:00:00Z");
		var clock = new MutableClock(t0);
		var registry = new RouteBundleActivationRegistry(clock);
		var catalog = new BundleCatalogRuntime(
			new LinkedHashSet<>(List.of("station-sangnoksu", "station-busan-seomyeon")),
			List.of(
				new RouteBundleFacilityCatalog.BundleFacility(
					"facility-busan-lift-1",
					"station-busan-seomyeon",
					"exit-busan-seomyeon-1",
					"WHEELCHAIR_LIFT",
					"서면역 휠체어 리프트",
					"B2",
					"B1",
					"환승 통로 리프트",
					"NORMAL",
					"OPERATIONAL",
					"OFFICIAL_SOURCE",
					1781568000L,
					95
				),
				new RouteBundleFacilityCatalog.BundleFacility(
					"facility-busan-ramp-1",
					"station-busan-seomyeon",
					null,
					"RAMP",
					"서면역 경사로",
					"",
					"",
					"",
					"UNDER_CONSTRUCTION",
					"UNKNOWN",
					"OPERATOR_CONFIRMED",
					0L,
					70
				),
				new RouteBundleFacilityCatalog.BundleFacility(
					"facility-busan-toilet-1",
					"station-busan-seomyeon",
					null,
					"ACCESSIBLE_TOILET",
					"서면역 장애인 화장실",
					"",
					"",
					"",
					"CLOSED",
					"CLOSED",
					"FIELD_VERIFIED",
					0L,
					30
				),
				new RouteBundleFacilityCatalog.BundleFacility(
					"facility-busan-center-1",
					"station-busan-seomyeon",
					null,
					"CUSTOMER_CENTER",
					"서면역 고객센터",
					"",
					"",
					"",
					"UNKNOWN",
					"UNKNOWN",
					"OFFICIAL_API",
					0L,
					0
				)
			),
			List.of(
				new RouteBundleFacilityCatalog.BundleStationExit(
					"exit-busan-seomyeon-1",
					"station-busan-seomyeon",
					"1",
					"",
					35.157800d,
					129.059100d,
					true,
					"OFFICIAL_API"
				),
				new RouteBundleFacilityCatalog.BundleStationExit(
					"exit-busan-seomyeon-2",
					"station-busan-seomyeon",
					"2",
					"2번 출구 방면",
					null,
					null,
					false,
					"OFFICIAL_FILE"
				)
			)
		);
		String manifestSha = LoadScenarioBundles.manifestSha256("a");
		registry.stage(LoadScenarioBundles.candidate("a", catalog, t0), 0);
		registry.activate(manifestSha, 0);

		DataSource dataSource = overrideDataSource();
		var beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("meterRegistry", new SimpleMeterRegistry());
		beanFactory.addBean("routeBundleActivationRegistry", registry);
		var repository = new JdbcTransitMasterOverrideRepository(
			dataSource,
			objectMapper(),
			beanFactory.getBeanProvider(MeterRegistry.class),
			beanFactory.getBeanProvider(RouteBundleActivationRegistry.class)
		);
		repository.afterPropertiesSet();

		var capability = repository.masterDataCapability();
		assertThat(capability.status()).isEqualTo(MasterDataCapabilityStatus.UP);
		assertThat(capability.writable()).isTrue();
		assertThat(capability.artifactVersion()).isEqualTo("load-bundle-a");
		assertThat(capability.sha256()).isEqualTo(manifestSha);
		assertThat(capability.loadedAt()).isEqualTo(t0);

		var unreadyCapability = new JdbcTransitMasterOverrideRepository(
			emptyDataSource(),
			objectMapper(),
			new SimpleMeterRegistry(),
			() -> registry
		).masterDataCapability();
		assertThat(unreadyCapability.status()).isEqualTo(MasterDataCapabilityStatus.READ_ONLY);
		assertThat(unreadyCapability.artifactVersion()).isEqualTo("load-bundle-a");

		assertThat(repository.loadStation("station-busan-seomyeon")).get().satisfies(station -> {
			assertThat(station.region()).isEqualTo("전국");
			assertThat(station.latitude()).isNull();
			assertThat(station.longitude()).isNull();
			assertThat(station.dataQualityLevel()).isEqualTo(DataQualityLevel.LEVEL_2);
			assertThat(station.lastVerifiedAt()).isEqualTo(LocalDate.of(2026, 8, 12));
		});
		assertThat(repository.loadStation("station-sangnoksu")).get().satisfies(station ->
			assertThat(station.nameKo()).isEqualTo("상록수"));

		assertThat(repository.loadStationExits())
			.filteredOn(exit -> exit.stationId().equals("station-busan-seomyeon"))
			.satisfiesExactly(
				exit1 -> {
					assertThat(exit1.name()).isEqualTo("1번 출구");
					assertThat(exit1.latitude()).isEqualByComparingTo("35.1578");
					assertThat(exit1.hasElevatorConnection()).isTrue();
					assertThat(exit1.dataSourceType()).isEqualTo(DataSourceType.OFFICIAL_API);
				},
				exit2 -> {
					assertThat(exit2.name()).isEqualTo("2번 출구 방면");
					assertThat(exit2.latitude()).isNull();
					assertThat(exit2.dataSourceType()).isEqualTo(DataSourceType.OFFICIAL_FILE);
				}
			);

		assertThat(repository.loadAccessibilityFacility("facility-busan-lift-1")).get().satisfies(facility -> {
			assertThat(facility.type()).isEqualTo(AccessibilityFacilityType.WHEELCHAIR_LIFT);
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.NORMAL);
			assertThat(facility.dataConfidence()).isEqualTo(DataConfidenceLevel.HIGH);
			assertThat(facility.dataSourceType()).isEqualTo(DataSourceType.OFFICIAL_FILE);
			assertThat(facility.floorFrom()).isEqualTo("B2");
			assertThat(facility.floorTo()).isEqualTo("B1");
		});
		assertThat(repository.loadAccessibilityFacility("facility-busan-ramp-1")).get().satisfies(facility -> {
			assertThat(facility.type()).isEqualTo(AccessibilityFacilityType.RAMP);
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.UNDER_CONSTRUCTION);
			assertThat(facility.dataConfidence()).isEqualTo(DataConfidenceLevel.MEDIUM);
			assertThat(facility.dataSourceType()).isEqualTo(DataSourceType.OPERATOR_PAGE);
			assertThat(facility.lastUpdatedAt()).isEqualTo(LocalDate.of(2026, 8, 12));
		});
		assertThat(repository.loadAccessibilityFacility("facility-busan-toilet-1")).get().satisfies(facility -> {
			assertThat(facility.type()).isEqualTo(AccessibilityFacilityType.ACCESSIBLE_TOILET);
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.CLOSED);
			assertThat(facility.dataConfidence()).isEqualTo(DataConfidenceLevel.LOW);
			assertThat(facility.dataSourceType()).isEqualTo(DataSourceType.ADMIN_VERIFIED);
		});
		assertThat(repository.loadAccessibilityFacility("facility-busan-center-1")).get().satisfies(facility -> {
			assertThat(facility.type()).isEqualTo(AccessibilityFacilityType.CUSTOMER_CENTER);
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.UNKNOWN);
			assertThat(facility.dataConfidence()).isEqualTo(DataConfidenceLevel.NEEDS_VERIFICATION);
			assertThat(facility.dataSourceType()).isEqualTo(DataSourceType.OFFICIAL_API);
		});

		repository.saveFacilityStatus(
			"facility-busan-lift-1",
			AccessibilityFacilityStatus.BROKEN,
			LocalDate.of(2026, 8, 13),
			"operator-1"
		);
		assertThat(repository.loadAccessibilityFacility("facility-busan-lift-1")).get().satisfies(facility -> {
			assertThat(facility.status()).isEqualTo(AccessibilityFacilityStatus.BROKEN);
			assertThat(facility.dataSourceType()).isEqualTo(DataSourceType.ADMIN_VERIFIED);
			assertThat(facility.lastUpdatedAt()).isEqualTo(LocalDate.of(2026, 8, 13));
		});
	}

	@Test
	@DisplayName("미적재 번들은 시드로 폴백하고 만료된 번들은 Fail-Closed로 TransitDataAccessException을 던진다")
	void unreadyBundleFallsBackToSeedAndStaleBundleFailsClosed() {
		Instant t0 = Instant.parse("2026-08-12T00:00:00Z");
		var clock = new MutableClock(t0);
		var registry = new RouteBundleActivationRegistry(clock);
		var repository = new JdbcTransitMasterOverrideRepository(
			overrideDataSource(),
			objectMapper(),
			new SimpleMeterRegistry(),
			() -> registry
		);

		assertThat(repository.masterDataCapability().artifactVersion()).isEqualTo("static-seed+overrides");
		assertThat(repository.loadStations()).isNotEmpty();
		assertThat(repository.loadStationExits()).isNotEmpty();
		assertThat(repository.loadAccessibilityFacilities()).isNotEmpty();

		String manifestSha = LoadScenarioBundles.manifestSha256("b");
		registry.stage(
			LoadScenarioBundles.candidate(
				"b",
				new BundleCatalogRuntime(Set.of("station-sangnoksu"), List.of(), List.of()),
				t0
			),
			0
		);
		registry.activate(manifestSha, 0);
		clock.set(t0.plus(Duration.ofDays(30)));

		assertThatThrownBy(repository::masterDataCapability).isInstanceOf(TransitDataAccessException.class);
		assertThatThrownBy(repository::loadStations).isInstanceOf(TransitDataAccessException.class);
		assertThatThrownBy(repository::loadStationExits).isInstanceOf(TransitDataAccessException.class);
		assertThatThrownBy(repository::loadAccessibilityFacilities).isInstanceOf(TransitDataAccessException.class);
	}

	private record BundleCatalogRuntime(
		Set<String> canonicalStationIds,
		List<BundleFacility> bundleFacilities,
		List<BundleStationExit> bundleStationExits
	) implements RouteBundleRuntimeView, RouteBundleFacilityCatalog {

		@Override
		public List<Facility> smrtElevatorFacilities() {
			return List.of();
		}
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
