ALTER TABLE transit_trips ADD COLUMN service_class VARCHAR(40) NOT NULL DEFAULT 'SUBWAY';
ALTER TABLE transit_trips ADD CONSTRAINT chk_transit_trips_service_class
  CHECK (service_class IN ('SUBWAY', 'ITX_CHEONGCHUN')) NOT VALID;
ALTER TABLE transit_trips VALIDATE CONSTRAINT chk_transit_trips_service_class;

CREATE TABLE route_service_artifact_evidence (
  service_class VARCHAR(40) NOT NULL PRIMARY KEY,
  timetable_artifact_id VARCHAR(200) NOT NULL,
  timetable_artifact_sha256 VARCHAR(64) NOT NULL,
  canonical_pack_id VARCHAR(120) NOT NULL,
  canonical_pack_sha256 VARCHAR(64) NOT NULL,
  canonical_pack_sqlite_sha256 VARCHAR(64) NOT NULL,
  admission_status VARCHAR(20) NOT NULL,
  admission_eligible BOOLEAN NOT NULL,
  fresh_until VARCHAR(40),
  source_issue INTEGER NOT NULL,
  CONSTRAINT chk_route_service_class CHECK (service_class = 'ITX_CHEONGCHUN'),
  CONSTRAINT chk_route_service_timetable_hash CHECK (timetable_artifact_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT chk_route_service_pack_hash CHECK (canonical_pack_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT chk_route_service_pack_sqlite_hash CHECK (canonical_pack_sqlite_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT chk_route_service_admission CHECK (
    (admission_status = 'ADMITTED' AND admission_eligible = TRUE AND fresh_until IS NOT NULL)
    OR (admission_status = 'MISSING' AND admission_eligible = FALSE)
  ),
  CONSTRAINT chk_route_service_source_issue CHECK (source_issue = 2116)
);

CREATE INDEX idx_transit_trips_service_class ON transit_trips(service_class);
