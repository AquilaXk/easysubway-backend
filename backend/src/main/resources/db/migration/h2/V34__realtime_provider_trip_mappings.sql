CREATE TABLE IF NOT EXISTS realtime_provider_trip_mappings (
  provider_id CHARACTER VARYING(80) NOT NULL,
  provider_line_id CHARACTER VARYING(80) NOT NULL,
  line_id CHARACTER VARYING(80) NOT NULL,
  raw_direction CHARACTER VARYING(120) DEFAULT '' NOT NULL,
  canonical_direction CHARACTER VARYING(120) DEFAULT '' NOT NULL,
  raw_destination CHARACTER VARYING(120) DEFAULT '' NOT NULL,
  canonical_destination CHARACTER VARYING(120) DEFAULT '' NOT NULL,
  raw_service_pattern CHARACTER VARYING(80) DEFAULT '' NOT NULL,
  canonical_service_pattern CHARACTER VARYING(80) DEFAULT '' NOT NULL,
  mapping_confidence CHARACTER VARYING(40) DEFAULT 'UNKNOWN' NOT NULL,
  valid_from TIMESTAMP WITH TIME ZONE,
  valid_until TIMESTAMP WITH TIME ZONE,
  cache_version BIGINT DEFAULT 1 NOT NULL,
  PRIMARY KEY (
    provider_id,
    provider_line_id,
    raw_direction,
    raw_destination,
    raw_service_pattern
  ),
  FOREIGN KEY (provider_id, provider_line_id) REFERENCES realtime_provider_line_mappings(provider_id, provider_line_id),
  CONSTRAINT h2_realtime_trip_mapping_confidence CHECK (mapping_confidence IN ('OFFICIAL', 'MANUAL', 'HEURISTIC', 'UNKNOWN')),
  CONSTRAINT h2_realtime_trip_cache_version CHECK (cache_version > 0)
);

CREATE INDEX IF NOT EXISTS idx_realtime_provider_trip_canonical
  ON realtime_provider_trip_mappings(provider_id, line_id, provider_line_id);

MERGE INTO realtime_provider_trip_mappings (
  provider_id,
  provider_line_id,
  line_id,
  raw_direction,
  canonical_direction,
  raw_destination,
  canonical_destination,
  raw_service_pattern,
  canonical_service_pattern,
  mapping_confidence,
  cache_version
) KEY (
  provider_id,
  provider_line_id,
  raw_direction,
  raw_destination,
  raw_service_pattern
) VALUES
  ('seoul-topis', '1004', 'seoul-4', '상행', '당고개 방면', '당고개', '당고개', '', '', 'OFFICIAL', 1),
  ('seoul-topis', '1004', 'seoul-4', '하행', '오이도 방면', '오이도', '오이도', '', '', 'OFFICIAL', 1);
