CREATE TABLE IF NOT EXISTS service_calendars (
  service_id CHARACTER VARYING(120) PRIMARY KEY NOT NULL,
  start_date CHAR(8) NOT NULL,
  end_date CHAR(8) NOT NULL,
  timezone CHARACTER VARYING(80) DEFAULT 'Asia/Seoul' NOT NULL,
  monday BOOL NOT NULL,
  tuesday BOOL NOT NULL,
  wednesday BOOL NOT NULL,
  thursday BOOL NOT NULL,
  friday BOOL NOT NULL,
  saturday BOOL NOT NULL,
  sunday BOOL NOT NULL,
  CONSTRAINT h2_service_calendar_valid_range CHECK (start_date <= end_date)
);

CREATE TABLE IF NOT EXISTS transit_routes (
  id CHARACTER VARYING(120) PRIMARY KEY NOT NULL,
  timezone CHARACTER VARYING(80) DEFAULT 'Asia/Seoul' NOT NULL,
  line_id CHARACTER VARYING(120) NOT NULL,
  route_short_name CHARACTER VARYING(80) DEFAULT '' NOT NULL,
  route_long_name CHARACTER VARYING(200) DEFAULT '' NOT NULL,
  direction_name CHARACTER VARYING(120) DEFAULT '' NOT NULL
);

CREATE TABLE IF NOT EXISTS service_calendar_dates (
  service_id CHARACTER VARYING(120) NOT NULL,
  date CHAR(8) NOT NULL,
  exception_type INT NOT NULL,
  PRIMARY KEY (service_id, date),
  FOREIGN KEY (service_id) REFERENCES service_calendars(service_id),
  CONSTRAINT h2_service_calendar_dates_exception CHECK (exception_type = 1 OR exception_type = 2)
);

CREATE TABLE IF NOT EXISTS transit_trips (
  id CHARACTER VARYING(160) PRIMARY KEY NOT NULL,
  route_id CHARACTER VARYING(120) NOT NULL,
  service_id CHARACTER VARYING(120) NOT NULL,
  service_pattern CHARACTER VARYING(40) DEFAULT 'LOCAL' NOT NULL,
  service_day_start_seconds INT DEFAULT 0 NOT NULL,
  trip_headsign CHARACTER VARYING(200) DEFAULT '' NOT NULL,
  direction_id CHARACTER VARYING(80) DEFAULT '' NOT NULL,
  FOREIGN KEY (route_id) REFERENCES transit_routes(id),
  FOREIGN KEY (service_id) REFERENCES service_calendars(service_id),
  CONSTRAINT h2_transit_trips_pattern CHECK (service_pattern = 'LOCAL' OR service_pattern = 'EXPRESS'),
  CONSTRAINT h2_transit_trips_day_start CHECK (service_day_start_seconds BETWEEN 0 AND 107999)
);

CREATE TABLE IF NOT EXISTS transit_stop_times (
  trip_id CHARACTER VARYING(160) NOT NULL,
  stop_sequence INT NOT NULL,
  station_id CHARACTER VARYING(120) NOT NULL,
  line_id CHARACTER VARYING(120) NOT NULL,
  pickup_type INT DEFAULT 0 NOT NULL,
  drop_off_type INT DEFAULT 0 NOT NULL,
  arrival_seconds INT NOT NULL,
  departure_seconds INT NOT NULL,
  PRIMARY KEY (trip_id, stop_sequence),
  FOREIGN KEY (trip_id) REFERENCES transit_trips(id),
  CONSTRAINT h2_transit_stop_sequence CHECK (stop_sequence >= 1),
  CONSTRAINT h2_transit_arrival_window CHECK (arrival_seconds BETWEEN 0 AND 107999),
  CONSTRAINT h2_transit_departure_window CHECK (departure_seconds BETWEEN 0 AND 107999),
  CONSTRAINT h2_transit_stop_time_order CHECK (arrival_seconds <= departure_seconds)
);

CREATE TABLE IF NOT EXISTS transit_frequencies (
  trip_id CHARACTER VARYING(160) NOT NULL,
  start_time_seconds INT NOT NULL,
  headway_seconds INT NOT NULL,
  end_time_seconds INT NOT NULL,
  exact_times BOOL DEFAULT FALSE NOT NULL,
  PRIMARY KEY (trip_id, start_time_seconds),
  FOREIGN KEY (trip_id) REFERENCES transit_trips(id),
  CONSTRAINT h2_transit_frequency_start CHECK (start_time_seconds BETWEEN 0 AND 107999),
  CONSTRAINT h2_transit_frequency_end CHECK (end_time_seconds > start_time_seconds AND end_time_seconds < 108000),
  CONSTRAINT h2_transit_frequency_headway CHECK (headway_seconds >= 1)
);

CREATE INDEX IF NOT EXISTS idx_transit_stop_times_station_line_departure ON transit_stop_times(station_id, line_id, departure_seconds);
CREATE INDEX IF NOT EXISTS idx_transit_stop_times_trip_sequence ON transit_stop_times(trip_id, stop_sequence);
CREATE INDEX IF NOT EXISTS idx_transit_trips_route_service_pattern ON transit_trips(route_id, service_id, service_pattern);
