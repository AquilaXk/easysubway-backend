package com.easysubway.journey.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.easysubway.journey.analytics.JourneySearchAggregateRow;
import com.easysubway.journey.analytics.JourneySearchKind;
import com.easysubway.journey.analytics.JourneySearchOutcome;
import com.easysubway.journey.analytics.JourneySearchRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@DisplayName("JDBC Journey V3 검색 기록 저장소")
class JdbcJourneySearchRecordStoreTest {

	private JdbcJourneySearchRecordStore store;
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() throws Exception {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
		jdbc = new JdbcTemplate(dataSource);
		try (var connection = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(connection,
				new ClassPathResource("db/migration/h2/V78__journey_v3_search_records.sql"));
		}
		store = new JdbcJourneySearchRecordStore(dataSource);
	}

	private static JourneySearchRecord record(String day, JourneySearchKind kind, JourneySearchOutcome outcome,
		String engine, String stairFree, List<String> categories) {
		LocalDate date = LocalDate.parse(day);
		return new JourneySearchRecord(UUID.randomUUID().toString(), Instant.parse(day + "T03:00:00Z"), date, kind,
			outcome, outcome == JourneySearchOutcome.FOUND ? 200 : 422, outcome == JourneySearchOutcome.FOUND ? null : "ROUTE_NOT_FOUND",
			engine, "STEP_FREE", categories, stairFree);
	}

	@Test
	@DisplayName("기록을 저장하고 기간 안의 건수를 모든 구분 기준으로 묶어 돌려준다")
	void savesAndAggregatesWithinRange() {
		store.save(record("2026-10-01", JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND,
			"UNKNOWN", "UNDETERMINED", List.of("FASTEST", "STAIR_FREE")));
		store.save(record("2026-10-01", JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND,
			"UNKNOWN", "UNDETERMINED", List.of("FASTEST", "STAIR_FREE")));
		store.save(record("2026-10-02", JourneySearchKind.ARRIVE_BY, JourneySearchOutcome.NO_ROUTE,
			"suite/q/1", "NOT_APPLICABLE", List.of()));
		store.save(record("2026-09-01", JourneySearchKind.LAST_CONNECTION, JourneySearchOutcome.FOUND,
			"UNKNOWN", "UNKNOWN", List.of()));

		List<JourneySearchAggregateRow> rows = store.aggregate(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"));

		assertThat(rows).extracting(JourneySearchAggregateRow::day, JourneySearchAggregateRow::kind,
				JourneySearchAggregateRow::outcome, JourneySearchAggregateRow::engineVersion,
				JourneySearchAggregateRow::stairFreeStatus, JourneySearchAggregateRow::alternativeCategories,
				JourneySearchAggregateRow::count)
			.containsExactlyInAnyOrder(
				tuple(LocalDate.parse("2026-10-01"), JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND,
					"UNKNOWN", "UNDETERMINED", "FASTEST,STAIR_FREE", 2L),
				tuple(LocalDate.parse("2026-10-02"), JourneySearchKind.ARRIVE_BY, JourneySearchOutcome.NO_ROUTE,
					"suite/q/1", "NOT_APPLICABLE", "", 1L));
	}

	@Test
	@DisplayName("개인정보가 될 수 있는 출발·도착역 열을 두지 않는다")
	void storesNoStationColumns() {
		List<String> columns = jdbc.queryForList(
			"SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'journey_v3_search_records'",
			String.class);

		assertThat(columns).isNotEmpty()
			.noneMatch(name -> name.contains("station") || name.contains("origin") || name.contains("destination")
				|| name.contains("user") || name.contains("session") || name.contains("request_id"));
	}

	@Test
	@DisplayName("정의되지 않은 결과 분류·탐색 종류 값은 저장하지 못한다")
	void rejectsUnknownEnumValues() {
		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO journey_v3_search_records (record_id, recorded_at, recorded_on, search_kind, outcome,
				http_status, machine_code, engine_version, mobility_profile, alternative_categories, stair_free_status)
			VALUES ('x', CURRENT_TIMESTAMP, CURRENT_DATE, 'DEPART_AT', 'SUCCESS_MAYBE', 200, NULL, 'UNKNOWN', 'STEP_FREE', '', 'UNKNOWN')
			""")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("기준일보다 이전 날짜의 기록만 지우고 기준일 이후 기록은 남긴다")
	void deletesOnlyRecordsBeforeCutoff() {
		store.save(record("2026-07-01", JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND, "UNKNOWN", "UNKNOWN", List.of()));
		store.save(record("2026-07-02", JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND, "UNKNOWN", "UNKNOWN", List.of()));
		store.save(record("2026-07-03", JourneySearchKind.DEPART_AT, JourneySearchOutcome.FOUND, "UNKNOWN", "UNKNOWN", List.of()));

		int deleted = store.deleteRecordedBefore(LocalDate.parse("2026-07-02"));

		assertThat(deleted).isEqualTo(1);
		assertThat(store.aggregate(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
			.extracting(JourneySearchAggregateRow::day)
			.containsExactlyInAnyOrder(LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-03"));
	}
}
