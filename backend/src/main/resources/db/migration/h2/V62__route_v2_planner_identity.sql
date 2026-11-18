ALTER TABLE transit_trips ADD COLUMN train_no CHARACTER VARYING(20);

CREATE TABLE transit_trip_official_fares (
  trip_id CHARACTER VARYING(160) NOT NULL,
  origin_station_id CHARACTER VARYING(120) NOT NULL,
  destination_station_id CHARACTER VARYING(120) NOT NULL,
  adult_fare_won INT NOT NULL,
  currency CHAR(3) NOT NULL,
  source_id CHARACTER VARYING(120) NOT NULL,
  source_snapshot_id CHARACTER VARYING(200) NOT NULL,
  PRIMARY KEY (trip_id, origin_station_id, destination_station_id),
  FOREIGN KEY (trip_id) REFERENCES transit_trips(id),
  CONSTRAINT h2_transit_trip_fare_positive CHECK (adult_fare_won > 0),
  CONSTRAINT h2_transit_trip_fare_currency CHECK (currency = 'KRW')
);

CREATE INDEX idx_transit_trip_official_fares_od
  ON transit_trip_official_fares(origin_station_id, destination_station_id);
