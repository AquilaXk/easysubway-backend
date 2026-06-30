CREATE TABLE IF NOT EXISTS datapack_source_snapshot_events (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	source_id VARCHAR(120) NOT NULL,
	snapshot_id VARCHAR(120) NOT NULL,
	operation_type VARCHAR(40) NOT NULL,
	operation_status VARCHAR(30) NOT NULL,
	requested_by VARCHAR(120) NOT NULL,
	reason VARCHAR(500) NOT NULL,
	idempotency_key VARCHAR(160) NOT NULL,
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_datapack_source_snapshot_events_snapshot
		FOREIGN KEY (snapshot_id) REFERENCES data_source_snapshots(snapshot_id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_datapack_source_snapshot_events_operation
		CHECK (
			operation_type IN ('CREATE_LOCKED')
			AND operation_status IN ('PASS', 'FAIL', 'PENDING')
		)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_datapack_source_snapshot_events_idempotency
	ON datapack_source_snapshot_events (source_id ASC, idempotency_key ASC);
