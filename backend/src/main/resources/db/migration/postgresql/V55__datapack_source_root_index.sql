CREATE UNIQUE INDEX CONCURRENTLY uq_data_source_snapshots_source_root
	ON data_source_snapshots (source_id)
	WHERE previous_snapshot_id IS NULL;
