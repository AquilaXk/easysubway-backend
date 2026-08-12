CREATE TABLE journey_v3_nonce_claims (
	nonce_sha256 CHAR(64) NOT NULL PRIMARY KEY,
	claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
	expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT chk_journey_v3_nonce_sha256 CHECK (REGEXP_LIKE(nonce_sha256, '^[0-9a-f]{64}$')),
	CONSTRAINT chk_journey_v3_nonce_expiry CHECK (expires_at > claimed_at)
);

CREATE TABLE journey_v3_sessions (
	token_sha256 CHAR(64) NOT NULL PRIMARY KEY,
	scope VARCHAR(40) NOT NULL,
	issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
	expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT chk_journey_v3_session_token_sha256 CHECK (REGEXP_LIKE(token_sha256, '^[0-9a-f]{64}$')),
	CONSTRAINT chk_journey_v3_session_scope CHECK (scope = 'journey:v3'),
	CONSTRAINT chk_journey_v3_session_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX idx_journey_v3_nonce_expires_at ON journey_v3_nonce_claims (expires_at);
CREATE INDEX idx_journey_v3_session_expires_at ON journey_v3_sessions (expires_at);
