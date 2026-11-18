ALTER TABLE push_notification_outbox
	ADD COLUMN IF NOT EXISTS processing_claimed_at TIMESTAMP;

ALTER TABLE push_notification_outbox
	DROP CONSTRAINT IF EXISTS chk_push_notification_outbox_status;

ALTER TABLE push_notification_outbox
	ADD CONSTRAINT chk_push_notification_outbox_status
		CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED'));
