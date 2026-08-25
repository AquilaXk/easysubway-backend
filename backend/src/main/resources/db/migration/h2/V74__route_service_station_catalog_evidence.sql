CREATE TABLE route_service_station_catalog_evidence (
  service_class CHARACTER VARYING(40) PRIMARY KEY NOT NULL,
  station_catalog_artifact_kind CHARACTER VARYING(80) NOT NULL,
  station_catalog_manifest_version INTEGER NOT NULL,
  station_catalog_pack_id CHARACTER VARYING(200) NOT NULL,
  station_catalog_station_set_sha256 CHARACTER VARYING(64) NOT NULL,
  station_catalog_payload_sha256 CHARACTER VARYING(64) NOT NULL,
  station_catalog_manifest_sha256 CHARACTER VARYING(64) NOT NULL,
  admission_status CHARACTER VARYING(20) NOT NULL,
  admission_eligible BOOL NOT NULL,
  fresh_until CHARACTER VARYING(40),
  source_issue INT NOT NULL,
  CONSTRAINT h2_route_service_station_catalog_service
    FOREIGN KEY (service_class) REFERENCES route_service_artifact_evidence(service_class),
  CONSTRAINT h2_route_service_station_catalog_class CHECK (service_class = 'ITX_CHEONGCHUN'),
  CONSTRAINT h2_route_service_station_catalog_kind CHECK (station_catalog_artifact_kind = 'station-catalog-pack'),
  CONSTRAINT h2_route_service_station_catalog_manifest_version CHECK (station_catalog_manifest_version = 1),
  CONSTRAINT h2_route_service_station_catalog_station_set_hash CHECK (REGEXP_LIKE(station_catalog_station_set_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_route_service_station_catalog_payload_hash CHECK (REGEXP_LIKE(station_catalog_payload_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_route_service_station_catalog_manifest_hash CHECK (REGEXP_LIKE(station_catalog_manifest_sha256, '^[0-9a-f]{64}$')),
  CONSTRAINT h2_route_service_station_catalog_admission CHECK (
    (admission_status = 'ADMITTED' AND admission_eligible = TRUE AND fresh_until IS NOT NULL)
    OR (admission_status = 'MISSING' AND admission_eligible = FALSE)
  ),
  CONSTRAINT h2_route_service_station_catalog_source_issue CHECK (source_issue = 2649)
);
