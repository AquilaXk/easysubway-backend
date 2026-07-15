CREATE UNIQUE INDEX CONCURRENTLY uq_data_source_snapshots_previous_child
	ON data_source_snapshots (previous_snapshot_id)
	WHERE previous_snapshot_id IS NOT NULL;
