package com.easysubway.realtime.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.easysubway.realtime.application.RealtimeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 실시간 provider mapping 저장소")
class JdbcRealtimeMappingRepositoryTest {

	private JdbcRealtimeMappingRepository repository;
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:realtime-mapping;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS realtime_provider_station_mappings");
		jdbcTemplate.execute("DROP TABLE IF EXISTS realtime_provider_line_mappings");
		jdbcTemplate.execute("""
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
				valid_from TIMESTAMP,
				valid_until TIMESTAMP,
				cache_version BIGINT NOT NULL,
				PRIMARY KEY (provider_id, provider_line_id),
				UNIQUE (provider_id, line_id)
			)
			""");
		jdbcTemplate.execute("""
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
				PRIMARY KEY (provider_id, provider_line_id, provider_station_id),
				UNIQUE (provider_id, line_id, station_id)
			)
			""");
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_line_mappings (
				provider_id, provider_line_id, line_id, provider_line_name,
				supports_arrivals, supports_train_positions, mapping_confidence,
				provider_priority, coverage_region, valid_from, valid_until, cache_version
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)
			""", "seoul-topis", "1004", "seoul-4", "4호선", true, true, "OFFICIAL", 10, "capital", 7L);
		jdbcTemplate.update("""
			INSERT INTO realtime_provider_station_mappings (
				provider_id, provider_line_id, provider_station_id, station_id, line_id, query_name,
				supports_arrivals, supports_train_positions, mapping_confidence, cache_version
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", "seoul-topis", "1004", "1004000448", "station-sangnoksu", "seoul-4", "상록수", true, true, "OFFICIAL", 8L);
		repository = new JdbcRealtimeMappingRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("도착 mapping은 station-line query로 provider alias와 cache version을 조회한다")
	void findArrivalMappingByStationLine() {
		var mapping = repository.findArrivalMapping(
			"seoul-topis",
			new RealtimeQuery("station-sangnoksu", "seoul-4", null, "상록수역", null)
		);

		assertThat(mapping).hasValueSatisfying((value) -> {
			assertThat(value.providerLineId()).isEqualTo("1004");
			assertThat(value.providerStationId()).isEqualTo("1004000448");
			assertThat(value.queryName()).isEqualTo("상록수");
			assertThat(value.providerLineName()).isEqualTo("4호선");
			assertThat(value.cacheVersion()).isEqualTo(8L);
		});
	}

	@Test
	@DisplayName("도착 mapping은 providerLineId가 불일치하면 조회하지 않는다")
	void findArrivalMappingRejectsProviderLineMismatch() {
		var mapping = repository.findArrivalMapping(
			"seoul-topis",
			new RealtimeQuery("station-sangnoksu", "seoul-4", "9999", "상록수", null)
		);

		assertThat(mapping).isEmpty();
	}

	@Test
	@DisplayName("열차 위치 mapping은 line query로 provider line alias와 cache version을 조회한다")
	void findTrainPositionMappingByLine() {
		var mapping = repository.findTrainPositionMapping(
			"seoul-topis",
			new RealtimeQuery(null, "seoul-4", null, null, "4호선")
		);

		assertThat(mapping).hasValueSatisfying((value) -> {
			assertThat(value.providerLineId()).isEqualTo("1004");
			assertThat(value.providerLineName()).isEqualTo("4호선");
			assertThat(value.cacheVersion()).isEqualTo(7L);
		});
	}

	@Test
	@DisplayName("valid_until이 지난 mapping은 조회하지 않는다")
	void expiredMappingIsIgnored() {
		jdbcTemplate.update("""
			UPDATE realtime_provider_line_mappings
			SET valid_until = TIMESTAMP '2026-06-25 00:00:00'
			WHERE provider_id = 'seoul-topis'
			""");

		var mapping = repository.findArrivalMapping(
			"seoul-topis",
			new RealtimeQuery("station-sangnoksu", "seoul-4", null, "상록수", null)
		);

		assertThat(mapping).isEmpty();
	}

	@Test
	@DisplayName("도착 mapping은 line mapping confidence가 낮으면 live eligible이 아니다")
	void lowConfidenceLineMarksArrivalMappingIneligible() {
		jdbcTemplate.update("""
			UPDATE realtime_provider_line_mappings
			SET mapping_confidence = 'HEURISTIC'
			WHERE provider_id = 'seoul-topis'
			""");

		var mapping = repository.findArrivalMapping(
			"seoul-topis",
			new RealtimeQuery("station-sangnoksu", "seoul-4", null, "상록수", null)
		);

		assertThat(mapping).hasValueSatisfying((value) -> {
			assertThat(value.liveEligible()).isFalse();
			assertThat(value.ineligibleReason()).isEqualTo("MAPPING_LOW_CONFIDENCE");
		});
	}
}
