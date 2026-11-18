ALTER TABLE route_service_artifact_evidence DROP CONSTRAINT h2_route_service_source_issue;
ALTER TABLE route_service_artifact_evidence ADD CONSTRAINT h2_route_service_source_issue
  CHECK (source_issue IN (2116, 2135));

CREATE TABLE timetable_snapshot_lock (
  singleton_id INTEGER PRIMARY KEY,
  CONSTRAINT h2_timetable_snapshot_lock_singleton CHECK (singleton_id = 1)
);

INSERT INTO timetable_snapshot_lock (singleton_id) VALUES (1);

CREATE TABLE timetable_snapshot_history (
  snapshot_sha256 CHARACTER VARYING(64) PRIMARY KEY,
  snapshot_id CHARACTER VARYING(120) NOT NULL UNIQUE,
  schema_identity CHARACTER VARYING(80) NOT NULL,
  fresh_until CHARACTER VARYING(40) NOT NULL,
  source_artifact_id CHARACTER VARYING(200) NOT NULL,
  source_artifact_sha256 CHARACTER VARYING(64) NOT NULL,
  completeness_evidence_sha256 CHARACTER VARYING(64) NOT NULL,
  canonical_pack_sha256 CHARACTER VARYING(64) NOT NULL,
  canonical_pack_sqlite_sha256 CHARACTER VARYING(64) NOT NULL,
  canonical_station_version CHARACTER VARYING(80) NOT NULL,
  canonical_station_set_sha256 CHARACTER VARYING(64) NOT NULL,
  canonical_station_member_count INTEGER NOT NULL,
  source_lineage_sha256 CHARACTER VARYING(64) NOT NULL,
  evidence_hash CHARACTER VARYING(64) NOT NULL,
  calendar_count INTEGER NOT NULL,
  route_count INTEGER NOT NULL,
  trip_count INTEGER NOT NULL,
  stop_time_count INTEGER NOT NULL,
  approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT h2_timetable_snapshot_hash CHECK (REGEXP_LIKE(snapshot_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_timetable_snapshot_source_hash CHECK (REGEXP_LIKE(source_artifact_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_timetable_snapshot_completeness_hash CHECK (REGEXP_LIKE(completeness_evidence_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_timetable_snapshot_pack_hash CHECK (REGEXP_LIKE(canonical_pack_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_timetable_snapshot_pack_sqlite_hash CHECK (REGEXP_LIKE(canonical_pack_sqlite_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_timetable_snapshot_station_hash CHECK (REGEXP_LIKE(canonical_station_set_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_timetable_snapshot_lineage_hash CHECK (REGEXP_LIKE(source_lineage_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_timetable_snapshot_evidence_hash CHECK (REGEXP_LIKE(evidence_hash, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_timetable_snapshot_counts CHECK (
    canonical_station_member_count > 0
    AND calendar_count > 0 AND route_count > 0 AND trip_count > 0 AND stop_time_count > 0
  )
);

CREATE TABLE timetable_snapshot_active (
  singleton_id INTEGER PRIMARY KEY,
  snapshot_sha256 CHARACTER VARYING(64) NOT NULL,
  activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT h2_timetable_snapshot_active_singleton CHECK (singleton_id = 1),
  CONSTRAINT h2_timetable_snapshot_active_history
    FOREIGN KEY (snapshot_sha256) REFERENCES timetable_snapshot_history(snapshot_sha256)
);
