package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Activates one complete subway+ITX timetable snapshot atomically. */
@Component
@Profile("prod | staging | release | prod-like")
@ConditionalOnProperty(name = "easysubway.timetable.seed.enabled", havingValue = "true")
public class TimetableSeedLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TimetableSeedLoader.class);
	private static final String EVIDENCE_KIND = "server-timetable-snapshot-evidence";
	private static final String SCHEMA_IDENTITY = "backend-timetable-snapshot-v1";

	private final LoadRouteTimetablePort routeTimetablePort;
	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactionTemplate;
	private final Resource seedResource;
	private final Resource evidenceResource;
	private final boolean includesItxSeed;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Autowired
	public TimetableSeedLoader(
		LoadRouteTimetablePort routeTimetablePort,
		DataSource dataSource,
		PlatformTransactionManager transactionManager,
		@Value("${easysubway.timetable.seed.resource:classpath:timetable/line4-timetable-seed.sql.gz}") Resource seedResource,
		@Value("${easysubway.timetable.seed.evidence-resource:classpath:timetable/server-timetable-snapshot-evidence.json}") Resource evidenceResource,
		@Value("${easysubway.timetable.seed.includes-itx:false}") boolean includesItxSeed,
		ObjectMapper objectMapper
	) {
		this(
			routeTimetablePort,
			dataSource,
			transactionManager,
			seedResource,
			evidenceResource,
			includesItxSeed,
			objectMapper,
			Clock.systemUTC()
		);
	}

	TimetableSeedLoader(
		LoadRouteTimetablePort routeTimetablePort,
		DataSource dataSource,
		PlatformTransactionManager transactionManager,
		Resource seedResource,
		Resource evidenceResource,
		boolean includesItxSeed,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.routeTimetablePort = routeTimetablePort;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.seedResource = seedResource;
		this.evidenceResource = evidenceResource;
		this.includesItxSeed = includesItxSeed;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		ActivationResult result = activateSeed(seedResource, evidenceResource);
		log.info("transit timetable snapshot {} from {}", result, seedResource);
	}

	ActivationResult activateSeed(Resource seed, Resource evidence) {
		Candidate candidate = readCandidate(seed, evidence);
		if (!includesItxSeed) {
			throw new IllegalStateException(
				"easysubway.timetable.seed.includes-itx=true is required for complete server snapshots");
		}
		try {
			ActivationResult result = transactionTemplate.execute(status -> activateLocked(candidate));
			if (result == null) {
				throw new IllegalStateException("snapshot transaction returned no result");
			}
			return result;
		} catch (RuntimeException exception) {
			throw new IllegalStateException("transit timetable snapshot activation failed", exception);
		}
	}

	private ActivationResult activateLocked(Candidate candidate) {
		jdbcTemplate.queryForObject(
			"SELECT singleton_id FROM timetable_snapshot_lock WHERE singleton_id = 1 FOR UPDATE",
			Integer.class
		);
		Optional<String> activeSha = jdbcTemplate.query(
			"SELECT snapshot_sha256 FROM timetable_snapshot_active WHERE singleton_id = 1",
			(resultSet, rowNumber) -> resultSet.getString("snapshot_sha256")
		).stream().findFirst();
		if (activeSha.filter(candidate.evidence().snapshotSha256()::equals).isPresent()) {
			assertHistoryMatches(candidate.evidence());
			validateLoadedSnapshot(candidate.evidence());
			return ActivationResult.NO_CHANGE;
		}

		deleteCurrentSnapshot();
		executeStatements(candidate.statements());
		validateLoadedSnapshot(candidate.evidence());
		insertOrValidateHistory(candidate.evidence());
		if (activeSha.isEmpty()) {
			jdbcTemplate.update(
				"INSERT INTO timetable_snapshot_active (singleton_id, snapshot_sha256) VALUES (1, ?)",
				candidate.evidence().snapshotSha256()
			);
		} else {
			jdbcTemplate.update(
				"UPDATE timetable_snapshot_active SET snapshot_sha256 = ?, activated_at = CURRENT_TIMESTAMP "
					+ "WHERE singleton_id = 1",
				candidate.evidence().snapshotSha256()
			);
		}
		if (!routeTimetablePort.hasRouteTimetable()) {
			throw new IllegalStateException("activated snapshot is not readable by the runtime repository");
		}
		return ActivationResult.ACTIVATED;
	}

	private void deleteCurrentSnapshot() {
		for (String table : List.of(
			"transit_frequencies",
			"transit_trip_official_fares",
			"transit_stop_times",
			"transit_trips",
			"transit_routes",
			"service_calendar_dates",
			"service_calendars",
			"transit_feed_info",
			"route_service_artifact_evidence"
		)) {
			jdbcTemplate.update("DELETE FROM " + table);
		}
	}

	private void executeStatements(List<String> statements) {
		jdbcTemplate.batchUpdate(statements.toArray(String[]::new));
	}

	private void validateLoadedSnapshot(SnapshotEvidence evidence) {
		if (evidence.tripCount() != evidence.subwayTripCount() + evidence.itxTripCount()
			|| evidence.stopTimeCount() != evidence.subwayStopTimeCount() + evidence.itxStopTimeCount()) {
			throw new IllegalStateException("complete snapshot service row counts are inconsistent");
		}
		assertCount("service_calendars", evidence.calendarCount());
		assertCount("transit_routes", evidence.routeCount());
		assertCount("transit_trips", evidence.tripCount());
		assertCount("transit_stop_times", evidence.stopTimeCount());
		assertCount("transit_trip_official_fares", evidence.officialFareCount());
		assertCount("route_service_artifact_evidence", 1);
		assertQueryCount(
			"SELECT COUNT(*) FROM transit_trips WHERE service_class = 'SUBWAY'",
			evidence.subwayTripCount(),
			"subway trip count"
		);
		assertQueryCount(
			"SELECT COUNT(*) FROM transit_stop_times s JOIN transit_trips t ON t.id = s.trip_id "
				+ "WHERE t.service_class = 'SUBWAY'",
			evidence.subwayStopTimeCount(),
			"subway stop-time count"
		);
		assertQueryCount(
			"SELECT COUNT(*) FROM transit_trips WHERE service_class = 'ITX_CHEONGCHUN'",
			evidence.itxTripCount(),
			"ITX trip count"
		);
		assertQueryCount(
			"SELECT COUNT(*) FROM transit_stop_times s JOIN transit_trips t ON t.id = s.trip_id "
				+ "WHERE t.service_class = 'ITX_CHEONGCHUN'",
			evidence.itxStopTimeCount(),
			"ITX stop-time count"
		);
		assertQueryCount("""
			SELECT COUNT(*) FROM transit_trips
			WHERE service_pattern NOT IN ('LOCAL', 'EXPRESS')
				OR (service_class = 'ITX_CHEONGCHUN' AND service_pattern <> 'EXPRESS')
				OR TRIM(direction_id) = '' OR TRIM(trip_headsign) = ''
			""", 0, "trip service pattern identity");
		assertQueryCount("""
			SELECT COUNT(*) FROM transit_trips t
			WHERE NOT EXISTS (SELECT 1 FROM transit_stop_times s WHERE s.trip_id = t.id)
			""", 0, "trip stop pattern");
		assertQueryCount("""
			SELECT COUNT(*)
			FROM transit_stop_times s
			JOIN transit_trips t ON t.id = s.trip_id
			WHERE s.pickup_type NOT IN (0, 1) OR s.drop_off_type NOT IN (0, 1)
				OR (t.service_pattern = 'EXPRESS' AND s.pickup_type <> s.drop_off_type)
			""", 0, "EXPRESS boarding restriction");
		assertQueryCount("""
			SELECT COUNT(*) FROM (
				SELECT trip_id, COUNT(*) AS row_count, MIN(stop_sequence) AS first_sequence,
					MAX(stop_sequence) AS last_sequence
				FROM transit_stop_times GROUP BY trip_id
			) sequence_counts
			WHERE first_sequence <> 1 OR last_sequence <> row_count
			""", 0, "stop sequence continuity");
		assertQueryCount("""
			SELECT COUNT(*)
			FROM transit_stop_times current_stop
			JOIN transit_stop_times next_stop
				ON next_stop.trip_id = current_stop.trip_id
				AND next_stop.stop_sequence = current_stop.stop_sequence + 1
			WHERE current_stop.departure_seconds > next_stop.arrival_seconds
			""", 0, "stop-time monotonicity");
		Integer matchingEvidence = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM route_service_artifact_evidence
			WHERE service_class = 'ITX_CHEONGCHUN'
				AND timetable_artifact_id = ?
				AND timetable_artifact_sha256 = ?
				AND canonical_pack_id = 'capital'
				AND canonical_pack_sha256 = ?
				AND canonical_pack_sqlite_sha256 = ?
				AND admission_status = 'ADMITTED'
				AND admission_eligible = TRUE
				AND fresh_until = ?
				AND source_issue = 2135
			""",
			Integer.class,
			evidence.sourceArtifactId(),
			evidence.sourceArtifactSha256(),
			evidence.canonicalPackSha256(),
			evidence.canonicalPackSqliteSha256(),
			evidence.freshUntil()
		);
		if (matchingEvidence == null || matchingEvidence != 1) {
			throw new IllegalStateException("route service evidence does not match snapshot lineage");
		}
	}

	private void assertCount(String table, int expected) {
		assertQueryCount("SELECT COUNT(*) FROM " + table, expected, table);
	}

	private void assertQueryCount(String sql, int expected, String label) {
		Integer actual = jdbcTemplate.queryForObject(sql, Integer.class);
		if (actual == null || actual != expected) {
			throw new IllegalStateException(label + " count mismatch: expected=" + expected + ", actual=" + actual);
		}
	}

	private void insertOrValidateHistory(SnapshotEvidence evidence) {
		Integer existing = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM timetable_snapshot_history WHERE snapshot_sha256 = ?",
			Integer.class,
			evidence.snapshotSha256()
		);
		if (existing != null && existing == 1) {
			assertHistoryMatches(evidence);
			return;
		}
		jdbcTemplate.update("""
			INSERT INTO timetable_snapshot_history (
				snapshot_sha256, snapshot_id, schema_identity, fresh_until,
				source_artifact_id, source_artifact_sha256, completeness_evidence_sha256,
				canonical_pack_sha256, canonical_pack_sqlite_sha256,
				canonical_station_version, canonical_station_set_sha256, canonical_station_member_count,
				source_lineage_sha256, evidence_hash,
				calendar_count, route_count, trip_count, stop_time_count
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			evidence.snapshotSha256(),
			evidence.snapshotId(),
			evidence.schemaIdentity(),
			evidence.freshUntil(),
			evidence.sourceArtifactId(),
			evidence.sourceArtifactSha256(),
			evidence.completenessEvidenceSha256(),
			evidence.canonicalPackSha256(),
			evidence.canonicalPackSqliteSha256(),
			evidence.canonicalStationVersion(),
			evidence.canonicalStationSetSha256(),
			evidence.canonicalStationMemberCount(),
			evidence.sourceLineageSha256(),
			evidence.evidenceHash(),
			evidence.calendarCount(),
			evidence.routeCount(),
			evidence.tripCount(),
			evidence.stopTimeCount()
		);
	}

	private void assertHistoryMatches(SnapshotEvidence evidence) {
		Integer matches = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM timetable_snapshot_history
			WHERE snapshot_sha256 = ? AND snapshot_id = ? AND schema_identity = ? AND fresh_until = ?
				AND source_artifact_id = ? AND source_artifact_sha256 = ?
				AND completeness_evidence_sha256 = ?
				AND canonical_pack_sha256 = ? AND canonical_pack_sqlite_sha256 = ?
				AND canonical_station_version = ? AND canonical_station_set_sha256 = ?
				AND canonical_station_member_count = ? AND source_lineage_sha256 = ? AND evidence_hash = ?
				AND calendar_count = ? AND route_count = ? AND trip_count = ? AND stop_time_count = ?
			""",
			Integer.class,
			evidence.snapshotSha256(),
			evidence.snapshotId(),
			evidence.schemaIdentity(),
			evidence.freshUntil(),
			evidence.sourceArtifactId(),
			evidence.sourceArtifactSha256(),
			evidence.completenessEvidenceSha256(),
			evidence.canonicalPackSha256(),
			evidence.canonicalPackSqliteSha256(),
			evidence.canonicalStationVersion(),
			evidence.canonicalStationSetSha256(),
			evidence.canonicalStationMemberCount(),
			evidence.sourceLineageSha256(),
			evidence.evidenceHash(),
			evidence.calendarCount(),
			evidence.routeCount(),
			evidence.tripCount(),
			evidence.stopTimeCount()
		);
		if (matches == null || matches != 1) {
			throw new IllegalStateException("immutable snapshot history metadata mismatch");
		}
	}

	private Candidate readCandidate(Resource seed, Resource evidenceResource) {
		try (InputStream seedInput = seed.getInputStream();
			InputStream evidenceInput = evidenceResource.getInputStream()) {
			byte[] rawSeedBytes = seedInput.readAllBytes();
			byte[] sqlBytes = gunzip(seed, rawSeedBytes);
			JsonNode parsed = objectMapper.readTree(evidenceInput);
			if (!(parsed instanceof ObjectNode evidenceNode)) {
				throw new IllegalStateException("timetable snapshot evidence must be an object");
			}
			SnapshotEvidence evidence = SnapshotEvidence.from(evidenceNode, objectMapper, clock);
			if (!evidence.snapshotSha256().equals(sha256(sqlBytes))
				|| evidence.snapshotSqlByteSize() != sqlBytes.length
				|| !evidence.snapshotGzipSha256().equals(sha256(rawSeedBytes))
				|| evidence.snapshotGzipByteSize() != rawSeedBytes.length) {
				throw new IllegalStateException("timetable snapshot evidence does not match seed bytes");
			}
			List<String> lines = new String(sqlBytes, StandardCharsets.UTF_8).lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("--"))
				.toList();
			if (lines.isEmpty()) {
				throw new IllegalStateException("timetable snapshot seed is empty");
			}
			if (lines.stream().anyMatch(line -> !line.endsWith(";"))) {
				throw new IllegalStateException("timetable snapshot seed must contain one statement per line");
			}
			List<String> statements = lines.stream()
				.map(line -> line.substring(0, line.length() - 1))
				.toList();
			return new Candidate(statements, evidence);
		} catch (IOException exception) {
			throw new IllegalStateException("cannot read timetable snapshot seed or evidence", exception);
		}
	}

	private static byte[] gunzip(Resource resource, byte[] bytes) throws IOException {
		String filename = resource.getFilename();
		if (filename == null || !filename.endsWith(".gz")) {
			throw new IllegalStateException("timetable snapshot seed must be gzip-compressed");
		}
		try (InputStream input = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
			return input.readAllBytes();
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	enum ActivationResult {
		ACTIVATED,
		NO_CHANGE
	}

	private record Candidate(List<String> statements, SnapshotEvidence evidence) {
	}

	private record SnapshotEvidence(
		String snapshotId,
		String snapshotSha256,
		int snapshotSqlByteSize,
		String snapshotGzipSha256,
		int snapshotGzipByteSize,
		String schemaIdentity,
		String freshUntil,
		String sourceArtifactId,
		String sourceArtifactSha256,
		String completenessEvidenceSha256,
		String canonicalPackSha256,
		String canonicalPackSqliteSha256,
		String canonicalStationVersion,
		String canonicalStationSetSha256,
		int canonicalStationMemberCount,
		String sourceLineageSha256,
		String evidenceHash,
		int calendarCount,
		int routeCount,
		int tripCount,
		int stopTimeCount,
		int subwayTripCount,
		int subwayStopTimeCount,
		int itxTripCount,
		int itxStopTimeCount,
		int officialFareCount
	) {

		static SnapshotEvidence from(ObjectNode node, ObjectMapper mapper, Clock clock) {
			String evidenceHash = text(node, "evidenceHash");
			ObjectNode withoutHash = node.deepCopy();
			withoutHash.remove("evidenceHash");
			try {
				if (!evidenceHash.equals(sha256(mapper.writeValueAsBytes(withoutHash)))) {
					throw new IllegalStateException("timetable snapshot evidence hash is invalid");
				}
			} catch (IOException exception) {
				throw new IllegalStateException("cannot canonicalize timetable snapshot evidence", exception);
			}
			if (integer(node, "schemaVersion") != 1
				|| !EVIDENCE_KIND.equals(text(node, "artifactKind"))
				|| !SCHEMA_IDENTITY.equals(text(node, "schemaIdentity"))) {
				throw new IllegalStateException("timetable snapshot evidence schema is invalid");
			}
			JsonNode source = object(node, "sourceArtifact");
			JsonNode service = object(node, "serviceIdentity");
			JsonNode canonical = object(node, "canonicalPackIdentity");
			JsonNode stations = object(node, "canonicalStationSet");
			JsonNode counts = object(node, "rowCounts");
			String freshUntil = text(node, "freshUntil");
			try {
				if (!OffsetDateTime.parse(freshUntil).toInstant().isAfter(clock.instant())) {
					throw new IllegalStateException("timetable snapshot evidence is stale");
				}
			} catch (DateTimeParseException exception) {
				throw new IllegalStateException("timetable snapshot evidence freshness is invalid", exception);
			}
			SnapshotEvidence evidence = new SnapshotEvidence(
				text(node, "snapshotId"),
				hash(node, "snapshotSha256"),
				positiveInteger(node, "snapshotSqlByteSize"),
				hash(node, "snapshotGzipSha256"),
				positiveInteger(node, "snapshotGzipByteSize"),
				text(node, "schemaIdentity"),
				freshUntil,
				text(source, "id"),
				hash(source, "sha256"),
				hash(source, "completenessEvidenceSha256"),
				hash(canonical, "sha256"),
				hash(canonical, "sqliteSha256"),
				text(stations, "version"),
				hash(stations, "sha256"),
				positiveInteger(stations, "memberCount"),
				hash(node, "sourceLineageSha256"),
				evidenceHash,
				positiveInteger(counts, "calendars"),
				positiveInteger(counts, "routes"),
				positiveInteger(counts, "trips"),
				positiveInteger(counts, "stopTimes"),
				positiveInteger(counts, "subwayTrips"),
				positiveInteger(counts, "subwayStopTimes"),
				positiveInteger(counts, "itxTrips"),
				positiveInteger(counts, "itxStopTimes"),
				positiveInteger(counts, "officialFares")
			);
			if (!"ITX_CHEONGCHUN".equals(text(service, "serviceId"))
				|| !"line-54a7b980b7c3".equals(text(service, "canonicalLineId"))
				|| !"EXPRESS".equals(text(service, "servicePattern"))
				|| !"Asia/Seoul".equals(text(service, "timezone"))
				|| !"capital".equals(text(canonical, "id"))
				|| !evidence.canonicalStationVersion().equals("sha256:" + evidence.canonicalStationSetSha256())) {
				throw new IllegalStateException("timetable snapshot evidence canonical identity is invalid");
			}
			return evidence;
		}

		private static JsonNode object(JsonNode node, String field) {
			JsonNode value = node.get(field);
			if (value == null || !value.isObject()) {
				throw new IllegalStateException("timetable snapshot evidence field is invalid: " + field);
			}
			return value;
		}

		private static String hash(JsonNode node, String field) {
			String value = text(node, field);
			if (!value.matches("[0-9a-f]{64}")) {
				throw new IllegalStateException("timetable snapshot evidence hash is invalid: " + field);
			}
			return value;
		}

		private static String text(JsonNode node, String field) {
			JsonNode value = node.get(field);
			if (value == null || !value.isTextual() || value.textValue().isBlank()) {
				throw new IllegalStateException("timetable snapshot evidence field is invalid: " + field);
			}
			return value.textValue();
		}

		private static int positiveInteger(JsonNode node, String field) {
			int value = integer(node, field);
			if (value <= 0) {
				throw new IllegalStateException("timetable snapshot evidence count is invalid: " + field);
			}
			return value;
		}

		private static int integer(JsonNode node, String field) {
			JsonNode value = node.get(field);
			if (value == null || !value.canConvertToInt()) {
				throw new IllegalStateException("timetable snapshot evidence integer is invalid: " + field);
			}
			return value.intValue();
		}
	}
}
