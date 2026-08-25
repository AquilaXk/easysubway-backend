CREATE TABLE route_service_station_catalog_evidence (
  service_class VARCHAR(40) NOT NULL PRIMARY KEY,
  station_catalog_artifact_kind VARCHAR(80) NOT NULL,
  station_catalog_manifest_version INTEGER NOT NULL,
  station_catalog_pack_id VARCHAR(200) NOT NULL,
  station_catalog_station_set_sha256 VARCHAR(64) NOT NULL,
  station_catalog_payload_sha256 VARCHAR(64) NOT NULL,
  station_catalog_manifest_sha256 VARCHAR(64) NOT NULL,
  admission_status VARCHAR(20) NOT NULL,
  admission_eligible BOOLEAN NOT NULL,
  fresh_until VARCHAR(40),
  source_issue INTEGER NOT NULL,
  CONSTRAINT fk_route_service_station_catalog_service
    FOREIGN KEY (service_class) REFERENCES route_service_artifact_evidence(service_class),
  CONSTRAINT chk_route_service_station_catalog_class CHECK (service_class = 'ITX_CHEONGCHUN'),
  CONSTRAINT chk_route_service_station_catalog_kind CHECK (station_catalog_artifact_kind = 'station-catalog-pack'),
  CONSTRAINT chk_route_service_station_catalog_manifest_version CHECK (station_catalog_manifest_version = 1),
  CONSTRAINT chk_route_service_station_catalog_station_set_hash CHECK (station_catalog_station_set_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT chk_route_service_station_catalog_payload_hash CHECK (station_catalog_payload_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT chk_route_service_station_catalog_manifest_hash CHECK (station_catalog_manifest_sha256 ~ '^[0-9a-f]{64}$'),
  CONSTRAINT chk_route_service_station_catalog_admission CHECK (
    (admission_status = 'ADMITTED' AND admission_eligible = TRUE AND fresh_until IS NOT NULL)
    OR (admission_status = 'MISSING' AND admission_eligible = FALSE)
  ),
  CONSTRAINT chk_route_service_station_catalog_source_issue CHECK (source_issue = 2649)
);
