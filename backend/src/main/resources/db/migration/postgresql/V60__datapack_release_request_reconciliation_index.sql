CREATE INDEX CONCURRENTLY idx_datapack_release_request_reconciliation_due
    ON datapack_release_request (status, reconciliation_next_attempt_at, updated_at);
