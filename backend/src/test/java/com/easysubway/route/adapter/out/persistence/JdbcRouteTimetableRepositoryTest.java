package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("JDBC 경로 시간표 저장소")
class JdbcRouteTimetableRepositoryTest {

	private JdbcTemplate jdbcTemplate;
	private JdbcRouteTimetableRepository repository;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:route-timetable;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
			"sa",
			""
		);
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP ALL OBJECTS");
		jdbcTemplate.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V29__canonical_transit_schedule.sql'");
		jdbcTemplate.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V37__transit_feed_info.sql'");
		jdbcTemplate.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V50__route_service_identity.sql'");
		repository = new JdbcRouteTimetableRepository(jdbcTemplate);
	}

	@Test
	@DisplayName("canonical 시간표 테이블을 RouteTimetable port record로 읽는다")
	void loadRouteTimetableMapsCanonicalScheduleTables() {
		insertTimetableRows();

		var timetable = repository.loadRouteTimetable();

		assertThat(timetable.serviceCalendars()).hasSize(1);
		assertThat(timetable.serviceCalendarDates()).hasSize(1);
		assertThat(timetable.transitRoutes()).hasSize(1);
		assertThat(timetable.transitTrips()).hasSize(1);
		assertThat(timetable.transitStopTimes()).hasSize(2);
		assertThat(timetable.transitFrequencies()).hasSize(1);
		assertThat(timetable.transitTrips().getFirst().servicePattern()).isEqualTo("LOCAL");
		assertThat(timetable.transitStopTimes().getFirst().stationId()).isEqualTo("station-sangnoksu");
		assertThat(timetable.transitFrequencies().getFirst().headwaySeconds()).isEqualTo(600);
	}

	@Test
	@DisplayName("시간표 availability는 stop_times 전체를 읽지 않고 판정한다")
	void hasRouteTimetableChecksOnlyTripAndStopTimePresence() {
		assertThat(repository.hasRouteTimetable()).isFalse();

		insertTimetableRows();

		assertThat(repository.hasRouteTimetable()).isTrue();
	}

	@Test
	@DisplayName("만료된 ITX admission은 요청 시점 snapshot과 availability에서 제외한다")
	void excludesExpiredItxRowsAtRequestTime() {
		insertTimetableRows();
		insertItxRows("2000-01-01T00:00:00Z");

		var timetable = repository.loadRouteTimetable();

		assertThat(repository.hasRouteTimetable()).isTrue();
		assertThat(timetable.transitTrips()).extracting("id").containsExactly("trip-seoul-4-0900");
		assertThat(timetable.transitStopTimes()).extracting("tripId").containsOnly("trip-seoul-4-0900");
		assertThat(repository.timetableCacheKey()).isEqualTo("SUBWAY_ONLY");
	}

	@Test
	@DisplayName("ITX admission freshness 상태가 바뀌면 timetable cache key도 바뀐다")
	void changesCacheKeyWhenItxAdmissionExpires() {
		insertItxRows("2999-01-01T00:00:00Z");
		String freshKey = repository.timetableCacheKey();

		jdbcTemplate.update("""
			UPDATE route_service_artifact_evidence
			SET fresh_until = '2000-01-01T00:00:00Z'
			WHERE service_class = 'ITX_CHEONGCHUN'
			""");

		assertThat(freshKey).startsWith("ITX_CHEONGCHUN:");
		assertThat(repository.timetableCacheKey()).isEqualTo("SUBWAY_ONLY");
	}

	@Test
	@DisplayName("동일 freshness에서도 ITX artifact identity가 바뀌면 cache key가 바뀐다")
	void changesCacheKeyWhenItxArtifactIdentityChanges() {
		insertItxRows("2999-01-01T00:00:00Z");
		String firstKey = repository.timetableCacheKey();

		jdbcTemplate.update("""
			UPDATE route_service_artifact_evidence
			SET timetable_artifact_id = 'itx-replacement'
			WHERE service_class = 'ITX_CHEONGCHUN'
			""");

		assertThat(firstKey).contains("itx-test");
		assertThat(repository.timetableCacheKey()).contains("itx-replacement").isNotEqualTo(firstKey);
	}

	@Test
	@DisplayName("transit_feed_info 행이 있으면 feed_end_date를 LocalDate로 매핑한다")
	void loadRouteTimetableMapsFeedEndDateWhenPresent() {
		jdbcTemplate.update("INSERT INTO transit_feed_info (id, feed_end_date) VALUES (1, '20261231')");

		var timetable = repository.loadRouteTimetable();

		assertThat(timetable.feedEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
	}

	@Test
	@DisplayName("transit_feed_info 행이 없으면 feedEndDate는 null이다(dormant 유지)")
	void loadRouteTimetableReturnsNullFeedEndDateWhenAbsent() {
		var timetable = repository.loadRouteTimetable();

		assertThat(timetable.feedEndDate()).isNull();
	}

	@Test
	@DisplayName("transit_feed_info feed_end_date 형식 불량은 planner를 죽이지 않고 null로 방어한다")
	void loadRouteTimetableDefendsAgainstMalformedFeedEndDate() {
		jdbcTemplate.update("INSERT INTO transit_feed_info (id, feed_end_date) VALUES (1, 'BADDATE!')");

		var timetable = repository.loadRouteTimetable();

		assertThat(timetable.feedEndDate()).isNull();
	}

	private void insertTimetableRows() {
		jdbcTemplate.update("""
			INSERT INTO service_calendars (
				service_id, start_date, end_date, timezone,
				monday, tuesday, wednesday, thursday, friday, saturday, sunday
			) VALUES ('weekday-2026', '20260701', '20261231', 'Asia/Seoul', TRUE, TRUE, TRUE, TRUE, TRUE, FALSE, FALSE)
			""");
		jdbcTemplate.update("""
			INSERT INTO service_calendar_dates (service_id, date, exception_type)
			VALUES ('weekday-2026', '20261003', 2)
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_routes (id, timezone, line_id, route_short_name, route_long_name, direction_name)
			VALUES ('route-seoul-4-oido', 'Asia/Seoul', 'seoul-4', '4', '상록수-사당', '사당 방면')
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_trips (
				id, route_id, service_id, service_pattern,
				service_day_start_seconds, trip_headsign, direction_id
			) VALUES ('trip-seoul-4-0900', 'route-seoul-4-oido', 'weekday-2026', 'LOCAL', 0, '사당', 'down')
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_stop_times (
				trip_id, stop_sequence, station_id, line_id,
				pickup_type, drop_off_type, arrival_seconds, departure_seconds
			) VALUES
				('trip-seoul-4-0900', 1, 'station-sangnoksu', 'seoul-4', 0, 0, 32400, 32400),
				('trip-seoul-4-0900', 2, 'station-sadang', 'seoul-4', 0, 0, 33000, 33000)
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_frequencies (trip_id, start_time_seconds, headway_seconds, end_time_seconds, exact_times)
			VALUES ('trip-seoul-4-0900', 32400, 600, 36000, FALSE)
			""");
	}

	private void insertItxRows(String freshUntil) {
		jdbcTemplate.update("""
			INSERT INTO service_calendars (
				service_id, start_date, end_date, timezone,
				monday, tuesday, wednesday, thursday, friday, saturday, sunday
			) VALUES ('itx-weekday', '20260701', '20991231', 'Asia/Seoul', TRUE, TRUE, TRUE, TRUE, TRUE, FALSE, FALSE)
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_routes (id, timezone, line_id, route_short_name, route_long_name, direction_name)
			VALUES ('route-itx', 'Asia/Seoul', 'line-k2', 'ITX-청춘', '청량리-춘천', '춘천 방면')
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_trips (
				id, route_id, service_id, service_pattern, service_class,
				service_day_start_seconds, trip_headsign, direction_id
			) VALUES ('trip-itx', 'route-itx', 'itx-weekday', 'EXPRESS', 'ITX_CHEONGCHUN', 0, '춘천', 'down')
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_stop_times (
				trip_id, stop_sequence, station_id, line_id,
				pickup_type, drop_off_type, arrival_seconds, departure_seconds
			) VALUES
				('trip-itx', 1, 'station-cheongnyangni', 'line-k2', 0, 0, 32400, 32400),
				('trip-itx', 2, 'station-chuncheon', 'line-k2', 0, 0, 36000, 36000)
			""");
		jdbcTemplate.update("""
			INSERT INTO route_service_artifact_evidence (
				service_class, timetable_artifact_id, timetable_artifact_sha256,
				canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256,
				admission_status, admission_eligible, fresh_until, source_issue
			) VALUES ('ITX_CHEONGCHUN', 'itx-test', ?, 'capital', ?, ?, 'ADMITTED', TRUE, ?, 2116)
			""", "a".repeat(64), "b".repeat(64), "c".repeat(64), freshUntil);
	}
}
