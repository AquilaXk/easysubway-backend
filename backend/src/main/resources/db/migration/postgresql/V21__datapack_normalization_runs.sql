CREATE TABLE IF NOT EXISTS datapack_normalization_runs (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	source_id VARCHAR(120) NOT NULL,
	source_snapshot_id VARCHAR(120) NOT NULL,
	normalized_count INTEGER NOT NULL,
	accepted_count INTEGER NOT NULL,
	quarantine_count INTEGER NOT NULL,
	alias_review_count INTEGER NOT NULL,
	schema_diff_sha256 datapack_sha256 NOT NULL,
	schema_diff_summary VARCHAR(1000) NOT NULL,
	status VARCHAR(30) NOT NULL,
	started_at TIMESTAMP NOT NULL,
	completed_at TIMESTAMP,
	CONSTRAINT fk_datapack_normalization_runs_snapshot_source
		FOREIGN KEY (source_snapshot_id, source_id) REFERENCES data_source_snapshots(snapshot_id, source_id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_datapack_normalization_runs_status
		CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
	CONSTRAINT chk_datapack_normalization_runs_counts
		CHECK (
			normalized_count >= 0
			AND accepted_count >= 0
			AND quarantine_count >= 0
			AND alias_review_count >= 0
			AND accepted_count + quarantine_count + alias_review_count <= normalized_count
		),
	CONSTRAINT chk_datapack_normalization_runs_finished_state
		CHECK ((completed_at IS NULL) = (status = 'RUNNING'))
);

CREATE INDEX IF NOT EXISTS idx_datapack_normalization_runs_snapshot_status
	ON datapack_normalization_runs (source_snapshot_id ASC, status ASC, started_at DESC);

CREATE TABLE IF NOT EXISTS datapack_normalized_outputs (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	normalization_run_id VARCHAR(120) NOT NULL,
	output_kind VARCHAR(40) NOT NULL,
	row_count INTEGER NOT NULL,
	output_sha256 datapack_sha256 NOT NULL,
	object_uri VARCHAR(1000) NOT NULL,
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_datapack_normalized_outputs_run
		FOREIGN KEY (normalization_run_id) REFERENCES datapack_normalization_runs(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_datapack_normalized_outputs_kind
		CHECK (output_kind IN ('ACCEPTED_ROWS', 'ALIAS_REVIEW_ROWS', 'QUARANTINE_ROWS', 'SCHEMA_DIFF')),
	CONSTRAINT chk_datapack_normalized_outputs_row_count
		CHECK (row_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_datapack_normalized_outputs_run_kind
	ON datapack_normalized_outputs (normalization_run_id ASC, output_kind ASC);
