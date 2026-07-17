package com.easysubway.route.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class TimetableSeedLoaderTest {

	private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
	private static final String FRESH_UNTIL = "2026-07-20T00:00:00+09:00";
	private final ObjectMapper objectMapper = new ObjectMapper();
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
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V61__timetable_snapshot_state.sql'");
		jdbc.execute("RUNSCRIPT FROM 'src/main/resources/db/migration/h2/V62__route_v2_planner_identity.sql'");
	}

	@Test
	void activatesCompleteSnapshotAndSameHashPerformsNoWrites() throws Exception {
		SnapshotResource snapshot = snapshot("a", false);
		TimetableSeedLoader loader = loader(snapshot);

		assertThat(loader.activateSeed(snapshot.seed(), snapshot.evidence()))
			.isEqualTo(TimetableSeedLoader.ActivationResult.ACTIVATED);
		String activatedAt = jdbc.queryForObject(
			"SELECT CAST(activated_at AS VARCHAR) FROM timetable_snapshot_active", String.class);
		jdbc.execute("""
			CREATE TABLE same_hash_write_guard (
				trip_id VARCHAR(200) REFERENCES transit_trips(id),
				service_class VARCHAR(40) REFERENCES route_service_artifact_evidence(service_class)
			)
			""");
		jdbc.update(
			"INSERT INTO same_hash_write_guard (trip_id, service_class) VALUES (?, 'ITX_CHEONGCHUN')",
			"itx-trip-a"
		);
		assertThat(loader.activateSeed(snapshot.seed(), snapshot.evidence()))
			.isEqualTo(TimetableSeedLoader.ActivationResult.NO_CHANGE);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_snapshot_history", Integer.class)).isOne();
		assertThat(jdbc.queryForObject(
			"SELECT CAST(activated_at AS VARCHAR) FROM timetable_snapshot_active", String.class)).isEqualTo(activatedAt);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM same_hash_write_guard", Integer.class)).isOne();
		assertSnapshotRows("a");
	}

	@Test
	void changedSnapshotReplacesAllRowsAndPreviousApprovedSnapshotCanRollback() throws Exception {
		SnapshotResource first = snapshot("a", false);
		SnapshotResource second = snapshot("b", false);
		TimetableSeedLoader loader = loader(first);
		loader.activateSeed(first.seed(), first.evidence());

		assertThat(loader.activateSeed(second.seed(), second.evidence()))
			.isEqualTo(TimetableSeedLoader.ActivationResult.ACTIVATED);
		assertSnapshotRows("b");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_snapshot_history", Integer.class)).isEqualTo(2);

		assertThat(loader.activateSeed(first.seed(), first.evidence()))
			.isEqualTo(TimetableSeedLoader.ActivationResult.ACTIVATED);
		assertSnapshotRows("a");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_snapshot_history", Integer.class)).isEqualTo(2);
	}

	@Test
	void sqlOrForeignKeyFailurePreservesPriorCommittedSnapshot() throws Exception {
		SnapshotResource first = snapshot("a", false);
		SnapshotResource invalid = snapshot("bad", true);
		SnapshotResource invalidValidation = withTripCount(snapshot("b", false), 3);
		SnapshotResource invalidExpressOrder = withNonMonotonicExpressStop(snapshot("order", false));
		TimetableSeedLoader loader = loader(first);
		loader.activateSeed(first.seed(), first.evidence());
		String activeSha = activeSha();

		assertThatThrownBy(() -> loader.activateSeed(invalid.seed(), invalid.evidence()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("transit timetable snapshot activation failed");

		assertThat(activeSha()).isEqualTo(activeSha);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_snapshot_history", Integer.class)).isOne();
		assertSnapshotRows("a");

		assertThatThrownBy(() -> loader.activateSeed(invalidExpressOrder.seed(), invalidExpressOrder.evidence()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("transit timetable snapshot activation failed");
		assertThat(activeSha()).isEqualTo(activeSha);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_snapshot_history", Integer.class)).isOne();
		assertSnapshotRows("a");

		assertThatThrownBy(() -> loader.activateSeed(invalidValidation.seed(), invalidValidation.evidence()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("transit timetable snapshot activation failed");
		assertThat(activeSha()).isEqualTo(activeSha);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_snapshot_history", Integer.class)).isOne();
		assertSnapshotRows("a");
	}

	@Test
	void itxOnlyCandidateCannotReplacePriorCompleteSnapshot() throws Exception {
		SnapshotResource first = snapshot("a", false);
		SnapshotResource itxOnly = withoutSubwayRows(snapshot("itx-only", false));
		TimetableSeedLoader loader = loader(first);
		loader.activateSeed(first.seed(), first.evidence());
		String activeSha = activeSha();

		assertThatThrownBy(() -> loader.activateSeed(itxOnly.seed(), itxOnly.evidence()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("transit timetable snapshot activation failed");

		assertThat(activeSha()).isEqualTo(activeSha);
		assertSnapshotRows("a");
	}

	@Test
	void concurrentLoadersConvergeOnOneCompleteSnapshotWithoutMixedRows() throws Exception {
		SnapshotResource first = snapshot("a", false);
		SnapshotResource second = snapshot("b", false);
		loader(first).activateSeed(first.seed(), first.evidence());
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (var executor = Executors.newFixedThreadPool(2)) {
			var attempts = java.util.List.of(first, second).stream().map(candidate -> executor.submit(() -> {
				ready.countDown();
				start.await();
				return loader(candidate).activateSeed(candidate.seed(), candidate.evidence());
			})).toList();
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (var attempt : attempts) {
				attempt.get(10, TimeUnit.SECONDS);
			}
		}

		String active = jdbc.queryForObject(
			"SELECT source_artifact_id FROM timetable_snapshot_history h "
				+ "JOIN timetable_snapshot_active a ON a.snapshot_sha256 = h.snapshot_sha256", String.class);
		assertThat(active).isIn("artifact-a", "artifact-b");
		assertSnapshotRows(active.endsWith("a") ? "a" : "b");
	}

	@Test
	void concurrentReadersObserveOnlyOneCommittedCompleteSnapshot() throws Exception {
		SnapshotResource first = snapshot("a", false);
		SnapshotResource second = snapshot("b", false);
		loader(first).activateSeed(first.seed(), first.evidence());
		CountDownLatch start = new CountDownLatch(1);
		TransactionTemplate readTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

		try (var executor = Executors.newFixedThreadPool(3)) {
			var writer = executor.submit(() -> {
				start.await();
				for (int index = 0; index < 30; index++) {
					SnapshotResource candidate = index % 2 == 0 ? second : first;
					loader(candidate).activateSeed(candidate.seed(), candidate.evidence());
				}
				return null;
			});
			var readers = java.util.List.of(0, 1).stream().map(ignored -> executor.submit(() -> {
				start.await();
				for (int index = 0; index < 100; index++) {
					readTransaction.executeWithoutResult(status -> assertReaderSnapshotIsComplete());
				}
				return null;
			})).toList();
			start.countDown();
			writer.get(20, TimeUnit.SECONDS);
			for (var reader : readers) {
				reader.get(20, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	void trackedCompleteSnapshotLoadsWithExactEvidenceCounts() {
		TimetableSeedLoader loader = new TimetableSeedLoader(
			new JdbcRouteTimetableRepository(dataSource),
			dataSource,
			new DataSourceTransactionManager(dataSource),
			new ClassPathResource("timetable/line4-timetable-seed.sql.gz"),
			new ClassPathResource("timetable/server-timetable-snapshot-evidence.json"),
			true,
			objectMapper,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);

		loader.run(null);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_trips", Integer.class)).isEqualTo(1035);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_stop_times", Integer.class)).isEqualTo(34070);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM transit_trips WHERE service_class = 'ITX_CHEONGCHUN'", Integer.class))
			.isEqualTo(140);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_trip_official_fares", Integer.class))
			.isEqualTo(3686);
	}

	@Test
	void rejectsDisabledItxOrStaleAndTamperedEvidenceBeforeWrites() throws Exception {
		SnapshotResource snapshot = snapshot("a", false);
		TimetableSeedLoader disabled = new TimetableSeedLoader(
			new JdbcRouteTimetableRepository(dataSource),
			dataSource,
			new DataSourceTransactionManager(dataSource),
			snapshot.seed(),
			snapshot.evidence(),
			false,
			objectMapper,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		assertThatThrownBy(() -> disabled.run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("includes-itx");

		ObjectNode stale;
		try (var input = snapshot.evidence().getInputStream()) {
			stale = (ObjectNode) objectMapper.readTree(input);
		}
		stale.put("freshUntil", "2000-01-01T00:00:00Z");
		SnapshotResource staleSnapshot = new SnapshotResource(snapshot.seed(), jsonResource(stale, "stale.json"));
		assertThatThrownBy(() -> loader(staleSnapshot).run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("evidence");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_snapshot_history", Integer.class)).isZero();
	}

	private TimetableSeedLoader loader(SnapshotResource snapshot) {
		return new TimetableSeedLoader(
			new JdbcRouteTimetableRepository(dataSource),
			dataSource,
			new DataSourceTransactionManager(dataSource),
			snapshot.seed(),
			snapshot.evidence(),
			true,
			objectMapper,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	private SnapshotResource snapshot(String suffix, boolean invalidForeignKey) throws Exception {
		String sourceHash = sha256("source-" + suffix);
		String sql = """
			INSERT INTO transit_feed_info (id, feed_end_date) VALUES (1, '20261231');
			INSERT INTO service_calendars (service_id, start_date, end_date, timezone, monday, tuesday, wednesday, thursday, friday, saturday, sunday) VALUES ('service-%1$s','20260101','20261231','Asia/Seoul',TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE);
			INSERT INTO transit_routes (id, timezone, line_id, route_short_name, route_long_name, direction_name) VALUES ('subway-route-%1$s','Asia/Seoul','seoul-4','','','up');
			INSERT INTO transit_routes (id, timezone, line_id, route_short_name, route_long_name, direction_name) VALUES ('itx-route-%1$s','Asia/Seoul','line-54a7b980b7c3','ITX-청춘','','down');
			INSERT INTO route_service_artifact_evidence (service_class, timetable_artifact_id, timetable_artifact_sha256, canonical_pack_id, canonical_pack_sha256, canonical_pack_sqlite_sha256, admission_status, admission_eligible, fresh_until, source_issue) VALUES ('ITX_CHEONGCHUN','artifact-%1$s','%2$s','capital','%3$s','%4$s','ADMITTED',TRUE,'%5$s',2135);
			INSERT INTO transit_trips (id, route_id, service_id, service_pattern, service_class, service_day_start_seconds, trip_headsign, direction_id) VALUES ('subway-trip-%1$s','subway-route-%1$s','service-%1$s','LOCAL','SUBWAY',0,'station-subway-terminal-%1$s','up');
			INSERT INTO transit_trips (id, route_id, service_id, service_pattern, service_class, service_day_start_seconds, trip_headsign, direction_id) VALUES ('itx-trip-%1$s','itx-route-%1$s','service-%1$s','EXPRESS','ITX_CHEONGCHUN',0,'춘천','down');
			INSERT INTO transit_stop_times (trip_id, stop_sequence, station_id, line_id, pickup_type, drop_off_type, arrival_seconds, departure_seconds) VALUES ('subway-trip-%1$s',1,'station-subway-%1$s','seoul-4',0,0,100,100);
			INSERT INTO transit_stop_times (trip_id, stop_sequence, station_id, line_id, pickup_type, drop_off_type, arrival_seconds, departure_seconds) VALUES ('itx-trip-%1$s',1,'station-itx-origin-%1$s','line-54a7b980b7c3',0,0,200,200);
			INSERT INTO transit_stop_times (trip_id, stop_sequence, station_id, line_id, pickup_type, drop_off_type, arrival_seconds, departure_seconds) VALUES ('itx-trip-%1$s',2,'station-itx-pass-%1$s','line-54a7b980b7c3',1,1,250,250);
			INSERT INTO transit_stop_times (trip_id, stop_sequence, station_id, line_id, pickup_type, drop_off_type, arrival_seconds, departure_seconds) VALUES ('%6$s',3,'station-itx-terminal-%1$s','line-54a7b980b7c3',0,0,300,300);
			INSERT INTO transit_trip_official_fares (trip_id, origin_station_id, destination_station_id, adult_fare_won, currency, source_id, source_snapshot_id) VALUES ('itx-trip-%1$s','station-itx-origin-%1$s','station-itx-terminal-%1$s',9800,'KRW','official','snapshot-%1$s');
			""".formatted(
			suffix,
			sourceHash,
			"b".repeat(64),
			"c".repeat(64),
			FRESH_UNTIL,
			invalidForeignKey ? "missing-trip" : "itx-trip-" + suffix
		);
		byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
		byte[] gzipBytes = gzip(sqlBytes);
		ObjectNode evidence = objectMapper.createObjectNode();
		evidence.put("schemaVersion", 1);
		evidence.put("artifactKind", "server-timetable-snapshot-evidence");
		evidence.put("schemaIdentity", "backend-timetable-snapshot-v1");
		evidence.put("snapshotId", "snapshot-" + suffix);
		evidence.put("snapshotSha256", sha256(sqlBytes));
		evidence.put("snapshotSqlByteSize", sqlBytes.length);
		evidence.put("snapshotGzipSha256", sha256(gzipBytes));
		evidence.put("snapshotGzipByteSize", gzipBytes.length);
		evidence.put("freshUntil", FRESH_UNTIL);
		ObjectNode service = evidence.putObject("serviceIdentity");
		service.put("serviceId", "ITX_CHEONGCHUN");
		service.put("canonicalLineId", "line-54a7b980b7c3");
		service.put("servicePattern", "EXPRESS");
		service.put("timezone", "Asia/Seoul");
		ObjectNode source = evidence.putObject("sourceArtifact");
		source.put("id", "artifact-" + suffix);
		source.put("sha256", sourceHash);
		source.put("completenessEvidenceSha256", "d".repeat(64));
		ObjectNode canonical = evidence.putObject("canonicalPackIdentity");
		canonical.put("id", "capital");
		canonical.put("sha256", "b".repeat(64));
		canonical.put("sqliteSha256", "c".repeat(64));
		ObjectNode stationSet = evidence.putObject("canonicalStationSet");
		stationSet.put("version", "sha256:" + "e".repeat(64));
		stationSet.put("sha256", "e".repeat(64));
		stationSet.put("memberCount", 2);
		evidence.put("sourceLineageSha256", "f".repeat(64));
		ObjectNode counts = evidence.putObject("rowCounts");
		counts.put("calendars", 1);
		counts.put("routes", 2);
		counts.put("trips", 2);
		counts.put("stopTimes", 4);
		counts.put("subwayTrips", 1);
		counts.put("subwayStopTimes", 1);
		counts.put("itxTrips", 1);
		counts.put("itxStopTimes", 3);
		counts.put("officialFares", 1);
		counts.put("routeServiceEvidence", 1);
		evidence.put("evidenceHash", sha256(objectMapper.writeValueAsBytes(evidence)));
		return new SnapshotResource(namedResource(gzipBytes, "snapshot.sql.gz"), jsonResource(evidence, "evidence.json"));
	}

	private SnapshotResource withTripCount(SnapshotResource snapshot, int count) throws Exception {
		ObjectNode evidence;
		try (var input = snapshot.evidence().getInputStream()) {
			evidence = (ObjectNode) objectMapper.readTree(input);
		}
		evidence.withObject("/rowCounts").put("trips", count);
		evidence.remove("evidenceHash");
		evidence.put("evidenceHash", sha256(objectMapper.writeValueAsBytes(evidence)));
		return new SnapshotResource(snapshot.seed(), jsonResource(evidence, "invalid-validation-evidence.json"));
	}

	private SnapshotResource withoutSubwayRows(SnapshotResource snapshot) throws Exception {
		byte[] compressed;
		try (var input = snapshot.seed().getInputStream()) {
			compressed = input.readAllBytes();
		}
		byte[] sqlBytes;
		try (var input = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
			sqlBytes = input.readAllBytes();
		}
		String sql = new String(sqlBytes, StandardCharsets.UTF_8).lines()
			.filter(line -> !line.startsWith("INSERT INTO transit_trips ") || !line.contains("'SUBWAY'"))
			.filter(line -> !line.startsWith("INSERT INTO transit_stop_times ") || !line.contains("'subway-trip-"))
			.collect(Collectors.joining("\n", "", "\n"));
		byte[] itxOnlySqlBytes = sql.getBytes(StandardCharsets.UTF_8);
		byte[] itxOnlyGzipBytes = gzip(itxOnlySqlBytes);
		ObjectNode evidence;
		try (var input = snapshot.evidence().getInputStream()) {
			evidence = (ObjectNode) objectMapper.readTree(input);
		}
		evidence.put("snapshotSha256", sha256(itxOnlySqlBytes));
		evidence.put("snapshotSqlByteSize", itxOnlySqlBytes.length);
		evidence.put("snapshotGzipSha256", sha256(itxOnlyGzipBytes));
		evidence.put("snapshotGzipByteSize", itxOnlyGzipBytes.length);
		evidence.withObject("/rowCounts").put("trips", 1).put("stopTimes", 3);
		evidence.remove("evidenceHash");
		evidence.put("evidenceHash", sha256(objectMapper.writeValueAsBytes(evidence)));
		return new SnapshotResource(
			namedResource(itxOnlyGzipBytes, "itx-only.sql.gz"),
			jsonResource(evidence, "itx-only-evidence.json")
		);
	}

	private SnapshotResource withNonMonotonicExpressStop(SnapshotResource snapshot) throws Exception {
		byte[] compressed;
		try (var input = snapshot.seed().getInputStream()) {
			compressed = input.readAllBytes();
		}
		byte[] sqlBytes;
		try (var input = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
			sqlBytes = input.readAllBytes();
		}
		String sql = new String(sqlBytes, StandardCharsets.UTF_8)
			.replace(",0,0,300,300);", ",0,0,150,150);");
		byte[] invalidSqlBytes = sql.getBytes(StandardCharsets.UTF_8);
		byte[] invalidGzipBytes = gzip(invalidSqlBytes);
		ObjectNode evidence;
		try (var input = snapshot.evidence().getInputStream()) {
			evidence = (ObjectNode) objectMapper.readTree(input);
		}
		evidence.put("snapshotSha256", sha256(invalidSqlBytes));
		evidence.put("snapshotSqlByteSize", invalidSqlBytes.length);
		evidence.put("snapshotGzipSha256", sha256(invalidGzipBytes));
		evidence.put("snapshotGzipByteSize", invalidGzipBytes.length);
		evidence.remove("evidenceHash");
		evidence.put("evidenceHash", sha256(objectMapper.writeValueAsBytes(evidence)));
		return new SnapshotResource(
			namedResource(invalidGzipBytes, "invalid-express-order.sql.gz"),
			jsonResource(evidence, "invalid-express-order-evidence.json")
		);
	}

	private void assertSnapshotRows(String suffix) {
		assertThat(jdbc.queryForList("SELECT id FROM transit_trips ORDER BY id", String.class))
			.containsExactly("itx-trip-" + suffix, "subway-trip-" + suffix);
		assertThat(jdbc.queryForList("SELECT trip_id FROM transit_stop_times ORDER BY trip_id", String.class))
			.containsExactly(
				"itx-trip-" + suffix,
				"itx-trip-" + suffix,
				"itx-trip-" + suffix,
				"subway-trip-" + suffix
			);
		assertThat(jdbc.queryForList(
			"SELECT service_pattern FROM transit_trips ORDER BY id", String.class))
			.containsExactly("EXPRESS", "LOCAL");
		assertThat(jdbc.queryForList(
			"SELECT pickup_type FROM transit_stop_times WHERE trip_id = ? ORDER BY stop_sequence",
			Integer.class,
			"itx-trip-" + suffix
		)).containsExactly(0, 1, 0);
		assertThat(jdbc.queryForList(
			"SELECT drop_off_type FROM transit_stop_times WHERE trip_id = ? ORDER BY stop_sequence",
			Integer.class,
			"itx-trip-" + suffix
		)).containsExactly(0, 1, 0);
		assertThat(jdbc.queryForList(
			"SELECT source_snapshot_id FROM transit_trip_official_fares", String.class))
			.containsExactly("snapshot-" + suffix);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timetable_snapshot_active", Integer.class)).isOne();
	}

	private void assertReaderSnapshotIsComplete() {
		var trips = jdbc.queryForList("SELECT id FROM transit_trips ORDER BY id", String.class);
		Thread.yield();
		var stopTrips = jdbc.queryForList("SELECT trip_id FROM transit_stop_times ORDER BY trip_id", String.class);
		assertThat(java.util.List.of(trips, stopTrips)).isIn(
			java.util.List.of(
				java.util.List.of("itx-trip-a", "subway-trip-a"),
				java.util.List.of("itx-trip-a", "itx-trip-a", "itx-trip-a", "subway-trip-a")
			),
			java.util.List.of(
				java.util.List.of("itx-trip-b", "subway-trip-b"),
				java.util.List.of("itx-trip-b", "itx-trip-b", "itx-trip-b", "subway-trip-b")
			)
		);
	}

	private String activeSha() {
		return jdbc.queryForObject("SELECT snapshot_sha256 FROM timetable_snapshot_active", String.class);
	}

	private Resource jsonResource(ObjectNode value, String filename) throws Exception {
		return namedResource(objectMapper.writeValueAsBytes(value), filename);
	}

	private Resource namedResource(byte[] bytes, String filename) {
		return new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
	}

	private static byte[] gzip(byte[] bytes) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
			gzip.write(bytes);
		}
		return output.toByteArray();
	}

	private static String sha256(String value) throws Exception {
		return sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(byte[] value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}

	private record SnapshotResource(Resource seed, Resource evidence) {
	}
}
