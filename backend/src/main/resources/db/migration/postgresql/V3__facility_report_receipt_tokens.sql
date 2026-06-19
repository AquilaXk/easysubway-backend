-- Allow receipt-token based report status lookup without keeping photo payloads in the report row.

ALTER TABLE facility_reports
	ADD COLUMN IF NOT EXISTS client_submission_id VARCHAR(120);

ALTER TABLE facility_reports
	ADD COLUMN IF NOT EXISTS receipt_token_hash CHAR(64);

ALTER TABLE facility_reports
	ADD CONSTRAINT chk_facility_reports_receipt_token_hash
		CHECK (receipt_token_hash IS NULL OR receipt_token_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE facility_reports
	ADD CONSTRAINT chk_facility_reports_receipt_hash_requires_submission
		CHECK (receipt_token_hash IS NULL OR client_submission_id IS NOT NULL);

CREATE UNIQUE INDEX IF NOT EXISTS ux_facility_reports_client_submission
	ON facility_reports (client_submission_id)
	WHERE client_submission_id IS NOT NULL;
