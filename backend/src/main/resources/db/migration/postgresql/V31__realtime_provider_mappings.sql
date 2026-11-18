CREATE TABLE IF NOT EXISTS realtime_provider_line_mappings (
  provider_id VARCHAR(80) NOT NULL,
  provider_line_id VARCHAR(80) NOT NULL,
  line_id VARCHAR(80) NOT NULL,
  provider_line_name VARCHAR(120) NOT NULL DEFAULT '',
  supports_arrivals BOOLEAN NOT NULL DEFAULT FALSE,
  supports_train_positions BOOLEAN NOT NULL DEFAULT FALSE,
  mapping_confidence VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
  provider_priority INTEGER NOT NULL DEFAULT 100,
  coverage_region VARCHAR(80) NOT NULL DEFAULT '',
  valid_from TIMESTAMPTZ,
  valid_until TIMESTAMPTZ,
  cache_version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (provider_id, provider_line_id),
  UNIQUE (provider_id, line_id),
  CONSTRAINT chk_realtime_line_mapping_confidence CHECK (mapping_confidence IN ('OFFICIAL', 'MANUAL', 'HEURISTIC', 'UNKNOWN')),
  CONSTRAINT chk_realtime_line_cache_version CHECK (cache_version > 0)
);

CREATE TABLE IF NOT EXISTS realtime_provider_station_mappings (
  provider_id VARCHAR(80) NOT NULL,
  provider_line_id VARCHAR(80) NOT NULL,
  provider_station_id VARCHAR(80) NOT NULL,
  station_id VARCHAR(120) NOT NULL,
  line_id VARCHAR(80) NOT NULL,
  query_name VARCHAR(120) NOT NULL DEFAULT '',
  supports_arrivals BOOLEAN NOT NULL DEFAULT FALSE,
  supports_train_positions BOOLEAN NOT NULL DEFAULT FALSE,
  mapping_confidence VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
  cache_version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (provider_id, provider_line_id, provider_station_id),
  UNIQUE (provider_id, line_id, station_id),
  FOREIGN KEY (provider_id, provider_line_id) REFERENCES realtime_provider_line_mappings(provider_id, provider_line_id),
  CONSTRAINT chk_realtime_station_mapping_confidence CHECK (mapping_confidence IN ('OFFICIAL', 'MANUAL', 'HEURISTIC', 'UNKNOWN')),
  CONSTRAINT chk_realtime_station_cache_version CHECK (cache_version > 0)
);

CREATE INDEX IF NOT EXISTS idx_realtime_provider_station_internal
  ON realtime_provider_station_mappings(provider_id, station_id, line_id);

INSERT INTO realtime_provider_line_mappings (
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
) VALUES (
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
) ON CONFLICT (provider_id, provider_line_id) DO NOTHING;

INSERT INTO realtime_provider_station_mappings (
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
) VALUES (
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
) ON CONFLICT (provider_id, provider_line_id, provider_station_id) DO NOTHING;
