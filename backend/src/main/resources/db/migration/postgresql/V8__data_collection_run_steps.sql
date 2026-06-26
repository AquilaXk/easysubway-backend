CREATE TABLE IF NOT EXISTS data_collection_run_steps (
	run_id VARCHAR(80) NOT NULL,
	step_order INTEGER NOT NULL,
	step_name VARCHAR(40) NOT NULL,
	status VARCHAR(30) NOT NULL,
	input_source VARCHAR(1000),
	artifact_reference VARCHAR(1000),
	checksum VARCHAR(64),
	record_count INTEGER NOT NULL DEFAULT 0,
	failure_message VARCHAR(1000),
	PRIMARY KEY (run_id, step_order),
	CONSTRAINT fk_data_collection_run_steps_run
		FOREIGN KEY (run_id) REFERENCES data_collection_runs(run_id)
		ON DELETE CASCADE ON UPDATE CASCADE
);
