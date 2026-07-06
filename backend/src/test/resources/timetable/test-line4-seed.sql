INSERT INTO transit_feed_info (id, feed_end_date) VALUES (1, '20261231');
INSERT INTO service_calendars (service_id, start_date, end_date, timezone, monday, tuesday, wednesday, thursday, friday, saturday, sunday) VALUES ('weekday-kric', '20260101', '20261231', 'Asia/Seoul', TRUE, TRUE, TRUE, TRUE, TRUE, FALSE, FALSE);
INSERT INTO transit_routes (id, timezone, line_id, route_short_name, route_long_name, direction_name) VALUES ('route-seoul-4-down', 'Asia/Seoul', 'seoul-4', '', '', 'down');
INSERT INTO transit_trips (id, route_id, service_id, service_pattern, service_day_start_seconds, trip_headsign, direction_id) VALUES ('trip-1', 'route-seoul-4-down', 'weekday-kric', 'LOCAL', 0, 'station-seoul-4-433', 'down');
INSERT INTO transit_stop_times (trip_id, stop_sequence, station_id, line_id, pickup_type, drop_off_type, arrival_seconds, departure_seconds) VALUES ('trip-1', 1, 'station-seoul-4-448', 'seoul-4', 0, 0, 25200, 25200);
INSERT INTO transit_stop_times (trip_id, stop_sequence, station_id, line_id, pickup_type, drop_off_type, arrival_seconds, departure_seconds) VALUES ('trip-1', 2, 'station-seoul-4-433', 'seoul-4', 0, 0, 27420, 27450);
