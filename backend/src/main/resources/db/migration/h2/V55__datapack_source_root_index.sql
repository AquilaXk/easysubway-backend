CREATE UNIQUE INDEX IF NOT EXISTS uq_data_source_snapshots_source_root
	ON data_source_snapshots (lineage_root_source_id);
