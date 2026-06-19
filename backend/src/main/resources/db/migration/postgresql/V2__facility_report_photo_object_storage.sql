-- Move facility report photo payloads out of the relational row and keep only object metadata.

ALTER TABLE facility_reports
	ADD COLUMN IF NOT EXISTS photo_object_key VARCHAR(255);

ALTER TABLE facility_reports
	ADD COLUMN IF NOT EXISTS photo_thumbnail_object_key VARCHAR(255);

ALTER TABLE facility_reports
	ADD COLUMN IF NOT EXISTS photo_sha256 CHAR(64);

ALTER TABLE facility_reports
	ADD COLUMN IF NOT EXISTS photo_size_bytes BIGINT;

ALTER TABLE facility_reports
	ADD CONSTRAINT chk_facility_reports_photo_sha256
		CHECK (photo_sha256 IS NULL OR photo_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE facility_reports
	ADD CONSTRAINT chk_facility_reports_photo_size
		CHECK (photo_size_bytes IS NULL OR photo_size_bytes BETWEEN 1 AND 921600);

CREATE INDEX IF NOT EXISTS idx_facility_reports_photo_object
	ON facility_reports (photo_object_key)
	WHERE photo_object_key IS NOT NULL;

-- Keep photo_data_base64 until object backfill/export has copied legacy payloads into object storage.
