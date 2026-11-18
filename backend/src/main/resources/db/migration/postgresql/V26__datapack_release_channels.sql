CREATE TABLE IF NOT EXISTS datapack_release_channels (
	channel VARCHAR(40) NOT NULL PRIMARY KEY,
	candidate_id VARCHAR(120) NOT NULL,
	manifest_url VARCHAR(1000) NOT NULL,
	manifest_sha256 datapack_sha256 NOT NULL,
	previous_stable_candidate_id VARCHAR(120),
	previous_manifest_sha256 datapack_sha256,
	rollback_available BOOLEAN NOT NULL,
	last_operation_type VARCHAR(30) NOT NULL,
	last_operation_status VARCHAR(30) NOT NULL,
	requested_by VARCHAR(120) NOT NULL,
	approved_by VARCHAR(120) NOT NULL,
	reason VARCHAR(500) NOT NULL,
	idempotency_key VARCHAR(160) NOT NULL,
	updated_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_datapack_release_channels_candidate
		FOREIGN KEY (candidate_id) REFERENCES datapack_candidates(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT fk_datapack_release_channels_previous_candidate
		FOREIGN KEY (previous_stable_candidate_id) REFERENCES datapack_candidates(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_datapack_release_channels_channel
		CHECK (channel IN ('dev', 'staging', 'production')),
	CONSTRAINT chk_datapack_release_channels_operation
		CHECK (
			last_operation_type IN ('PROMOTE', 'ROLLBACK')
			AND last_operation_status IN ('PASS', 'FAIL', 'PENDING')
		),
	CONSTRAINT chk_datapack_release_channels_rollback_target
		CHECK (
			rollback_available = FALSE
			OR (previous_stable_candidate_id IS NOT NULL AND previous_manifest_sha256 IS NOT NULL)
		)
);

CREATE TABLE IF NOT EXISTS datapack_release_channel_events (
	id VARCHAR(120) NOT NULL PRIMARY KEY,
	channel VARCHAR(40) NOT NULL,
	previous_candidate_id VARCHAR(120),
	next_candidate_id VARCHAR(120) NOT NULL,
	previous_manifest_sha256 datapack_sha256,
	next_manifest_sha256 datapack_sha256 NOT NULL,
	operation_type VARCHAR(30) NOT NULL,
	operation_status VARCHAR(30) NOT NULL,
	requested_by VARCHAR(120) NOT NULL,
	approved_by VARCHAR(120) NOT NULL,
	reason VARCHAR(500) NOT NULL,
	idempotency_key VARCHAR(160) NOT NULL,
	workflow_run_url VARCHAR(1000),
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT fk_datapack_release_channel_events_channel
		FOREIGN KEY (channel) REFERENCES datapack_release_channels(channel)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT fk_datapack_release_channel_events_previous_candidate
		FOREIGN KEY (previous_candidate_id) REFERENCES datapack_candidates(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT fk_datapack_release_channel_events_next_candidate
		FOREIGN KEY (next_candidate_id) REFERENCES datapack_candidates(id)
		ON DELETE RESTRICT ON UPDATE RESTRICT,
	CONSTRAINT chk_datapack_release_channel_events_operation
		CHECK (
			operation_type IN ('PROMOTE', 'ROLLBACK')
			AND operation_status IN ('PASS', 'FAIL', 'PENDING')
		)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_datapack_release_channel_events_idempotency
	ON datapack_release_channel_events (channel ASC, idempotency_key ASC);
