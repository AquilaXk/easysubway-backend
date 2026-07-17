package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
		jdbcTemplate.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V61__timetable_snapshot_state.sql'");
		jdbcTemplate.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V62__route_v2_planner_identity.sql'");
		repository = new JdbcRouteTimetableRepository(
			jdbcTemplate,
			Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC)
		);
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
		insertItxRows("2026-07-20T00:00:00+09:00");

		assertThat(repository.hasRouteTimetable()).isTrue();
	}

	@Test
	@DisplayName("만료된 active snapshot은 런타임에서 fail closed한다")
	void rejectsExpiredActiveSnapshotAtRequestTime() {
		insertTimetableRows();
		insertItxRows("2000-01-01T00:00:00Z");

		assertThat(repository.hasRouteTimetable()).isFalse();
		assertThat(repository.activeItxTimetableArtifactId()).isEmpty();
		assertThat(repository.timetableCacheKey()).isEqualTo("UNAVAILABLE");
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

		assertThat(freshKey).isEqualTo("a".repeat(64) + "2999-01-01T00:00:00Z");
		assertThat(repository.timetableCacheKey()).isEqualTo("UNAVAILABLE");
	}

	@Test
	@DisplayName("동일 freshness에서도 active snapshot SHA가 바뀌면 cache key가 바뀐다")
	void changesCacheKeyWhenActiveSnapshotShaChanges() {
		insertItxRows("2999-01-01T00:00:00Z");
		String firstKey = repository.timetableCacheKey();

		insertSnapshotHistory("f".repeat(64), "snapshot-replacement", "2999-01-01T00:00:00Z");
		jdbcTemplate.update(
			"UPDATE timetable_snapshot_active SET snapshot_sha256 = ? WHERE singleton_id = 1",
			"f".repeat(64)
		);

		assertThat(firstKey).isEqualTo("a".repeat(64) + "2999-01-01T00:00:00Z");
		assertThat(repository.timetableCacheKey())
			.isEqualTo("f".repeat(64) + "2999-01-01T00:00:00Z")
			.isNotEqualTo(firstKey);
	}

	@Test
	@DisplayName("활성 snapshot의 EXPRESS pattern과 통과역 승하차 금지를 그대로 읽는다")
	void preservesExpressPatternAndPassThroughRestrictions() {
		insertItxRows("2999-01-01T00:00:00Z");

		var timetable = repository.loadRouteTimetable();
		var itxTrip = timetable.transitTrips().stream()
			.filter(trip -> trip.id().equals("trip-itx"))
			.findFirst()
			.orElseThrow();
		var passThrough = timetable.transitStopTimes().stream()
			.filter(stop -> stop.stationId().equals("station-gapyeong-pass"))
			.findFirst()
			.orElseThrow();

		assertThat(itxTrip.servicePattern()).isEqualTo("EXPRESS");
		assertThat(itxTrip.serviceClass()).isEqualTo("ITX_CHEONGCHUN");
		assertThat(itxTrip.trainNo()).isEqualTo("2001");
		assertThat(timetable.officialFares()).singleElement().satisfies(fare -> {
			assertThat(fare.tripId()).isEqualTo("trip-itx");
			assertThat(fare.originStationId()).isEqualTo("station-cheongnyangni");
			assertThat(fare.destinationStationId()).isEqualTo("station-chuncheon");
			assertThat(fare.adultFareWon()).isEqualTo(9_800);
			assertThat(fare.currency()).isEqualTo("KRW");
			assertThat(fare.sourceId()).isEqualTo("tago-train-schedule-fares");
		});
		assertThat(passThrough.pickupType()).isOne();
		assertThat(passThrough.dropOffType()).isOne();
	}

	@Test
	@DisplayName("active snapshot identity와 시간표 row를 하나의 조회 값으로 반환한다")
	void loadsActiveSnapshotIdentityAndTimetableTogether() {
		insertTimetableRows();
		insertItxRows("2999-01-01T00:00:00Z");

		var snapshot = repository.loadRouteTimetableSnapshot();

		assertThat(snapshot.cacheKey()).isEqualTo("a".repeat(64) + "2999-01-01T00:00:00Z");
		assertThat(snapshot.timetableArtifactId()).isEqualTo("snapshot-test");
		assertThat(snapshot.plannerIdentity()).satisfies(identity -> {
			assertThat(identity.timetableSnapshotSha256()).isEqualTo("a".repeat(64));
			assertThat(identity.canonicalPackSha256()).isEqualTo("b".repeat(64));
			assertThat(identity.canonicalPackSqliteSha256()).isEqualTo("c".repeat(64));
			assertThat(identity.canonicalStationVersion()).isEqualTo("sha256:" + "e".repeat(64));
			assertThat(identity.canonicalStationSetSha256()).isEqualTo("e".repeat(64));
			assertThat(identity.sourceLineageSha256()).isEqualTo("f".repeat(64));
			assertThat(identity.evidenceHash()).isEqualTo("1".repeat(64));
		});
		assertThat(snapshot.timetable().transitTrips()).extracting("id")
			.contains("trip-seoul-4-0900", "trip-itx");
	}

	@Test
	@DisplayName("missing·schema-invalid·lineage mismatch active snapshot은 모두 fail closed한다")
	void rejectsMissingInvalidSchemaAndLineageMismatch() {
		insertItxRows("2999-01-01T00:00:00Z");
		assertThat(repository.activeItxTimetableArtifactId()).contains("snapshot-test");

		jdbcTemplate.update("UPDATE timetable_snapshot_history SET schema_identity = 'invalid' WHERE snapshot_sha256 = ?", "a".repeat(64));
		assertThat(repository.activeItxTimetableArtifactId()).isEmpty();

		jdbcTemplate.update("UPDATE timetable_snapshot_history SET schema_identity = 'backend-timetable-snapshot-v1' WHERE snapshot_sha256 = ?", "a".repeat(64));
		jdbcTemplate.update("UPDATE route_service_artifact_evidence SET canonical_pack_sha256 = ? WHERE service_class = 'ITX_CHEONGCHUN'", "9".repeat(64));
		assertThat(repository.activeItxTimetableArtifactId()).isEmpty();

		jdbcTemplate.update("DELETE FROM timetable_snapshot_active");
		assertThat(repository.activeItxTimetableArtifactId()).isEmpty();
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
		jdbcTemplate.update("UPDATE transit_trips SET train_no = '2001' WHERE id = 'trip-itx'");
		jdbcTemplate.update("""
			INSERT INTO transit_stop_times (
				trip_id, stop_sequence, station_id, line_id,
				pickup_type, drop_off_type, arrival_seconds, departure_seconds
			) VALUES
				('trip-itx', 1, 'station-cheongnyangni', 'line-k2', 0, 0, 32400, 32400),
				('trip-itx', 2, 'station-gapyeong-pass', 'line-k2', 1, 1, 34200, 34200),
				('trip-itx', 3, 'station-chuncheon', 'line-k2', 0, 0, 36000, 36000)
			""");
		jdbcTemplate.update("""
			INSERT INTO transit_trip_official_fares (
				trip_id, origin_station_id, destination_station_id, adult_fare_won,
				currency, source_id, source_snapshot_id
			) VALUES (
				'trip-itx', 'station-cheongnyangni', 'station-chuncheon', 9800,
				'KRW', 'tago-train-schedule-fares', 'itx-test'
			)
			""");
		jdbcTemplate.update("""
			INSERT INTO route_service_artifact_evidence (
				service_class, timetable_artifact_id, timetable_artifact_sha256,
				canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256,
				admission_status, admission_eligible, fresh_until, source_issue
			) VALUES ('ITX_CHEONGCHUN', 'itx-test', ?, 'capital', ?, ?, 'ADMITTED', TRUE, ?, 2135)
			""", "a".repeat(64), "b".repeat(64), "c".repeat(64), freshUntil);
		insertSnapshotHistory("a".repeat(64), "snapshot-test", freshUntil);
		jdbcTemplate.update(
			"INSERT INTO timetable_snapshot_active (singleton_id, snapshot_sha256) VALUES (1, ?)",
			"a".repeat(64)
		);
	}

	private void insertSnapshotHistory(String snapshotSha256, String snapshotId, String freshUntil) {
		jdbcTemplate.update("""
			INSERT INTO timetable_snapshot_history (
				snapshot_sha256, snapshot_id, schema_identity, fresh_until,
				source_artifact_id, source_artifact_sha256, completeness_evidence_sha256,
				canonical_pack_sha256, canonical_pack_sqlite_sha256,
				canonical_station_version, canonical_station_set_sha256, canonical_station_member_count,
				source_lineage_sha256, evidence_hash,
				calendar_count, route_count, trip_count, stop_time_count
			) VALUES (?, ?, 'backend-timetable-snapshot-v1', ?, 'itx-test', ?, ?, ?, ?, ?, ?, 2, ?, ?, ?, ?, ?, ?)
			""",
			snapshotSha256,
			snapshotId,
			freshUntil,
			"a".repeat(64),
			"d".repeat(64),
			"b".repeat(64),
			"c".repeat(64),
			"sha256:" + "e".repeat(64),
			"e".repeat(64),
			"f".repeat(64),
			"1".repeat(64),
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_calendars", Integer.class),
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_routes", Integer.class),
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_trips", Integer.class),
			jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transit_stop_times", Integer.class)
		);
	}
}
