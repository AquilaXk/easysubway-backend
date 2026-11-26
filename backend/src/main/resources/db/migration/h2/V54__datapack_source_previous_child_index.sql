CREATE UNIQUE INDEX IF NOT EXISTS uq_data_source_snapshots_previous_child
	ON data_source_snapshots (previous_snapshot_id);
