CREATE TABLE IF NOT EXISTS realtime_provider_line_mappings (
  provider_id CHARACTER VARYING(80) NOT NULL,
  provider_line_id CHARACTER VARYING(80) NOT NULL,
  line_id CHARACTER VARYING(80) NOT NULL,
  provider_line_name CHARACTER VARYING(120) DEFAULT '' NOT NULL,
  supports_arrivals BOOL DEFAULT FALSE NOT NULL,
  supports_train_positions BOOL DEFAULT FALSE NOT NULL,
  mapping_confidence CHARACTER VARYING(40) DEFAULT 'UNKNOWN' NOT NULL,
  provider_priority INT DEFAULT 100 NOT NULL,
  coverage_region CHARACTER VARYING(80) DEFAULT '' NOT NULL,
  valid_from TIMESTAMP WITH TIME ZONE,
  valid_until TIMESTAMP WITH TIME ZONE,
  cache_version BIGINT DEFAULT 1 NOT NULL,
  PRIMARY KEY (provider_id, provider_line_id),
  UNIQUE (provider_id, line_id),
  CONSTRAINT h2_realtime_line_mapping_confidence CHECK (mapping_confidence IN ('OFFICIAL', 'MANUAL', 'HEURISTIC', 'UNKNOWN')),
  CONSTRAINT h2_realtime_line_cache_version CHECK (cache_version > 0)
);

CREATE TABLE IF NOT EXISTS realtime_provider_station_mappings (
  provider_id CHARACTER VARYING(80) NOT NULL,
  provider_line_id CHARACTER VARYING(80) NOT NULL,
  provider_station_id CHARACTER VARYING(80) NOT NULL,
  station_id CHARACTER VARYING(120) NOT NULL,
  line_id CHARACTER VARYING(80) NOT NULL,
  query_name CHARACTER VARYING(120) DEFAULT '' NOT NULL,
  supports_arrivals BOOL DEFAULT FALSE NOT NULL,
  supports_train_positions BOOL DEFAULT FALSE NOT NULL,
  mapping_confidence CHARACTER VARYING(40) DEFAULT 'UNKNOWN' NOT NULL,
  cache_version BIGINT DEFAULT 1 NOT NULL,
  PRIMARY KEY (provider_id, provider_line_id, provider_station_id),
  UNIQUE (provider_id, line_id, station_id),
  FOREIGN KEY (provider_id, provider_line_id) REFERENCES realtime_provider_line_mappings(provider_id, provider_line_id),
  CONSTRAINT h2_realtime_station_mapping_confidence CHECK (mapping_confidence IN ('OFFICIAL', 'MANUAL', 'HEURISTIC', 'UNKNOWN')),
  CONSTRAINT h2_realtime_station_cache_version CHECK (cache_version > 0)
);

CREATE INDEX IF NOT EXISTS idx_realtime_provider_station_internal
  ON realtime_provider_station_mappings(provider_id, station_id, line_id);

MERGE INTO realtime_provider_line_mappings (
  provider_id,
  provider_line_id,
  line_id,
  provider_line_name,
  supports_arrivals,
  supports_train_positions,
  mapping_confidence,
  provider_priority,
  coverage_region,
  cache_version
) KEY (provider_id, provider_line_id) VALUES (
  'seoul-topis',
  '1004',
  'seoul-4',
  '4호선',
  TRUE,
  TRUE,
  'OFFICIAL',
  10,
  'capital',
  1
);

MERGE INTO realtime_provider_station_mappings (
  provider_id,
  provider_line_id,
  provider_station_id,
  station_id,
  line_id,
  query_name,
  supports_arrivals,
  supports_train_positions,
  mapping_confidence,
  cache_version
) KEY (provider_id, provider_line_id, provider_station_id) VALUES (
  'seoul-topis',
  '1004',
  '1004000448',
  'station-sangnoksu',
  'seoul-4',
  '상록수',
  TRUE,
  TRUE,
  'OFFICIAL',
  1
);
