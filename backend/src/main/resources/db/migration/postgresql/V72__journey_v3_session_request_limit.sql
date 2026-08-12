ALTER TABLE journey_v3_sessions
	ADD COLUMN request_count INTEGER NOT NULL DEFAULT 0,
	ADD CONSTRAINT chk_journey_v3_session_request_count
		CHECK (request_count >= 0 AND request_count <= 50) NOT VALID;
