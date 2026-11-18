CREATE TABLE IF NOT EXISTS route_edge_evidence (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	station_id VARCHAR(120) NOT NULL,
	line_id VARCHAR(120),
	edge_id VARCHAR(160) NOT NULL,
	edge_type VARCHAR(40) NOT NULL,
	source_id VARCHAR(120) NOT NULL,
	source_snapshot_id VARCHAR(120) NOT NULL,
	provenance_kind VARCHAR(40) NOT NULL,
	verification_status VARCHAR(30) NOT NULL,
	last_verified_at TIMESTAMP NOT NULL,
	evidence_hash datapack_sha256 NOT NULL,
	strict_route_eligible BOOLEAN NOT NULL,
	blocker_reason VARCHAR(120),
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_route_edge_evidence_snapshot_source
		FOREIGN KEY (source_snapshot_id, source_id) REFERENCES data_source_snapshots(snapshot_id, source_id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_route_edge_evidence_edge_type
		CHECK (edge_type IN ('ENTRY', 'EXIT', 'TRANSFER', 'GENERATED_CONNECTOR')),
	CONSTRAINT chk_route_edge_evidence_provenance
		CHECK (provenance_kind IN ('OFFICIAL_SOURCE', 'OPERATOR_CONFIRMED', 'FIELD_VERIFIED', 'GENERATED', 'UNKNOWN')),
	CONSTRAINT chk_route_edge_evidence_verification
		CHECK (verification_status IN ('VERIFIED', 'UNKNOWN', 'GENERATED', 'STALE', 'MISSING')),
	CONSTRAINT chk_route_edge_evidence_blocker
		CHECK (strict_route_eligible = FALSE OR blocker_reason IS NULL),
	CONSTRAINT chk_route_edge_evidence_strict_route
		CHECK (
			strict_route_eligible = FALSE
			OR (
				edge_type IN ('ENTRY', 'EXIT', 'TRANSFER')
				AND provenance_kind IN ('OFFICIAL_SOURCE', 'OPERATOR_CONFIRMED', 'FIELD_VERIFIED')
				AND verification_status = 'VERIFIED'
			)
		)
);

CREATE INDEX IF NOT EXISTS idx_route_edge_evidence_station_gate
	ON route_edge_evidence (station_id ASC, line_id ASC, edge_type ASC, verification_status ASC, created_at DESC);
