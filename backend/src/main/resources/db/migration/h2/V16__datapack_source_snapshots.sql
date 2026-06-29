CREATE TABLE IF NOT EXISTS data_source_snapshots (
	snapshot_id VARCHAR(120) NOT NULL PRIMARY KEY,
	source_id VARCHAR(120) NOT NULL,
	provider VARCHAR(120) NOT NULL,
	retrieved_at TIMESTAMP NOT NULL,
	source_updated_at TIMESTAMP,
	row_count INTEGER NOT NULL,
	raw_sha256 VARCHAR(64) NOT NULL,
	raw_object_uri VARCHAR(1000) NOT NULL,
	redacted_request_fingerprint VARCHAR(64) NOT NULL,
	schema_fingerprint VARCHAR(64) NOT NULL,
	snapshot_status VARCHAR(30) NOT NULL,
	schema_status VARCHAR(30) NOT NULL,
	license_status VARCHAR(30) NOT NULL,
	fetch_status VARCHAR(30) NOT NULL,
	redistribution_allowed BOOLEAN NOT NULL,
	credential_redacted BOOLEAN NOT NULL,
	previous_snapshot_id VARCHAR(120),
	diff_summary VARCHAR(1000),
	freshness_expires_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_data_source_snapshots_previous
		FOREIGN KEY (previous_snapshot_id) REFERENCES data_source_snapshots(snapshot_id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_data_source_snapshots_row_count
		CHECK (row_count >= 0),
	CONSTRAINT chk_data_source_snapshots_raw_sha256
		CHECK (char_length(raw_sha256) = 64),
	CONSTRAINT chk_data_source_snapshots_request_fingerprint
		CHECK (char_length(redacted_request_fingerprint) = 64),
	CONSTRAINT chk_data_source_snapshots_schema_fingerprint
		CHECK (char_length(schema_fingerprint) = 64)
);

CREATE INDEX IF NOT EXISTS idx_data_source_snapshots_source_retrieved
	ON data_source_snapshots (source_id ASC, retrieved_at DESC, snapshot_id ASC);
