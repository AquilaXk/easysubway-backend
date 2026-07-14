package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
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
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V50__route_service_identity.sql'");
	}

	private TimetableSeedLoader loader(Resource seed) {
		return loader(seed, false);
	}

	private TimetableSeedLoader loader(Resource seed, boolean includesItx) {
		return new TimetableSeedLoader(
			new JdbcRouteTimetableRepository(dataSource),
			dataSource,
			new DataSourceTransactionManager(dataSource),
			seed,
			includesItx);
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
			racingPort, dataSource, new DataSourceTransactionManager(dataSource), seed, false);
		loser.run(null); // 예외 없이 반환해야 한다.

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_stop_times", Integer.class)).isEqualTo(2);
	}

	@Test
	void refreshesItxAdmissionAfterCompetingItxSeedWon() {
		String freshUntil = OffsetDateTime.now().plusDays(1).toString();
		var racingPort = new com.easysubway.route.application.port.out.LoadRouteTimetablePort() {
			private boolean seeded;

			@Override
			public boolean hasRouteTimetable() {
				if (!seeded) {
					seeded = true;
					loader(itxSeed("ADMITTED", true, freshUntil), true).run(null);
				}
				return true;
			}

			@Override
			public RouteTimetable loadRouteTimetable() {
				return RouteTimetable.empty();
			}
		};
		var loser = new TimetableSeedLoader(
			racingPort,
			dataSource,
			new DataSourceTransactionManager(dataSource),
			itxSeed("ADMITTED", true, freshUntil),
			true
		);

		loser.run(null);

		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM transit_trips WHERE service_class = 'ITX_CHEONGCHUN'", Integer.class)).isOne();
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

	@Test
	void rejectsItxRowsWithoutAdmittedIdentityAndRollsBack() {
		var seed = itxSeed("MISSING", false, "2026-07-21T00:00:00.000Z");

		assertThatThrownBy(() -> loader(seed).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ITX-청춘 timetable seed requires ADMITTED evidence");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_trips", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM route_service_artifact_evidence", Integer.class)).isZero();
	}

	@Test
	void rejectsStaleAdmittedItxIdentityAndRollsBack() {
		assertThatThrownBy(() -> loader(itxSeed("ADMITTED", true, "2000-01-01T00:00:00.000Z")).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("valid freshness");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_trips", Integer.class)).isZero();
	}

	@Test
	void acceptsFreshAdmittedItxIdentity() {
		String freshUntil = OffsetDateTime.now().plusDays(1).toString();
		loader(itxSeed("ADMITTED", true, freshUntil), true).run(null);

		assertThat(jdbc.queryForObject(
			"SELECT service_class FROM transit_trips WHERE id = 'itx-1'", String.class))
			.isEqualTo("ITX_CHEONGCHUN");
		assertThat(jdbc.queryForObject(
			"SELECT canonical_pack_id FROM route_service_artifact_evidence", String.class))
			.isEqualTo("capital");
	}

	@Test
	void rejectsItxSeedWhenIncludesItxConfigurationIsDisabled() {
		assertThatThrownBy(() -> loader(itxSeed(
			"ADMITTED", true, OffsetDateTime.now().plusDays(1).toString()), false).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("includes-itx");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_trips", Integer.class)).isZero();
	}

	@Test
	void rejectsExistingItxWhenIncludesItxConfigurationIsDisabled() {
		loader(itxSeed("ADMITTED", true, OffsetDateTime.now().plusDays(1).toString()), true).run(null);

		assertThatThrownBy(() -> loader(new ClassPathResource("timetable/test-line4-seed.sql"), false).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("includes-itx");
	}

	@Test
	void rejectsIncludesItxConfigurationWhenSeedContainsNoItxRowsAndRollsBack() {
		var subwayOnlySeed = new ClassPathResource("timetable/test-line4-seed.sql");

		assertThatThrownBy(() -> loader(subwayOnlySeed, true).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("includes-itx=true");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_trips", Integer.class)).isZero();
	}

	@Test
	void rejectsExpiredExistingItxIdentityOnRestart() {
		loader(itxSeed("ADMITTED", true, OffsetDateTime.now().plusDays(1).toString()), true).run(null);
		jdbc.update(
			"UPDATE route_service_artifact_evidence SET fresh_until = '2000-01-01T00:00:00.000Z' WHERE service_class = 'ITX_CHEONGCHUN'");

		assertThatThrownBy(() -> loader(itxSeed(
			"ADMITTED", true, OffsetDateTime.now().plusDays(1).toString()), true).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("valid freshness");
	}

	@Test
	void rejectsAdditiveItxSeedWhenSubwayTimetableAlreadyExists() {
		loader(new ClassPathResource("timetable/test-line4-seed.sql")).run(null);

		assertThatThrownBy(() -> loader(itxSeed(
			"ADMITTED", true, OffsetDateTime.now().plusDays(1).toString()), true).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("additive ITX timetable seed is not supported");
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM transit_trips WHERE service_class = 'ITX_CHEONGCHUN'", Integer.class)).isZero();
	}

	@Test
	void doesNotTreatMissingEvidenceOnlySeedAsAdditiveItxTrips() {
		loader(new ClassPathResource("timetable/test-line4-seed.sql")).run(null);

		loader(missingItxEvidenceOnlySeed()).run(null);

		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM transit_trips WHERE service_class = 'ITX_CHEONGCHUN'", Integer.class)).isZero();
	}

	@Test
	void skipsUnavailableExternalSeedResourceWhenTimetableAlreadyExists() {
		loader(new ClassPathResource("timetable/test-line4-seed.sql")).run(null);
		var unavailable = new ByteArrayResource(new byte[0]) {
			@Override
			public InputStream getInputStream() throws IOException {
				throw new IOException("external seed mount unavailable");
			}
		};

		loader(unavailable).run(null);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_stop_times", Integer.class)).isEqualTo(2);
	}

	private Resource itxSeed(String admissionStatus, boolean admissionEligible, String freshUntil) {
		return new ByteArrayResource((
			"INSERT INTO service_calendars (service_id, start_date, end_date, timezone, monday, tuesday, wednesday, thursday, friday, saturday, sunday) VALUES ('itx-weekday','20260714','20260721','Asia/Seoul',TRUE,TRUE,TRUE,TRUE,TRUE,FALSE,FALSE);\n"
			+ "INSERT INTO transit_routes (id, timezone, line_id, route_short_name, route_long_name, direction_name) VALUES ('itx-down','Asia/Seoul','line-54a7b980b7c3','ITX-청춘','','down');\n"
			+ "INSERT INTO route_service_artifact_evidence (service_class, timetable_artifact_id, timetable_artifact_sha256, canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256, admission_status, admission_eligible, fresh_until, source_issue) VALUES ('ITX_CHEONGCHUN','test-only-itx','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','capital','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','"
			+ admissionStatus + "'," + admissionEligible + ",'" + freshUntil + "',2116);\n"
			+ "INSERT INTO transit_trips (id, route_id, service_id, service_pattern, service_class, service_day_start_seconds, trip_headsign, direction_id) VALUES ('itx-1','itx-down','itx-weekday','EXPRESS','ITX_CHEONGCHUN',0,'춘천','down');\n"
			+ "INSERT INTO transit_stop_times (trip_id, stop_sequence, station_id, line_id, pickup_type, drop_off_type, arrival_seconds, departure_seconds) VALUES ('itx-1',1,'station-b819702fa7d9','line-54a7b980b7c3',0,0,28800,28860);\n"
		).getBytes()) {
			@Override
			public String getFilename() {
				return "itx-seed.sql";
			}
		};
	}

	private Resource missingItxEvidenceOnlySeed() {
		return new ByteArrayResource((
			"INSERT INTO route_service_artifact_evidence (service_class, timetable_artifact_id, timetable_artifact_sha256, canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256, admission_status, admission_eligible, fresh_until, source_issue) VALUES ('ITX_CHEONGCHUN','missing-itx','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','capital','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','MISSING',FALSE,NULL,2116);\n"
		).getBytes()) {
			@Override
			public String getFilename() {
				return "missing-itx-evidence-only-seed.sql";
			}
		};
	}
}
