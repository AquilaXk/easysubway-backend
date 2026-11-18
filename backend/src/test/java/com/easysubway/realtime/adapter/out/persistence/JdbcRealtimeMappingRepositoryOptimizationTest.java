package com.easysubway.realtime.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.realtime.application.RealtimeQuery;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JdbcRealtimeMappingRepository LIKE 이스케이프·LIMIT 1·TTL 캐시 검증")
class JdbcRealtimeMappingRepositoryOptimizationTest {

	private JdbcTemplate jdbcTemplate;
	private AtomicInteger sqlExecutions;
	private AtomicReference<Instant> clockRef;
	private JdbcRealtimeMappingRepository repository;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource raw = new DriverManagerDataSource(
			"jdbc:h2:mem:realtime-mapping-opt;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		JdbcTemplate setup = new JdbcTemplate(raw);
		setup.execute("DROP TABLE IF EXISTS realtime_provider_trip_mappings");
		setup.execute("DROP TABLE IF EXISTS realtime_provider_station_mappings");
		setup.execute("DROP TABLE IF EXISTS realtime_provider_line_mappings");
		setup.execute("""
			CREATE TABLE realtime_provider_line_mappings (
				provider_id VARCHAR(80) NOT NULL,
				provider_line_id VARCHAR(80) NOT NULL,
				line_id VARCHAR(80) NOT NULL,
				provider_line_name VARCHAR(120) NOT NULL,
				supports_arrivals BOOLEAN NOT NULL,
				supports_train_positions BOOLEAN NOT NULL,
				mapping_confidence VARCHAR(40) NOT NULL,
				provider_priority INTEGER NOT NULL,
				coverage_region VARCHAR(80) NOT NULL,
				valid_from TIMESTAMP WITH TIME ZONE,
				valid_until TIMESTAMP WITH TIME ZONE,
				cache_version BIGINT NOT NULL,
				PRIMARY KEY (provider_id, provider_line_id)
			)
			""");
		setup.execute("""
			CREATE TABLE realtime_provider_station_mappings (
				provider_id VARCHAR(80) NOT NULL,
				provider_line_id VARCHAR(80) NOT NULL,
				provider_station_id VARCHAR(80) NOT NULL,
				station_id VARCHAR(120) NOT NULL,
				line_id VARCHAR(80) NOT NULL,
				query_name VARCHAR(120) NOT NULL,
				supports_arrivals BOOLEAN NOT NULL,
				supports_train_positions BOOLEAN NOT NULL,
				mapping_confidence VARCHAR(40) NOT NULL,
				cache_version BIGINT NOT NULL,
				PRIMARY KEY (provider_id, provider_line_id, provider_station_id)
			)
			""");
		setup.execute("""
			CREATE TABLE realtime_provider_trip_mappings (
				provider_id VARCHAR(80) NOT NULL,
				provider_line_id VARCHAR(80) NOT NULL,
				line_id VARCHAR(80) NOT NULL,
				raw_direction VARCHAR(120) NOT NULL,
				canonical_direction VARCHAR(120) NOT NULL,
				raw_destination VARCHAR(120) NOT NULL,
				canonical_destination VARCHAR(120) NOT NULL,
				raw_service_pattern VARCHAR(80) NOT NULL,
				canonical_service_pattern VARCHAR(80) NOT NULL,
				mapping_confidence VARCHAR(40) NOT NULL,
				valid_from TIMESTAMP WITH TIME ZONE,
				valid_until TIMESTAMP WITH TIME ZONE,
				cache_version BIGINT NOT NULL,
				PRIMARY KEY (provider_id, provider_line_id, raw_direction, raw_destination, raw_service_pattern)
			)
			""");

		sqlExecutions = new AtomicInteger();
		DataSource counting = countingDataSource(raw, sqlExecutions);
		jdbcTemplate = new JdbcTemplate(counting);
		clockRef = new AtomicReference<>(Instant.parse("2026-07-01T00:00:00Z"));
		Clock mutableClock = new Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return clockRef.get();
			}
		};
		repository = new JdbcRealtimeMappingRepository(jdbcTemplate, mutableClock);
	}

	@Test
	@DisplayName("LIKE 조회는 %, _, ! 문자를 이스케이프하여 임의 문자열 매칭을 차단한다")
	void escapesLikeWildcardsAndExclamationInQueries() {
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_line_mappings
			VALUES ('p1', 'pl-1', 'metro-lXother', 'lineXother', TRUE, TRUE, 'OFFICIAL', 10, '', NULL, NULL, 1)
			""");
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_station_mappings
			VALUES ('p1', 'pl-1', 'psX1', 'st-esc', 'metro-lXother', 'qXother', TRUE, TRUE, 'OFFICIAL', 1)
			""");

		var wildcardAttempt = repository.findArrivalMapping(
			"p1", new RealtimeQuery("st-esc", "l%", "ps_", "사당", "4호선"));
		assertThat(wildcardAttempt).isEmpty();
		var trainWildcardAttempt = repository.findTrainPositionMapping(
			"p1", new RealtimeQuery("st-esc", "l%", null, "사당", "4호선"));
		assertThat(trainWildcardAttempt).isEmpty();

		jdbcTemplate.update("""
			INSERT INTO realtime_provider_line_mappings
			VALUES ('p1', 'pl-esc', 'metro-l_!exact%', 'line_!exact%', TRUE, TRUE, 'OFFICIAL', 10, '', NULL, NULL, 2)
			""");
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_station_mappings
			VALUES ('p1', 'pl-esc', 'ps_!esc%', 'st-esc', 'metro-l_!exact%', 'q_!exact%', TRUE, TRUE, 'OFFICIAL', 2)
			""");

		var exactMatch = repository.findArrivalMapping(
			"p1", new RealtimeQuery("st-esc", "l_!exact%", "_!esc%", "사당", "4호선"));
		assertThat(exactMatch).isPresent();
		assertThat(exactMatch.get().lineId()).isEqualTo("metro-l_!exact%");

		var trainExactMatch = repository.findTrainPositionMapping(
			"p1", new RealtimeQuery("st-esc", "l_!exact%", "pl-esc", "사당", "4호선"));
		assertThat(trainExactMatch).isPresent();
		assertThat(trainExactMatch.get().lineId()).isEqualTo("metro-l_!exact%");
	}

	@Test
	@DisplayName("다중 일치 행이 있어도 cache_version 내림차순 LIMIT 1로 결정적 단일 행을 반환한다")
	void returnsDeterministicHighestVersionRowWhenMultipleMatchesExist() {
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_line_mappings
			VALUES
				('p1', 'pl-old', 'old-l4', '4호선', TRUE, TRUE, 'OFFICIAL', 10, '', NULL, NULL, 1),
				('p1', 'pl-new', 'new-l4', '4호선', TRUE, TRUE, 'OFFICIAL', 5, '', NULL, NULL, 9)
			""");
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_station_mappings
			VALUES
				('p1', 'pl-old', 'ps-old', 'st-shared', 'old-l4', '사당', TRUE, TRUE, 'OFFICIAL', 1),
				('p1', 'pl-new', 'ps-new', 'st-shared', 'new-l4', '사당', TRUE, TRUE, 'OFFICIAL', 9)
			""");

		var arrival = repository.findArrivalMapping("p1", new RealtimeQuery("st-shared", "l4", null, "사당", "4호선"));
		var trainPos = repository.findTrainPositionMapping("p1", new RealtimeQuery("st-shared", "l4", null, "사당", "4호선"));

		assertThat(arrival).isPresent();
		assertThat(arrival.get().providerLineId()).isEqualTo("pl-new");
		assertThat(arrival.get().cacheVersion()).isEqualTo(9L);
		assertThat(trainPos).isPresent();
		assertThat(trainPos.get().providerLineId()).isEqualTo("pl-new");
		assertThat(trainPos.get().cacheVersion()).isEqualTo(9L);
	}

	@Test
	@DisplayName("30초 TTL 로컬 캐시는 동일 조회 DB 왕복을 줄이고 만료 또는 clearCache 시 재조회한다")
	void cachesLookupsWithinTtlAndReloadsAfterExpiryOrClear() {
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_line_mappings
			VALUES ('p1', 'pl-4', 'line-4', '4호선', TRUE, TRUE, 'OFFICIAL', 10, '', NULL, NULL, 1)
			""");
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_station_mappings
			VALUES ('p1', 'pl-4', 'ps-4', 'station-4', 'line-4', '사당', TRUE, TRUE, 'OFFICIAL', 1)
			""");
		sqlExecutions.set(0);

		var query = new RealtimeQuery("station-4", "line-4", "pl-4", "사당", "4호선");
		assertThat(repository.findArrivalMapping("p1", query)).isPresent();
		int firstQueryCount = sqlExecutions.get();
		assertThat(firstQueryCount).isGreaterThanOrEqualTo(1);

		// TTL 이내 반복 조회는 DB 실행 없음
		assertThat(repository.findArrivalMapping("p1", query)).isPresent();
		assertThat(repository.findArrivalMapping("p1", query)).isPresent();
		assertThat(sqlExecutions.get()).isEqualTo(firstQueryCount);

		// clearCache 후 재조회
		repository.clearCache();
		assertThat(repository.findArrivalMapping("p1", query)).isPresent();
		int afterClearCount = sqlExecutions.get();
		assertThat(afterClearCount).isGreaterThan(firstQueryCount);

		// TTL(30s) 초과 후 만료 재조회
		clockRef.set(clockRef.get().plusSeconds(31));
		assertThat(repository.findArrivalMapping("p1", query)).isPresent();
		assertThat(sqlExecutions.get()).isGreaterThan(afterClearCount);
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
					return Proxy.newProxyInstance(
						JdbcRealtimeMappingRepositoryOptimizationTest.class.getClassLoader(),
						result.getClass().getInterfaces().length > 0
							? result.getClass().getInterfaces()
							: new Class<?>[] { java.sql.PreparedStatement.class },
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
