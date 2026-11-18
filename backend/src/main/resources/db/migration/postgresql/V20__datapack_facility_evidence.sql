CREATE TABLE IF NOT EXISTS facility_evidence (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	station_id VARCHAR(120) NOT NULL,
	line_id VARCHAR(120),
	facility_type VARCHAR(40) NOT NULL,
	evidence_kind VARCHAR(40) NOT NULL,
	source_id VARCHAR(120) NOT NULL,
	source_snapshot_id VARCHAR(120) NOT NULL,
	provider_record_hash datapack_sha256 NOT NULL,
	status_meaning VARCHAR(40) NOT NULL,
	installation_status VARCHAR(40) NOT NULL,
	operational_status VARCHAR(40) NOT NULL,
	verified_at TIMESTAMP NOT NULL,
	retrieved_at TIMESTAMP NOT NULL,
	freshness_expires_at TIMESTAMP NOT NULL,
	confidence INTEGER NOT NULL,
	strict_route_eligible BOOLEAN NOT NULL,
	strict_route_eligible_reason VARCHAR(120),
	conflict_status VARCHAR(30) NOT NULL,
	manual_override_id VARCHAR(120),
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_facility_evidence_snapshot_source
		FOREIGN KEY (source_snapshot_id, source_id) REFERENCES data_source_snapshots(snapshot_id, source_id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT fk_facility_evidence_manual_override
		FOREIGN KEY (manual_override_id) REFERENCES manual_overrides(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_facility_evidence_type
		CHECK (facility_type IN ('ELEVATOR', 'ESCALATOR', 'WHEELCHAIR_LIFT', 'DISABLED_TOILET')),
	CONSTRAINT chk_facility_evidence_kind
		CHECK (evidence_kind IN ('EXISTS', 'NOT_INSTALLED', 'NO_OFFICIAL_RECORD', 'UNKNOWN_PENDING_REVIEW')),
	CONSTRAINT chk_facility_evidence_installation
		CHECK (installation_status IN ('INSTALLED', 'NOT_INSTALLED', 'NO_OFFICIAL_RECORD', 'UNKNOWN')),
	CONSTRAINT chk_facility_evidence_operational
		CHECK (operational_status IN ('AVAILABLE', 'UNAVAILABLE', 'UNKNOWN', 'CHECK_REQUIRED')),
	CONSTRAINT chk_facility_evidence_status_meaning
		CHECK (status_meaning IN ('STATIC_LOCATION', 'INSTALLATION_STATE', 'REALTIME_OPERATION', 'OPERATOR_CONFIRMED', 'FIELD_VERIFIED')),
	CONSTRAINT chk_facility_evidence_confidence
		CHECK (confidence BETWEEN 0 AND 100),
	CONSTRAINT chk_facility_evidence_conflict
		CHECK (conflict_status IN ('NONE', 'RESOLVED', 'UNRESOLVED')),
	CONSTRAINT chk_facility_evidence_strict_reason
		CHECK (strict_route_eligible = FALSE OR strict_route_eligible_reason IS NULL),
	CONSTRAINT chk_facility_evidence_strict_route
		CHECK (
			strict_route_eligible = FALSE
			OR (
				evidence_kind = 'EXISTS'
				AND installation_status = 'INSTALLED'
				AND operational_status = 'AVAILABLE'
				AND status_meaning IN ('REALTIME_OPERATION', 'OPERATOR_CONFIRMED', 'FIELD_VERIFIED')
				AND conflict_status <> 'UNRESOLVED'
			)
		)
);

CREATE INDEX IF NOT EXISTS idx_facility_evidence_station_matrix
	ON facility_evidence (station_id ASC, line_id ASC, facility_type ASC, evidence_kind ASC, created_at DESC);
