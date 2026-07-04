package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import com.easysubway.route.application.port.out.LoadRouteTimetablePort.RouteTimetable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("prod | staging | release | prod-like")
@Transactional(readOnly = true)
public class JdbcRouteTimetableRepository implements LoadRouteTimetablePort {

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
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"""
				SELECT CASE
					WHEN EXISTS (SELECT 1 FROM transit_trips)
						AND EXISTS (SELECT 1 FROM transit_stop_times)
					THEN TRUE ELSE FALSE
				END
				""",
			Boolean.class
		));
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
					SELECT id, route_id, service_id, trip_headsign, direction_id, service_pattern, service_day_start_seconds
					FROM transit_trips
					ORDER BY id
					""",
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
			)
		);
	}

	private static LocalDate serviceDate(String value) {
		return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
	}
}
