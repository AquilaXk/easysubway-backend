ALTER TABLE transit_trips ADD COLUMN service_class CHARACTER VARYING(40) DEFAULT 'SUBWAY' NOT NULL;
ALTER TABLE transit_trips ADD CONSTRAINT h2_transit_trips_service_class
  CHECK (service_class = 'SUBWAY' OR service_class = 'ITX_CHEONGCHUN');

CREATE TABLE route_service_artifact_evidence (
  service_class CHARACTER VARYING(40) PRIMARY KEY NOT NULL,
  timetable_artifact_id CHARACTER VARYING(200) NOT NULL,
  timetable_artifact_sha256 CHARACTER VARYING(64) NOT NULL,
  canonical_pack_id CHARACTER VARYING(120) NOT NULL,
  canonical_pack_sha256 CHARACTER VARYING(64) NOT NULL,
  canonical_pack_sqlite_sha256 CHARACTER VARYING(64) NOT NULL,
  admission_status CHARACTER VARYING(20) NOT NULL,
  admission_eligible BOOL NOT NULL,
  fresh_until CHARACTER VARYING(40),
  source_issue INT NOT NULL,
  CONSTRAINT h2_route_service_class CHECK (service_class = 'ITX_CHEONGCHUN'),
  CONSTRAINT h2_route_service_timetable_hash CHECK (REGEXP_LIKE(timetable_artifact_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_route_service_pack_hash CHECK (REGEXP_LIKE(canonical_pack_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_route_service_pack_sqlite_hash CHECK (REGEXP_LIKE(canonical_pack_sqlite_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_route_service_admission CHECK (
    (admission_status = 'ADMITTED' AND admission_eligible = TRUE AND fresh_until IS NOT NULL)
    OR (admission_status = 'MISSING' AND admission_eligible = FALSE)
  ),
  CONSTRAINT h2_route_service_source_issue CHECK (source_issue = 2116)
);

CREATE INDEX idx_transit_trips_service_class ON transit_trips(service_class);
