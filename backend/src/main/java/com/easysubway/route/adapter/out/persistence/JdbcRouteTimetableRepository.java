package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class JdbcRouteTimetableRepository implements LoadRouteTimetablePort {

	private static final Logger log = LoggerFactory.getLogger(JdbcRouteTimetableRepository.class);

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	@Autowired
	public JdbcRouteTimetableRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource), Clock.systemUTC());
	}

	JdbcRouteTimetableRepository(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, Clock.systemUTC());
	}

	JdbcRouteTimetableRepository(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	@Override
	public boolean hasRouteTimetable() {
		return activeItxArtifact().isPresent() && hasReadableTransitTrips();
	}

	@Override
	public boolean hasActivatableRouteTimetable() {
		return admissibleItxArtifact().isPresent() && hasReadableTransitTrips();
	}

	private boolean hasReadableTransitTrips() {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"""
				SELECT CASE
					WHEN EXISTS (
						SELECT 1 FROM transit_trips t
						WHERE EXISTS (SELECT 1 FROM transit_stop_times s WHERE s.trip_id = t.id)
					)
					THEN TRUE ELSE FALSE
				END
				""",
			Boolean.class
		));
	}

	@Override
	public String timetableCacheKey() {
		return activeItxArtifact()
			.map(JdbcRouteTimetableRepository::cacheKey)
			.orElse("UNAVAILABLE");
	}

	@Override
	public Optional<String> activeItxTimetableArtifactId() {
		return activeItxArtifact().map(ItxArtifact::snapshotId);
	}

	@Override
	public RouteTimetableSnapshot loadRouteTimetableSnapshot() {
		return activeItxArtifact()
			.map(artifact -> new RouteTimetableSnapshot(
				cacheKey(artifact),
				artifact.snapshotId(),
				artifact.plannerIdentity(),
				loadRouteTimetable()
			))
			.orElseGet(() -> new RouteTimetableSnapshot("UNAVAILABLE", null, RouteTimetable.empty()));
	}

	private Optional<ItxArtifact> activeItxArtifact() {
		return admissibleItxArtifact()
			.filter(artifact -> freshOffsetDateTime(artifact.freshUntil()).isPresent());
	}

	// 시간 기반 freshness를 제외한 lineage·schema 적격성만 판정한다(활성화 시점 readability 검사용).
	private Optional<ItxArtifact> admissibleItxArtifact() {
		return jdbcTemplate.query(
			"""
				SELECT h.snapshot_sha256, h.snapshot_id, h.fresh_until,
					h.canonical_pack_sha256, h.canonical_pack_sqlite_sha256,
					h.canonical_station_version, h.canonical_station_set_sha256,
					h.source_lineage_sha256, h.evidence_hash
				FROM timetable_snapshot_active a
				JOIN timetable_snapshot_history h ON h.snapshot_sha256 = a.snapshot_sha256
				JOIN route_service_artifact_evidence e
					ON e.service_class = 'ITX_CHEONGCHUN'
					AND e.timetable_artifact_id = h.source_artifact_id
					AND e.timetable_artifact_sha256 = h.source_artifact_sha256
					AND e.canonical_pack_id = 'capital'
					AND e.canonical_pack_sha256 = h.canonical_pack_sha256
					AND e.canonical_pack_sqlite_sha256 = h.canonical_pack_sqlite_sha256
					AND e.fresh_until = h.fresh_until
					AND e.admission_status = 'ADMITTED'
					AND e.admission_eligible = TRUE
					AND e.source_issue = 2135
				WHERE a.singleton_id = 1
					AND h.schema_identity = 'backend-timetable-snapshot-v1'
					AND EXISTS (
						SELECT 1 FROM transit_trips t
						WHERE t.service_class = 'ITX_CHEONGCHUN'
							AND EXISTS (SELECT 1 FROM transit_stop_times s WHERE s.trip_id = t.id)
					)
				""",
			(resultSet, rowNumber) -> new ItxArtifact(
				resultSet.getString("snapshot_sha256"),
				resultSet.getString("snapshot_id"),
				resultSet.getString("fresh_until"),
				new PlannerIdentity(
					resultSet.getString("snapshot_sha256"),
					resultSet.getString("canonical_pack_sha256"),
					resultSet.getString("canonical_pack_sqlite_sha256"),
					resultSet.getString("canonical_station_version"),
					resultSet.getString("canonical_station_set_sha256"),
					resultSet.getString("source_lineage_sha256"),
					resultSet.getString("evidence_hash")
				)
			)
		).stream().findFirst();
	}

	@Override
	public RouteTimetable loadRouteTimetable() {
		return new RouteTimetable(
			jdbcTemplate.query(
				"""
					SELECT service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday,
						start_date, end_date, timezone
					FROM service_calendars
					ORDER BY service_id
					""",
				(resultSet, rowNumber) -> new ServiceCalendar(
					resultSet.getString("service_id"),
					resultSet.getBoolean("monday"),
					resultSet.getBoolean("tuesday"),
					resultSet.getBoolean("wednesday"),
					resultSet.getBoolean("thursday"),
					resultSet.getBoolean("friday"),
					resultSet.getBoolean("saturday"),
					resultSet.getBoolean("sunday"),
					serviceDate(resultSet.getString("start_date")),
					serviceDate(resultSet.getString("end_date")),
					resultSet.getString("timezone")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT service_id, date, exception_type
					FROM service_calendar_dates
					ORDER BY service_id, date
					""",
				(resultSet, rowNumber) -> new ServiceCalendarDate(
					resultSet.getString("service_id"),
					serviceDate(resultSet.getString("date")),
					resultSet.getInt("exception_type")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT id, line_id, route_short_name, route_long_name, direction_name, timezone
					FROM transit_routes
					ORDER BY id
					""",
				(resultSet, rowNumber) -> new TransitRoute(
					resultSet.getString("id"),
					resultSet.getString("line_id"),
					resultSet.getString("route_short_name"),
					resultSet.getString("route_long_name"),
					resultSet.getString("direction_name"),
					resultSet.getString("timezone")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT id, route_id, service_id, trip_headsign, direction_id, service_class, service_pattern,
						train_no, service_day_start_seconds
					FROM transit_trips
					ORDER BY id
					""",
				(resultSet, rowNumber) -> new TransitTrip(
					resultSet.getString("id"),
					resultSet.getString("route_id"),
					resultSet.getString("service_id"),
					resultSet.getString("trip_headsign"),
					resultSet.getString("direction_id"),
					resultSet.getString("service_class"),
					resultSet.getString("service_pattern"),
					resultSet.getString("train_no"),
					resultSet.getInt("service_day_start_seconds")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT trip_id, stop_sequence, station_id, line_id, arrival_seconds, departure_seconds,
						pickup_type, drop_off_type
					FROM transit_stop_times
					ORDER BY trip_id, stop_sequence
					""",
				(resultSet, rowNumber) -> new TransitStopTime(
					resultSet.getString("trip_id"),
					resultSet.getInt("stop_sequence"),
					resultSet.getString("station_id"),
					resultSet.getString("line_id"),
					resultSet.getInt("arrival_seconds"),
					resultSet.getInt("departure_seconds"),
					resultSet.getInt("pickup_type"),
					resultSet.getInt("drop_off_type")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT trip_id, start_time_seconds, end_time_seconds, headway_seconds, exact_times
					FROM transit_frequencies
					ORDER BY trip_id, start_time_seconds
					""",
				(resultSet, rowNumber) -> new TransitFrequency(
					resultSet.getString("trip_id"),
					resultSet.getInt("start_time_seconds"),
					resultSet.getInt("end_time_seconds"),
					resultSet.getInt("headway_seconds"),
					resultSet.getBoolean("exact_times")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT trip_id, origin_station_id, destination_station_id, adult_fare_won,
						currency, source_id, source_snapshot_id
					FROM transit_trip_official_fares
					ORDER BY trip_id, origin_station_id, destination_station_id
					""",
				(resultSet, rowNumber) -> new OfficialFare(
					resultSet.getString("trip_id"),
					resultSet.getString("origin_station_id"),
					resultSet.getString("destination_station_id"),
					resultSet.getInt("adult_fare_won"),
					resultSet.getString("currency"),
					resultSet.getString("source_id"),
					resultSet.getString("source_snapshot_id")
				)
			),
			loadFeedEndDate(),
			loadRouteAccessData()
		);
	}
	private RouteAccessData loadRouteAccessData() {
		return new RouteAccessData(
			jdbcTemplate.query(
				"""
					SELECT id, station_id, line_id, node_type
					FROM station_pathway_nodes
					ORDER BY id
					""",
				(resultSet, rowNumber) -> new PathwayNode(
					resultSet.getString("id"),
					resultSet.getString("station_id"),
					resultSet.getString("line_id"),
					resultSet.getString("node_type")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT id, from_node_id, to_node_id, duration_seconds, distance_meters,
						bidirectional, includes_stairs, reliability_score, accessibility_status,
						provenance_kind, verification_status, legacy_internal_route_edge_id
					FROM station_pathway_edges
					ORDER BY id
					""",
				(resultSet, rowNumber) -> new PathwayEdge(
					resultSet.getString("id"),
					resultSet.getString("from_node_id"),
					resultSet.getString("to_node_id"),
					resultSet.getInt("duration_seconds"),
					resultSet.getInt("distance_meters"),
					resultSet.getBoolean("bidirectional"),
					resultSet.getBoolean("includes_stairs"),
					resultSet.getInt("reliability_score"),
					resultSet.getString("accessibility_status"),
					resultSet.getString("provenance_kind"),
					resultSet.getString("verification_status"),
					resultSet.getString("legacy_internal_route_edge_id")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT id, from_station_id, from_line_id, to_station_id, to_line_id,
						transfer_type, min_transfer_seconds, pathway_edge_id,
						strict_step_free_pathway_edge_id, verification_status
					FROM transfer_rules
					ORDER BY id
					""",
				(resultSet, rowNumber) -> new TransferRule(
					resultSet.getString("id"),
					resultSet.getString("from_station_id"),
					resultSet.getString("from_line_id"),
					resultSet.getString("to_station_id"),
					resultSet.getString("to_line_id"),
					resultSet.getString("transfer_type"),
					resultSet.getInt("min_transfer_seconds"),
					resultSet.getString("pathway_edge_id"),
					resultSet.getString("strict_step_free_pathway_edge_id"),
					resultSet.getString("verification_status")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT id, station_id, line_id, edge_id, edge_type, provenance_kind,
						verification_status, strict_route_eligible, blocker_reason
					FROM route_edge_evidence
					ORDER BY station_id, line_id, edge_type, id
					""",
				(resultSet, rowNumber) -> new RouteEdgeEvidence(
					resultSet.getString("id"),
					resultSet.getString("station_id"),
					resultSet.getString("line_id"),
					resultSet.getString("edge_id"),
					resultSet.getString("edge_type"),
					resultSet.getString("provenance_kind"),
					resultSet.getString("verification_status"),
					resultSet.getBoolean("strict_route_eligible"),
					resultSet.getString("blocker_reason")
				)
			)
		);
	}

	private Optional<OffsetDateTime> freshOffsetDateTime(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		try {
			OffsetDateTime parsed = OffsetDateTime.parse(value);
			return parsed.toInstant().isAfter(clock.instant()) ? Optional.of(parsed) : Optional.empty();
		} catch (DateTimeParseException exception) {
			return Optional.empty();
		}
	}

	private record ItxArtifact(
		String snapshotSha256,
		String snapshotId,
		String freshUntil,
		PlannerIdentity plannerIdentity
	) {
	}

	private static String cacheKey(ItxArtifact artifact) {
		return artifact.snapshotSha256()
			+ artifact.plannerIdentity().canonicalPackSha256()
			+ artifact.freshUntil();
	}
	private LocalDate loadFeedEndDate() {
		List<LocalDate> rows = jdbcTemplate.query(
			"SELECT feed_end_date FROM transit_feed_info LIMIT 1",
			(resultSet, rowNumber) -> parseFeedEndDate(resultSet.getString("feed_end_date"))
		);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private static LocalDate parseFeedEndDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(value.trim(), DateTimeFormatter.BASIC_ISO_DATE);
		} catch (DateTimeParseException exception) {
			log.warn("transit_feed_info.feed_end_date 형식이 YYYYMMDD가 아니어서 무시한다: {}", value);
			return null;
		}
	}

	private static LocalDate serviceDate(String value) {
		return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
	}
}
