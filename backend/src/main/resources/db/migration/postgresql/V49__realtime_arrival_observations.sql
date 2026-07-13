CREATE TABLE realtime_arrival_observations (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider_id VARCHAR(80) NOT NULL,
  station_id VARCHAR(120) NOT NULL,
  line_id VARCHAR(80) NOT NULL,
  provider_line_id VARCHAR(80) NOT NULL,
  provider_station_id VARCHAR(80) NOT NULL,
  train_no VARCHAR(80) NOT NULL,
  provider_observed_at TIMESTAMPTZ NOT NULL,
  backend_received_at TIMESTAMPTZ NOT NULL,
  raw_eta_seconds INTEGER,
  adjusted_eta_seconds INTEGER,
  raw_direction VARCHAR(120),
  raw_destination VARCHAR(120),
  retained_until TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_realtime_arrival_retention
    CHECK (retained_until > backend_received_at),
  CONSTRAINT chk_realtime_arrival_raw_eta
    CHECK (raw_eta_seconds IS NULL OR raw_eta_seconds >= 0),
  CONSTRAINT chk_realtime_arrival_adjusted_eta
    CHECK (adjusted_eta_seconds IS NULL OR adjusted_eta_seconds >= 0)
);

CREATE INDEX idx_realtime_arrival_provider_train_time
  ON realtime_arrival_observations (provider_id, train_no, provider_observed_at);

CREATE INDEX idx_realtime_arrival_retained_until
  ON realtime_arrival_observations (retained_until);

CREATE TABLE realtime_provider_call_quota_state (
  provider_id VARCHAR(80) PRIMARY KEY,
  minute_window BIGINT NOT NULL,
  minute_calls INTEGER NOT NULL,
  day_window BIGINT NOT NULL,
  daily_calls INTEGER NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_realtime_provider_quota_counts
    CHECK (minute_calls >= 0 AND daily_calls >= 0)
);

INSERT INTO realtime_provider_call_quota_state (
  provider_id, minute_window, minute_calls, day_window, daily_calls, updated_at
) VALUES ('seoul-topis', -1, 0, -1, 0, CURRENT_TIMESTAMP);
