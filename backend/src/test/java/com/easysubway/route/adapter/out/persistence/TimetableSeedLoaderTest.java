package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TimetableSeedLoaderTest {

	private DriverManagerDataSource dataSource;
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:seed-loader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP ALL OBJECTS");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V29__canonical_transit_schedule.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V37__transit_feed_info.sql'");
	}

	private TimetableSeedLoader loader(Resource seed) {
		return new TimetableSeedLoader(
			new JdbcRouteTimetableRepository(dataSource),
			dataSource,
			new DataSourceTransactionManager(dataSource),
			seed);
	}

	@Test
	void seedsWhenEmptyThenSkipsWhenPresent() {
		var seed = new ClassPathResource("timetable/test-line4-seed.sql");
		loader(seed).run(null);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_stop_times", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT feed_end_date FROM transit_feed_info", String.class)).isEqualTo("20261231");

		// 멱등: hasRouteTimetable()==true → skip, 중복 없음.
		loader(seed).run(null);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_stop_times", Integer.class)).isEqualTo(2);
	}

	@Test
	void loadsGzippedResource() {
		loader(new ClassPathResource("timetable/test-line4-seed.sql.gz")).run(null);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_stop_times", Integer.class)).isEqualTo(2);
	}

	@Test
	void toleratesConcurrentSeedWhenAnotherInstanceWon() {
		var seed = new ClassPathResource("timetable/test-line4-seed.sql");
		// 다른 replica가 먼저 적재한 상태를 만든다.
		loader(seed).run(null);

		// 경쟁 loser 시뮬: 사전체크는 empty(false)로 보지만 재확인 시 true → 배치는 PK 충돌 → 관용 처리(예외 없음).
		var racingPort = new com.easysubway.route.application.port.out.LoadRouteTimetablePort() {
			private int calls = 0;

			@Override
			public boolean hasRouteTimetable() {
				return calls++ > 0;
			}

			@Override
			public RouteTimetable loadRouteTimetable() {
				return RouteTimetable.empty();
			}
		};
		var loser = new TimetableSeedLoader(
			racingPort, dataSource, new DataSourceTransactionManager(dataSource), seed);
		loser.run(null); // 예외 없이 반환해야 한다.

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_stop_times", Integer.class)).isEqualTo(2);
	}

	@Test
	void rollsBackOnMidScriptFailureLeavingNoPartialData() {
		// trips 성공 후 stop_times FK 위반으로 실패 → all-or-nothing 롤백(부분 적재 없음).
		var bad = new ByteArrayResource((
			"INSERT INTO transit_routes (id, timezone, line_id, route_short_name, route_long_name, direction_name) VALUES ('r1','Asia/Seoul','seoul-4','','','down');\n"
			+ "INSERT INTO service_calendars (service_id, start_date, end_date, timezone, monday, tuesday, wednesday, thursday, friday, saturday, sunday) VALUES ('weekday-kric','20260101','20261231','Asia/Seoul',TRUE,TRUE,TRUE,TRUE,TRUE,FALSE,FALSE);\n"
			+ "INSERT INTO transit_trips (id, route_id, service_id, service_pattern, service_day_start_seconds, trip_headsign, direction_id) VALUES ('t1','r1','weekday-kric','LOCAL',0,'','down');\n"
			+ "INSERT INTO transit_stop_times (trip_id, stop_sequence, station_id, line_id, pickup_type, drop_off_type, arrival_seconds, departure_seconds) VALUES ('MISSING',1,'s','seoul-4',0,0,1,1);\n"
		).getBytes()) {
			@Override
			public String getFilename() {
				return "bad-seed.sql";
			}
		};
		assertThatThrownBy(() -> loader(bad).run(null)).isInstanceOf(RuntimeException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_trips", Integer.class)).isZero();
	}
}
