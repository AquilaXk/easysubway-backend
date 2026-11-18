package com.easysubway.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.collection.adapter.out.persistence.JdbcDataCollectionRunRepository;
import com.easysubway.collection.domain.DataCollectionRun;
import com.easysubway.collection.domain.DataCollectionRunStep;
import com.easysubway.collection.domain.DataCollectionSource;
import com.easysubway.collection.domain.DataCollectionStatus;
import com.easysubway.collection.domain.DataCollectionStepStatus;
import com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteRouteRepository;
import com.easysubway.favorite.domain.FavoriteRoute;
import com.easysubway.field.adapter.out.persistence.JdbcFieldVerificationSessionRepository;
import com.easysubway.field.domain.FieldVerificationItem;
import com.easysubway.field.domain.FieldVerificationItemType;
import com.easysubway.field.domain.FieldVerificationSession;
import com.easysubway.field.domain.FieldVerificationStatus;
import com.easysubway.profile.domain.MobilityType;
import com.easysubway.route.domain.RouteSearchResult;
import com.easysubway.route.domain.RouteSearchStatus;
import com.easysubway.route.domain.RouteStep;
import com.easysubway.train.application.TrainSearchCache;
import com.easysubway.train.application.TrainSearchProvider;
import com.easysubway.train.application.TrainSearchService;
import com.easysubway.train.domain.TrainSearchModels.Journey;
import com.easysubway.train.domain.TrainSearchModels.LegQuery;
import com.easysubway.train.domain.TrainSearchModels.Station;
import com.easysubway.train.domain.TrainSearchModels.TrainType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("Wave 2B N+1 배치 조회 및 TrainSearchService 색인 캐시 검증")
class Wave2bBatchLoadingAndCacheTest {

	@Test
	@DisplayName("JdbcDataCollectionRunRepository는 다건 run 조회 시 steps를 IN 배치 1회로 읽어 N+1 쿼리를 없앤다")
	void dataCollectionRunLoadsStepsWithoutNPlusOne() {
		DriverManagerDataSource raw = new DriverManagerDataSource(
			"jdbc:h2:mem:wave2b-collection-batch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa", ""
		);
		JdbcTemplate setup = new JdbcTemplate(raw);
		setup.execute("DROP TABLE IF EXISTS data_collection_run_steps");
		setup.execute("DROP TABLE IF EXISTS data_collection_runs");
		setup.execute("""
			CREATE TABLE data_collection_runs (
				run_id VARCHAR(80) PRIMARY KEY,
				source VARCHAR(40) NOT NULL,
				status VARCHAR(20) NOT NULL,
				requested_by VARCHAR(120) NOT NULL,
				started_at TIMESTAMP NOT NULL,
				completed_at TIMESTAMP NULL,
				collected_count INTEGER NOT NULL,
				failure_message VARCHAR(1000) NULL,
				retryable BOOLEAN NOT NULL,
				operator_action VARCHAR(500) NOT NULL,
				active_source VARCHAR(40) NULL
			)
			""");
		setup.execute("""
			CREATE TABLE data_collection_run_steps (
				run_id VARCHAR(80) NOT NULL,
				step_order INTEGER NOT NULL,
				step_name VARCHAR(40) NOT NULL,
				status VARCHAR(30) NOT NULL,
				input_source VARCHAR(1000),
				artifact_reference VARCHAR(1000),
				checksum VARCHAR(64),
				record_count INTEGER NOT NULL,
				failure_message VARCHAR(1000),
				PRIMARY KEY (run_id, step_order)
			)
			""");
		AtomicInteger sqlCount = new AtomicInteger();
		var repository = new JdbcDataCollectionRunRepository(countingDataSource(raw, sqlCount));

		LocalDateTime base = LocalDateTime.of(2026, 7, 1, 9, 0);
		for (int i = 0; i < 25; i++) {
			List<DataCollectionRunStep> steps = List.of(
				new DataCollectionRunStep("step-a", DataCollectionStepStatus.COMPLETED, "src", "art", null, 10, null),
				new DataCollectionRunStep("step-b", DataCollectionStepStatus.COMPLETED, "src", "art", null, 20, null),
				new DataCollectionRunStep("step-c", DataCollectionStepStatus.COMPLETED, "src", "art", null, 30, null)
			);
			repository.saveRun(new DataCollectionRun(
				"run-" + i,
				DataCollectionSource.TRANSIT_MASTER,
				DataCollectionStatus.COMPLETED,
				"admin",
				base.plusMinutes(i),
				base.plusMinutes(i + 1),
				60,
				null,
				false,
				"none",
				steps
			));
		}

		sqlCount.set(0);
		List<DataCollectionRun> loaded = repository.loadRecentRuns(25);
		assertThat(loaded).hasSize(25);
		assertThat(loaded).allSatisfy(run -> assertThat(run.steps()).hasSize(3));
		assertThat(sqlCount.get()).isLessThanOrEqualTo(2);
	}

	@Test
	@DisplayName("JdbcFieldVerificationSessionRepository는 다건 세션 listAll 시 items를 IN 배치 1회로 읽어 N+1을 없앤다")
	void fieldVerificationSessionLoadsItemsWithoutNPlusOne() {
		DriverManagerDataSource raw = new DriverManagerDataSource(
			"jdbc:h2:mem:wave2b-field-batch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa", ""
		);
		JdbcTemplate setup = new JdbcTemplate(raw);
		setup.execute("DROP TABLE IF EXISTS field_verification_items");
		setup.execute("DROP TABLE IF EXISTS field_verification_sessions");
		setup.execute("""
			CREATE TABLE field_verification_sessions (
				session_id VARCHAR(120) PRIMARY KEY,
				station_id VARCHAR(120) NOT NULL,
				station_name VARCHAR(120) NOT NULL,
				verified_at DATE NOT NULL,
				verified_by VARCHAR(120) NOT NULL,
				status VARCHAR(40) NOT NULL,
				note VARCHAR(1000)
			)
			""");
		setup.execute("""
			CREATE TABLE field_verification_items (
				item_id VARCHAR(120) PRIMARY KEY,
				session_id VARCHAR(120) NOT NULL,
				item_type VARCHAR(40) NOT NULL,
				target_name VARCHAR(200) NOT NULL,
				status VARCHAR(40) NOT NULL,
				note VARCHAR(1000)
			)
			""");
		AtomicInteger sqlCount = new AtomicInteger();
		var repository = new JdbcFieldVerificationSessionRepository(countingDataSource(raw, sqlCount));

		for (int i = 0; i < 25; i++) {
			repository.save(new FieldVerificationSession(
				"sess-" + i,
				"station-" + i,
				"역" + i,
				LocalDate.of(2026, 7, 1).plusDays(i % 10),
				"inspector",
				FieldVerificationStatus.VERIFIED,
				"note",
				List.of(
					new FieldVerificationItem("item-" + i + "-1", FieldVerificationItemType.ELEVATOR, "E1", FieldVerificationStatus.VERIFIED, null),
					new FieldVerificationItem("item-" + i + "-2", FieldVerificationItemType.RESTROOM, "R1", FieldVerificationStatus.VERIFIED, null)
				)
			));
		}

		sqlCount.set(0);
		List<FieldVerificationSession> sessions = repository.listAll();
		assertThat(sessions).hasSize(25);
		assertThat(sessions).allSatisfy(s -> assertThat(s.items()).hasSize(2));
		assertThat(sqlCount.get()).isLessThanOrEqualTo(2);
	}

	@Test
	@DisplayName("JdbcFavoriteRouteRepository는 20개 역 포함 경로 저장 시 batchUpdate로 O(1) JDBC 호출만 실행한다")
	void favoriteRouteUsesBatchInsertForStations() {
		DriverManagerDataSource raw = new DriverManagerDataSource(
			"jdbc:h2:mem:wave2b-favorite-batch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa", ""
		);
		JdbcTemplate setup = new JdbcTemplate(raw);
		setup.execute("DROP TABLE IF EXISTS favorite_route_stations");
		setup.execute("DROP TABLE IF EXISTS favorite_routes");
		setup.execute("""
			CREATE TABLE favorite_routes (
				user_id VARCHAR(120) NOT NULL,
				route_search_id VARCHAR(120) NOT NULL,
				origin_station_id VARCHAR(120) NOT NULL,
				origin_station_name VARCHAR(120) NOT NULL,
				destination_station_id VARCHAR(120) NOT NULL,
				destination_station_name VARCHAR(120) NOT NULL,
				mobility_type VARCHAR(40) NOT NULL,
				status VARCHAR(40) NOT NULL,
				line_id VARCHAR(120) NOT NULL,
				line_name VARCHAR(120) NOT NULL,
				score INTEGER NOT NULL,
				steps_json TEXT NOT NULL,
				warnings_json TEXT NOT NULL,
				blocked_reasons_json TEXT NOT NULL,
				route_created_at TIMESTAMP NOT NULL,
				added_at TIMESTAMP NOT NULL,
				PRIMARY KEY (user_id, route_search_id)
			)
			""");
		setup.execute("""
			CREATE TABLE favorite_route_stations (
				user_id VARCHAR(120) NOT NULL,
				route_search_id VARCHAR(120) NOT NULL,
				station_id VARCHAR(120) NOT NULL,
				PRIMARY KEY (user_id, route_search_id, station_id)
			)
			""");
		AtomicInteger sqlCount = new AtomicInteger();
		var repository = new JdbcFavoriteRouteRepository(
			countingDataSource(raw, sqlCount), new ObjectMapper().registerModule(new JavaTimeModule()));

		List<RouteStep> steps = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			steps.add(new RouteStep(
				i + 1, "이동", "설명", "L" + i, "노선" + i,
				"st-" + i, "st-" + (i + 1), 2, 100, false, false));
		}
		var route = new RouteSearchResult(
			"fav-rs-1", "st-0", "출발", "st-20", "도착", MobilityType.WHEELCHAIR,
			RouteSearchStatus.FOUND, "L0", "노선0", 90, steps, List.of(), List.of(),
			LocalDateTime.of(2026, 7, 1, 9, 0));

		sqlCount.set(0);
		repository.saveFavoriteRoute(new FavoriteRoute("user-1", route, LocalDateTime.of(2026, 7, 1, 9, 5)));
		assertThat(sqlCount.get()).isLessThanOrEqualTo(4);
		assertThat(repository.loadUserIdsByRouteStationId("st-15")).containsExactly("user-1");
	}

	@Test
	@DisplayName("TrainSearchService는 동일 catalog 반복 검색 시 사전 색인을 재사용하면서도 freshCatalog와 대소문자 무시 정렬을 유지한다")
	void trainSearchServiceReusesIndexedCatalogAndMatchesCaseInsensitively() throws Exception {
		ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
		Instant now = Instant.now();
		var catalogPayload = MapCatalog.of(
			List.of(
				new Station("s-3", "Seoul Station"),
				new Station("s-1", "Seodaemun"),
				new Station("s-2", "Seongsu")
			),
			List.of(new TrainType("KTX", "KTX", List.of("00")))
		);
		String json = mapper.writeValueAsString(catalogPayload);
		String sha = sha256Hex(json);
		TrainSearchCache.CachedCatalog cachedCatalog = new TrainSearchCache.CachedCatalog(
			"catalog", json, sha, now, now.plusSeconds(3600));

		AtomicInteger freshCatalogCalls = new AtomicInteger();
		TrainSearchCache cache = new TrainSearchCache() {
			@Override
			public Optional<CachedCatalog> freshCatalog(String kind, Instant at) {
				freshCatalogCalls.incrementAndGet();
				return Optional.of(cachedCatalog);
			}

			@Override
			public void replaceCatalog(List<CachedCatalog> catalogs) {}

			@Override
			public Optional<CachedLeg> freshLeg(String key, Instant at) {
				return Optional.empty();
			}

			@Override
			public boolean tryAcquireLease(String key, String owner, Instant at, Duration ttl) {
				return false;
			}

			@Override
			public void releaseLease(String key, String owner) {}

			@Override
			public boolean storeLegAndRelease(String key, String owner, CachedLeg leg) {
				return false;
			}

			@Override
			public int purgeExpiredBefore(Instant cutoff) {
				return 0;
			}
		};
		TrainSearchProvider provider = new TrainSearchProvider() {
			@Override
			public Catalog catalog() {
				throw new AssertionError("should not call provider when cache is fresh");
			}

			@Override
			public List<Journey> search(LegQuery query) {
				return List.of();
			}
		};
		TrainSearchService service = new TrainSearchService(provider, cache, mapper);

		List<Station> first = service.stations("seo", "KTX");
		List<Station> second = service.stations("SEO", "KTX");

		assertThat(first).extracting(Station::name).containsExactly("Seodaemun", "Seongsu", "Seoul Station");
		assertThat(second).isEqualTo(first);
		assertThat(freshCatalogCalls.get()).isEqualTo(2);
	}

	private record MapCatalog(List<Station> stations, List<TrainType> trainTypes) {
		static MapCatalog of(List<Station> stations, List<TrainType> trainTypes) {
			return new MapCatalog(stations, trainTypes);
		}
	}

	private static String sha256Hex(String input) throws Exception {
		byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
			.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	private static DataSource countingDataSource(DataSource delegate, AtomicInteger counter) {
		return (DataSource) Proxy.newProxyInstance(
			DataSource.class.getClassLoader(),
			new Class<?>[] { DataSource.class },
			(proxy, method, args) -> {
				if ("getConnection".equals(method.getName())) {
					Connection conn = (Connection) method.invoke(delegate, args);
					return wrapConnection(conn, counter);
				}
				return method.invoke(delegate, args);
			}
		);
	}

	private static Connection wrapConnection(Connection delegate, AtomicInteger counter) {
		return (Connection) Proxy.newProxyInstance(
			Connection.class.getClassLoader(),
			new Class<?>[] { Connection.class },
			(proxy, method, args) -> {
				Object result = method.invoke(delegate, args);
				if (("prepareStatement".equals(method.getName()) || "createStatement".equals(method.getName()))
					&& result != null) {
					Class<?>[] ifaces = result instanceof PreparedStatement
						? new Class<?>[] { PreparedStatement.class }
						: new Class<?>[] { Statement.class };
					return Proxy.newProxyInstance(
						Wave2bBatchLoadingAndCacheTest.class.getClassLoader(),
						ifaces,
						(sProxy, sMethod, sArgs) -> {
							if (sMethod.getName().startsWith("execute")) {
								counter.incrementAndGet();
							}
							return sMethod.invoke(result, sArgs);
						}
					);
				}
				return result;
			}
		);
	}
}
