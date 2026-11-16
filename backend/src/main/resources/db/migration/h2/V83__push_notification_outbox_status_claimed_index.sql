CREATE INDEX idx_push_notification_outbox_status_claimed ON push_notification_outbox (status, processing_claimed_at, created_at ASC);
