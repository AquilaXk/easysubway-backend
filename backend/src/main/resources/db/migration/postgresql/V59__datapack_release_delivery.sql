CREATE TABLE datapack_release_deliveries (
    idempotency_key       VARCHAR(400)    NOT NULL PRIMARY KEY,
    release_request_id    VARCHAR(255)    NOT NULL,
    release_sequence      BIGINT          NOT NULL,
    manifest_sha256       datapack_sha256 NOT NULL,
    channel               VARCHAR(32)     NOT NULL,
    candidate_id          VARCHAR(255)    NOT NULL,
    payload_sha256        datapack_sha256,
    signature_sha256      datapack_sha256 NOT NULL,
    state                 VARCHAR(32)     NOT NULL,
    attempts              INTEGER         NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMP,
    reconcile_deadline    TIMESTAMP       NOT NULL,
    dead_letter_deadline  TIMESTAMP       NOT NULL,
    http_class            VARCHAR(32),
    sanitized_detail      VARCHAR(255),
    claimed_at            TIMESTAMP,
    claim_owner           VARCHAR(120),
    created_at            TIMESTAMP       NOT NULL,
    updated_at            TIMESTAMP       NOT NULL,
    CONSTRAINT uq_datapack_release_delivery_sequence UNIQUE (release_request_id, release_sequence),
    CONSTRAINT chk_datapack_release_delivery_state CHECK (state IN ('PENDING', 'DELIVERED', 'RETRY_SCHEDULED', 'RECONCILIATION_REQUIRED', 'DEAD_LETTER')),
    CONSTRAINT chk_datapack_release_delivery_channel CHECK (channel IN ('dev', 'staging', 'production')),
    CONSTRAINT chk_datapack_release_delivery_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_datapack_release_delivery_sequence CHECK (release_sequence > 0),
    CONSTRAINT chk_datapack_release_delivery_deadlines CHECK (reconcile_deadline <= dead_letter_deadline)
);

CREATE INDEX idx_datapack_release_delivery_due
    ON datapack_release_deliveries (state, next_attempt_at, claimed_at);
CREATE INDEX idx_datapack_release_delivery_channel_state
    ON datapack_release_deliveries (channel, state);

ALTER TABLE datapack_release_request
    ADD COLUMN reconciliation_next_attempt_at TIMESTAMP;
