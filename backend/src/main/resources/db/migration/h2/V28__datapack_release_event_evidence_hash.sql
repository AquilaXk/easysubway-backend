ALTER TABLE datapack_release_channel_events
	ADD COLUMN IF NOT EXISTS evidence_bundle_sha256 VARCHAR(64);

ALTER TABLE datapack_release_channel_events
	ADD CONSTRAINT chk_datapack_release_channel_events_evidence_hash
	CHECK (evidence_bundle_sha256 IS NULL OR char_length(evidence_bundle_sha256) = 64);
