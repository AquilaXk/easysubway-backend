CREATE TABLE IF NOT EXISTS service_calendars (
  service_id VARCHAR(120) NOT NULL PRIMARY KEY,
  monday BOOLEAN NOT NULL,
  tuesday BOOLEAN NOT NULL,
  wednesday BOOLEAN NOT NULL,
  thursday BOOLEAN NOT NULL,
  friday BOOLEAN NOT NULL,
  saturday BOOLEAN NOT NULL,
  sunday BOOLEAN NOT NULL,
  start_date VARCHAR(8) NOT NULL,
  end_date VARCHAR(8) NOT NULL,
  timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
  CONSTRAINT chk_service_calendars_date_order CHECK (start_date <= end_date)
);

CREATE TABLE IF NOT EXISTS service_calendar_dates (
  service_id VARCHAR(120) NOT NULL,
  date VARCHAR(8) NOT NULL,
  exception_type INTEGER NOT NULL,
  PRIMARY KEY (service_id, date),
  CONSTRAINT fk_service_calendar_dates_service FOREIGN KEY (service_id) REFERENCES service_calendars(service_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_service_calendar_dates_exception CHECK (exception_type IN (1, 2))
);

CREATE TABLE IF NOT EXISTS transit_routes (
  id VARCHAR(120) NOT NULL PRIMARY KEY,
  line_id VARCHAR(120) NOT NULL,
  route_short_name VARCHAR(80) NOT NULL DEFAULT '',
  route_long_name VARCHAR(200) NOT NULL DEFAULT '',
  direction_name VARCHAR(120) NOT NULL DEFAULT '',
  timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul'
);

CREATE TABLE IF NOT EXISTS transit_trips (
  id VARCHAR(160) NOT NULL PRIMARY KEY,
  route_id VARCHAR(120) NOT NULL,
  service_id VARCHAR(120) NOT NULL,
  trip_headsign VARCHAR(200) NOT NULL DEFAULT '',
  direction_id VARCHAR(80) NOT NULL DEFAULT '',
  service_pattern VARCHAR(40) NOT NULL DEFAULT 'LOCAL',
  service_day_start_seconds INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT fk_transit_trips_route FOREIGN KEY (route_id) REFERENCES transit_routes(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_transit_trips_service FOREIGN KEY (service_id) REFERENCES service_calendars(service_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_transit_trips_pattern CHECK (service_pattern IN ('LOCAL', 'EXPRESS')),
  CONSTRAINT chk_transit_trips_service_day_start CHECK (service_day_start_seconds >= 0 AND service_day_start_seconds < 108000)
);

CREATE TABLE IF NOT EXISTS transit_stop_times (
  trip_id VARCHAR(160) NOT NULL,
  stop_sequence INTEGER NOT NULL,
  station_id VARCHAR(120) NOT NULL,
  line_id VARCHAR(120) NOT NULL,
  arrival_seconds INTEGER NOT NULL,
  departure_seconds INTEGER NOT NULL,
  pickup_type INTEGER NOT NULL DEFAULT 0,
  drop_off_type INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (trip_id, stop_sequence),
  CONSTRAINT fk_transit_stop_times_trip FOREIGN KEY (trip_id) REFERENCES transit_trips(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_transit_stop_times_sequence CHECK (stop_sequence > 0),
  CONSTRAINT chk_transit_stop_times_arrival CHECK (arrival_seconds >= 0 AND arrival_seconds < 108000),
  CONSTRAINT chk_transit_stop_times_departure CHECK (departure_seconds >= 0 AND departure_seconds < 108000),
  CONSTRAINT chk_transit_stop_times_order CHECK (arrival_seconds <= departure_seconds)
);

CREATE TABLE IF NOT EXISTS transit_frequencies (
  trip_id VARCHAR(160) NOT NULL,
  start_time_seconds INTEGER NOT NULL,
  end_time_seconds INTEGER NOT NULL,
  headway_seconds INTEGER NOT NULL,
  exact_times BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (trip_id, start_time_seconds),
  CONSTRAINT fk_transit_frequencies_trip FOREIGN KEY (trip_id) REFERENCES transit_trips(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_transit_frequencies_start CHECK (start_time_seconds >= 0 AND start_time_seconds < 108000),
  CONSTRAINT chk_transit_frequencies_end CHECK (end_time_seconds > start_time_seconds AND end_time_seconds < 108000),
  CONSTRAINT chk_transit_frequencies_headway CHECK (headway_seconds > 0)
);

CREATE INDEX IF NOT EXISTS idx_transit_stop_times_station_line_departure ON transit_stop_times(station_id, line_id, departure_seconds);
CREATE INDEX IF NOT EXISTS idx_transit_stop_times_trip_sequence ON transit_stop_times(trip_id, stop_sequence);
CREATE INDEX IF NOT EXISTS idx_transit_trips_route_service_pattern ON transit_trips(route_id, service_id, service_pattern);
