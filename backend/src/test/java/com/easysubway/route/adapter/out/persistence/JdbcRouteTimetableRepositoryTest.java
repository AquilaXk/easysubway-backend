package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
}
