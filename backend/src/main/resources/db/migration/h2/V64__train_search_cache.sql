CREATE TABLE train_catalog_cache (
    catalog_kind VARCHAR(32) PRIMARY KEY,
    payload_json CLOB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_train_catalog_cache_hash CHECK (CHAR_LENGTH(payload_sha256) = 64),
    CONSTRAINT chk_train_catalog_cache_expiry CHECK (expires_at > observed_at)
);

CREATE TABLE train_search_cache (
    cache_key VARCHAR(200) PRIMARY KEY,
    normalized_query_json CLOB,
    payload_json CLOB,
    payload_sha256 CHAR(64),
    observed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    last_access_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(100),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_train_search_cache_payload CHECK (
        (normalized_query_json IS NULL AND payload_json IS NULL AND payload_sha256 IS NULL
            AND observed_at IS NULL AND expires_at IS NULL)
        OR
        (normalized_query_json IS NOT NULL AND payload_json IS NOT NULL AND payload_sha256 IS NOT NULL
            AND observed_at IS NOT NULL AND expires_at IS NOT NULL
            AND CHAR_LENGTH(payload_sha256) = 64 AND expires_at > observed_at)
    ),
    CONSTRAINT chk_train_search_cache_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX idx_train_search_cache_expiry ON train_search_cache (expires_at);
CREATE INDEX idx_train_search_cache_last_access ON train_search_cache (last_access_at);

CREATE TABLE train_provider_call_quota_state (
    provider_id VARCHAR(100) PRIMARY KEY,
    minute_window BIGINT NOT NULL,
    minute_calls INTEGER NOT NULL,
    day_window BIGINT NOT NULL,
    daily_calls INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_train_provider_call_quota_counts CHECK (minute_calls >= 0 AND daily_calls >= 0)
);
