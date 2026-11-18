package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test fixture reader: maps the canonical schedule tables of an H2 fixture database into a {@link RouteTimetable}.
 *
 * <p>#476 이후 운영 시간표는 활성 서버 경로 번들에서만 읽는다. 이 클래스는 line4 코리도 슬라이스 seed를 H2에 적재해 플래너
 * golden 테스트에 넘기는 테스트 전용 도구로만 남는다.</p>
 */
public class JdbcRouteTimetableRepository implements LoadRouteTimetablePort {

	private static final Logger log = LoggerFactory.getLogger(JdbcRouteTimetableRepository.class);

	private final JdbcTemplate jdbcTemplate;

	public JdbcRouteTimetableRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
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
					resultSet.getString("legacy_internal_route_edge_id"), null
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
