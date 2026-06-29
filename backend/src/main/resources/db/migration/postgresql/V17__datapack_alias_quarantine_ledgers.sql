ALTER TABLE data_source_snapshots
	ADD CONSTRAINT uq_data_source_snapshots_snapshot_source UNIQUE (snapshot_id, source_id);

CREATE TABLE IF NOT EXISTS external_alias_approvals (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	source_id VARCHAR(120) NOT NULL,
	source_snapshot_id VARCHAR(120) NOT NULL,
	provider_entity_type VARCHAR(40) NOT NULL,
	provider_entity_id VARCHAR(200) NOT NULL,
	canonical_entity_type VARCHAR(40) NOT NULL,
	canonical_entity_id VARCHAR(200) NOT NULL,
	confidence INTEGER NOT NULL,
	match_method VARCHAR(40) NOT NULL,
	approval_status VARCHAR(30) NOT NULL,
	requested_by VARCHAR(120) NOT NULL,
	approved_by VARCHAR(120),
	approved_at TIMESTAMP,
	evidence_hash datapack_sha256 NOT NULL,
	superseded_by VARCHAR(120),
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_external_alias_approvals_snapshot_source
		FOREIGN KEY (source_snapshot_id, source_id) REFERENCES data_source_snapshots(snapshot_id, source_id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT fk_external_alias_approvals_superseded
		FOREIGN KEY (superseded_by) REFERENCES external_alias_approvals(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_external_alias_approvals_confidence
		CHECK (confidence BETWEEN 0 AND 100),
	CONSTRAINT chk_external_alias_approvals_approved_state
		CHECK (approval_status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_external_alias_approvals_active_provider
	ON external_alias_approvals (source_snapshot_id, provider_entity_type, provider_entity_id)
	WHERE approval_status = 'APPROVED' AND superseded_by IS NULL;

CREATE INDEX IF NOT EXISTS idx_external_alias_approvals_snapshot_status
	ON external_alias_approvals (source_snapshot_id ASC, approval_status ASC, created_at DESC);

CREATE TABLE IF NOT EXISTS source_quarantine_records (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	source_id VARCHAR(120) NOT NULL,
	source_snapshot_id VARCHAR(120) NOT NULL,
	provider_record_hash datapack_sha256 NOT NULL,
	reason_code VARCHAR(80) NOT NULL,
	severity VARCHAR(20) NOT NULL,
	redacted_excerpt TEXT,
	resolution_status VARCHAR(30) NOT NULL,
	resolved_by VARCHAR(120),
	resolved_at TIMESTAMP,
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_source_quarantine_records_snapshot_source
		FOREIGN KEY (source_snapshot_id, source_id) REFERENCES data_source_snapshots(snapshot_id, source_id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_source_quarantine_records_resolution_state
		CHECK (
			(resolution_status = 'OPEN' AND resolved_by IS NULL AND resolved_at IS NULL)
			OR (resolution_status <> 'OPEN' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
		)
);

CREATE INDEX IF NOT EXISTS idx_source_quarantine_records_snapshot_status
	ON source_quarantine_records (source_snapshot_id ASC, resolution_status ASC, severity ASC, created_at DESC);

CREATE TABLE IF NOT EXISTS source_quarantine_resolutions (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	quarantine_record_id VARCHAR(120) NOT NULL,
	resolution_status VARCHAR(30) NOT NULL,
	resolution_reason VARCHAR(1000) NOT NULL,
	resolved_by VARCHAR(120) NOT NULL,
	resolved_at TIMESTAMP NOT NULL,
	canonical_entity_type VARCHAR(40),
	canonical_entity_id VARCHAR(200),
	evidence_hash datapack_sha256,
	CONSTRAINT fk_source_quarantine_resolutions_record
		FOREIGN KEY (quarantine_record_id) REFERENCES source_quarantine_records(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_source_quarantine_resolutions_status
		CHECK (resolution_status IN ('ACCEPTED', 'REJECTED', 'ALIAS_APPROVED', 'SOURCE_FIXED', 'IGNORED'))
);

CREATE INDEX IF NOT EXISTS idx_source_quarantine_resolutions_record
	ON source_quarantine_resolutions (quarantine_record_id ASC, resolved_at DESC);
