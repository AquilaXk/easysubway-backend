ALTER TABLE datapack_release_channel_events
	ADD COLUMN IF NOT EXISTS evidence_bundle_sha256 datapack_sha256;
