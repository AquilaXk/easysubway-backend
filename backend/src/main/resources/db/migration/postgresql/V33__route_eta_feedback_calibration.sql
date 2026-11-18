ALTER TABLE route_feedbacks
	ADD COLUMN IF NOT EXISTS itinerary_id VARCHAR(120),
	ADD COLUMN IF NOT EXISTS mobility_type VARCHAR(40),
	ADD COLUMN IF NOT EXISTS constraint_mode VARCHAR(40),
	ADD COLUMN IF NOT EXISTS eta_source VARCHAR(40),
	ADD COLUMN IF NOT EXISTS eta_offset_bucket VARCHAR(40),
	ADD COLUMN IF NOT EXISTS eta_feedback_opted_in BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_route_feedbacks_eta_calibration
	ON route_feedbacks (mobility_type, constraint_mode, eta_source, eta_offset_bucket)
	WHERE eta_feedback_opted_in = TRUE;
