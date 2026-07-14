package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.Instant;
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
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
@Transactional(readOnly = true)
public class JdbcRouteTimetableRepository implements LoadRouteTimetablePort {

	private static final Logger log = LoggerFactory.getLogger(JdbcRouteTimetableRepository.class);

	private final JdbcTemplate jdbcTemplate;

	@Autowired
	public JdbcRouteTimetableRepository(DataSource dataSource) {
		this(new JdbcTemplate(dataSource));
	}

	JdbcRouteTimetableRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public boolean hasRouteTimetable() {
		String tripFilter = activeItxFreshUntil().isPresent()
			? ""
			: "AND t.service_class <> 'ITX_CHEONGCHUN'";
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"""
				SELECT CASE
					WHEN EXISTS (
						SELECT 1 FROM transit_trips t
						WHERE EXISTS (SELECT 1 FROM transit_stop_times s WHERE s.trip_id = t.id)
						%s
					)
					THEN TRUE ELSE FALSE
				END
				""".formatted(tripFilter),
			Boolean.class
		));
	}

	@Override
	public String timetableCacheKey() {
		return activeItxFreshUntil()
			.map(value -> "ITX_CHEONGCHUN:" + value.toInstant())
			.orElse("SUBWAY_ONLY");
	}

	@Override
	public RouteTimetable loadRouteTimetable() {
		boolean includeItx = activeItxFreshUntil().isPresent();
		String tripFilter = includeItx ? "" : "WHERE service_class <> 'ITX_CHEONGCHUN'";
		String childTripFilter = includeItx ? "" : """
			WHERE EXISTS (
				SELECT 1 FROM transit_trips t
				WHERE t.id = trip_id AND t.service_class <> 'ITX_CHEONGCHUN'
			)
			""";
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
					SELECT id, route_id, service_id, trip_headsign, direction_id, service_pattern, service_day_start_seconds
					FROM transit_trips
					%s
					ORDER BY id
					""".formatted(tripFilter),
				(resultSet, rowNumber) -> new TransitTrip(
					resultSet.getString("id"),
					resultSet.getString("route_id"),
					resultSet.getString("service_id"),
					resultSet.getString("trip_headsign"),
					resultSet.getString("direction_id"),
					resultSet.getString("service_pattern"),
					resultSet.getInt("service_day_start_seconds")
				)
			),
			jdbcTemplate.query(
				"""
					SELECT trip_id, stop_sequence, station_id, line_id, arrival_seconds, departure_seconds,
						pickup_type, drop_off_type
					FROM transit_stop_times
					%s
					ORDER BY trip_id, stop_sequence
					""".formatted(childTripFilter),
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
					%s
					ORDER BY trip_id, start_time_seconds
					""".formatted(childTripFilter),
				(resultSet, rowNumber) -> new TransitFrequency(
					resultSet.getString("trip_id"),
					resultSet.getInt("start_time_seconds"),
					resultSet.getInt("end_time_seconds"),
					resultSet.getInt("headway_seconds"),
					resultSet.getBoolean("exact_times")
				)
			),
			loadFeedEndDate()
		);
	}

	private Optional<OffsetDateTime> activeItxFreshUntil() {
		return jdbcTemplate.query(
			"""
				SELECT fresh_until
				FROM route_service_artifact_evidence
				WHERE service_class = 'ITX_CHEONGCHUN'
					AND admission_status = 'ADMITTED'
					AND admission_eligible = TRUE
					AND EXISTS (
						SELECT 1 FROM transit_trips
						WHERE service_class = 'ITX_CHEONGCHUN'
					)
				""",
			(resultSet, rowNumber) -> resultSet.getString("fresh_until")
		).stream().findFirst().flatMap(JdbcRouteTimetableRepository::freshOffsetDateTime);
	}

	private static Optional<OffsetDateTime> freshOffsetDateTime(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		try {
			OffsetDateTime parsed = OffsetDateTime.parse(value);
			return parsed.toInstant().isAfter(Instant.now()) ? Optional.of(parsed) : Optional.empty();
		} catch (DateTimeParseException exception) {
			return Optional.empty();
		}
	}

	private LocalDate loadFeedEndDate() {
		List<LocalDate> rows = jdbcTemplate.query(
			"SELECT feed_end_date FROM transit_feed_info LIMIT 1",
			(resultSet, rowNumber) -> parseFeedEndDate(resultSet.getString("feed_end_date"))
		);
		return rows.isEmpty() ? null : rows.get(0);
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
