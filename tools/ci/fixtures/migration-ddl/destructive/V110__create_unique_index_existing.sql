CREATE UNIQUE INDEX ux_data_collection_runs_running_source
    ON data_collection_runs (source)
    WHERE status = 'RUNNING';
