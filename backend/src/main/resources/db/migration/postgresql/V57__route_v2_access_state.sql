CREATE TABLE route_v2_sessions (
	token_sha256 CHAR(64) NOT NULL PRIMARY KEY,
	scope VARCHAR(40) NOT NULL,
	issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
	expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
	request_count INTEGER NOT NULL DEFAULT 0,
	CONSTRAINT chk_route_v2_sessions_token_sha256 CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
	CONSTRAINT chk_route_v2_sessions_scope CHECK (scope = 'route:v2:itx'),
	CONSTRAINT chk_route_v2_sessions_expiry CHECK (expires_at > issued_at),
	CONSTRAINT chk_route_v2_sessions_request_count CHECK (request_count BETWEEN 0 AND 50)
);

CREATE TABLE route_v2_nonce_replays (
	nonce_sha256 CHAR(64) NOT NULL PRIMARY KEY,
	expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT chk_route_v2_nonce_sha256 CHECK (nonce_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE route_v2_states (
	route_state_id VARCHAR(120) NOT NULL PRIMARY KEY,
	origin_station_id VARCHAR(120) NOT NULL,
	destination_station_id VARCHAR(120) NOT NULL,
	transport_scope VARCHAR(40) NOT NULL,
	requested_departure_at TIMESTAMP WITH TIME ZONE NOT NULL,
	itinerary_json TEXT NOT NULL,
	timetable_artifact_id VARCHAR(160) NOT NULL,
	created_at TIMESTAMP WITH TIME ZONE NOT NULL,
	planned_arrival_at TIMESTAMP WITH TIME ZONE NOT NULL,
	expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT chk_route_v2_states_scope CHECK (transport_scope = 'SUBWAY_AND_ITX_CHEONGCHUN'),
	CONSTRAINT chk_route_v2_states_expiry CHECK (
		expires_at = LEAST(
			created_at + INTERVAL '6 hours',
			GREATEST(created_at + INTERVAL '30 minutes', planned_arrival_at + INTERVAL '30 minutes')
		)
	)
);

CREATE INDEX idx_route_v2_sessions_expires_at ON route_v2_sessions (expires_at);
CREATE INDEX idx_route_v2_nonce_replays_expires_at ON route_v2_nonce_replays (expires_at);
CREATE INDEX idx_route_v2_states_expires_at ON route_v2_states (expires_at);
