CREATE TABLE IF NOT EXISTS realtime_provider_trip_mappings (
  provider_id VARCHAR(80) NOT NULL,
  provider_line_id VARCHAR(80) NOT NULL,
  line_id VARCHAR(80) NOT NULL,
  raw_direction VARCHAR(120) NOT NULL DEFAULT '',
  canonical_direction VARCHAR(120) NOT NULL DEFAULT '',
  raw_destination VARCHAR(120) NOT NULL DEFAULT '',
  canonical_destination VARCHAR(120) NOT NULL DEFAULT '',
  raw_service_pattern VARCHAR(80) NOT NULL DEFAULT '',
  canonical_service_pattern VARCHAR(80) NOT NULL DEFAULT '',
  mapping_confidence VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
  valid_from TIMESTAMPTZ,
  valid_until TIMESTAMPTZ,
  cache_version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (
    provider_id,
    provider_line_id,
    raw_direction,
    raw_destination,
    raw_service_pattern
  ),
  FOREIGN KEY (provider_id, provider_line_id) REFERENCES realtime_provider_line_mappings(provider_id, provider_line_id),
  CONSTRAINT chk_realtime_trip_mapping_confidence CHECK (mapping_confidence IN ('OFFICIAL', 'MANUAL', 'HEURISTIC', 'UNKNOWN')),
  CONSTRAINT chk_realtime_trip_cache_version CHECK (cache_version > 0)
);

CREATE INDEX IF NOT EXISTS idx_realtime_provider_trip_canonical
  ON realtime_provider_trip_mappings(provider_id, line_id, provider_line_id);

INSERT INTO realtime_provider_trip_mappings (
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
) VALUES
  ('seoul-topis', '1004', 'seoul-4', '상행', '당고개 방면', '당고개', '당고개', '', '', 'OFFICIAL', 1),
  ('seoul-topis', '1004', 'seoul-4', '하행', '오이도 방면', '오이도', '오이도', '', '', 'OFFICIAL', 1)
ON CONFLICT (
  provider_id,
  provider_line_id,
  raw_direction,
  raw_destination,
  raw_service_pattern
) DO NOTHING;
