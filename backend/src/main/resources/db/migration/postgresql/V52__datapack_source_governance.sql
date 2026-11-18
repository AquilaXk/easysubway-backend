ALTER TABLE data_source_snapshots
	ADD COLUMN coverage_count INTEGER,
	ADD COLUMN diff_summary_json JSONB,
	ADD COLUMN freshness_basis_at TIMESTAMP,
	ADD COLUMN provider_valid_until TIMESTAMP,
	ADD COLUMN governance_policy_version VARCHAR(32),
	ADD COLUMN governance_policy_sha256 datapack_sha256;

UPDATE data_source_snapshots
SET coverage_count = row_count
WHERE coverage_count IS NULL;

ALTER TABLE data_source_snapshots
	ADD CONSTRAINT chk_data_source_snapshots_coverage_count_not_null
		CHECK (coverage_count IS NOT NULL) NOT VALID,
	ADD CONSTRAINT chk_data_source_snapshots_coverage_count
		CHECK (coverage_count >= 0) NOT VALID,
	ADD CONSTRAINT chk_data_source_snapshots_governance_pair
		CHECK ((governance_policy_version IS NULL) = (governance_policy_sha256 IS NULL)) NOT VALID,
	ADD CONSTRAINT chk_data_source_snapshots_previous_not_self
		CHECK (previous_snapshot_id IS NULL OR previous_snapshot_id <> snapshot_id) NOT VALID,
	ADD CONSTRAINT fk_data_source_snapshots_previous_source
		FOREIGN KEY (previous_snapshot_id, source_id)
		REFERENCES data_source_snapshots(snapshot_id, source_id)
		ON DELETE RESTRICT ON UPDATE RESTRICT NOT VALID;

CREATE TABLE datapack_source_lineage_locks (
	source_id VARCHAR(120) PRIMARY KEY
);
