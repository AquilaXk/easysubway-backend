UPDATE route_feedbacks
	SET user_id = 'deleted-user'
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

UPDATE facility_reports
	SET user_id = '__easysubway_deleted_facility_report__'
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM user_activity_events
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM push_notification_outbox
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM registered_devices
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM notification_settings
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM mobility_profiles
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM favorite_route_stations
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM favorite_routes
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM favorite_facilities
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DELETE FROM favorite_stations
	WHERE user_id IN (SELECT user_id FROM guest_accounts);

DROP TABLE IF EXISTS anonymous_auth_audit_events;
DROP TABLE IF EXISTS anonymous_auth_tokens;
DROP TABLE IF EXISTS guest_accounts;
