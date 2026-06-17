-- Spring Batch 5 PostgreSQL metadata schema.
-- 운영 재기동 때 같은 DDL이 반복되어도 실패하지 않도록 IF NOT EXISTS를 사용한다.

CREATE TABLE IF NOT EXISTS BATCH_JOB_INSTANCE (
	JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
		REFERENCES BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
);

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION_PARAMS (
	JOB_EXECUTION_ID BIGINT NOT NULL,
	PARAMETER_NAME VARCHAR(100) NOT NULL,
	PARAMETER_TYPE VARCHAR(100) NOT NULL,
	PARAMETER_VALUE VARCHAR(2500),
	IDENTIFYING CHAR(1) NOT NULL,
	CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
		REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE IF NOT EXISTS BATCH_STEP_EXECUTION (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	COMMIT_COUNT BIGINT,
	READ_COUNT BIGINT,
	FILTER_COUNT BIGINT,
	WRITE_COUNT BIGINT,
	READ_SKIP_COUNT BIGINT,
	WRITE_SKIP_COUNT BIGINT,
	PROCESS_SKIP_COUNT BIGINT,
	ROLLBACK_COUNT BIGINT,
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
		REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE IF NOT EXISTS BATCH_STEP_EXECUTION_CONTEXT (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
		REFERENCES BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
);

CREATE TABLE IF NOT EXISTS BATCH_JOB_EXECUTION_CONTEXT (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
		REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE SEQUENCE IF NOT EXISTS BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

CREATE TABLE IF NOT EXISTS data_collection_runs (
	run_id VARCHAR(80) NOT NULL PRIMARY KEY,
	source VARCHAR(40) NOT NULL,
	status VARCHAR(20) NOT NULL,
	requested_by VARCHAR(120) NOT NULL,
	started_at TIMESTAMP NOT NULL,
	completed_at TIMESTAMP,
	collected_count INTEGER NOT NULL,
	failure_message VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_data_collection_runs_started_at
	ON data_collection_runs (started_at DESC);

CREATE TABLE IF NOT EXISTS mobility_profiles (
	user_id VARCHAR(120) NOT NULL PRIMARY KEY,
	mobility_type VARCHAR(40) NOT NULL,
	avoid_stairs BOOLEAN NOT NULL,
	require_elevator BOOLEAN NOT NULL,
	allow_escalator BOOLEAN NOT NULL,
	minimize_transfers BOOLEAN NOT NULL,
	avoid_long_walks BOOLEAN NOT NULL,
	large_text BOOLEAN NOT NULL,
	high_contrast BOOLEAN NOT NULL,
	simple_view BOOLEAN NOT NULL,
	updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mobility_profiles_updated_at
	ON mobility_profiles (updated_at DESC);

CREATE TABLE IF NOT EXISTS favorite_stations (
	user_id VARCHAR(120) NOT NULL,
	station_id VARCHAR(120) NOT NULL,
	added_at TIMESTAMP NOT NULL,
	PRIMARY KEY (user_id, station_id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_stations_station_user
	ON favorite_stations (station_id, user_id);

CREATE INDEX IF NOT EXISTS idx_favorite_stations_user_added
	ON favorite_stations (user_id, added_at ASC, station_id ASC);

CREATE TABLE IF NOT EXISTS favorite_facilities (
	user_id VARCHAR(120) NOT NULL,
	facility_id VARCHAR(120) NOT NULL,
	added_at TIMESTAMP NOT NULL,
	PRIMARY KEY (user_id, facility_id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_facilities_facility_user
	ON favorite_facilities (facility_id, user_id);

CREATE INDEX IF NOT EXISTS idx_favorite_facilities_user_added
	ON favorite_facilities (user_id, added_at ASC, facility_id ASC);

CREATE TABLE IF NOT EXISTS favorite_routes (
	user_id VARCHAR(120) NOT NULL,
	route_search_id VARCHAR(120) NOT NULL,
	origin_station_id VARCHAR(120) NOT NULL,
	origin_station_name VARCHAR(120) NOT NULL,
	destination_station_id VARCHAR(120) NOT NULL,
	destination_station_name VARCHAR(120) NOT NULL,
	mobility_type VARCHAR(40) NOT NULL,
	status VARCHAR(40) NOT NULL,
	line_id VARCHAR(120) NOT NULL,
	line_name VARCHAR(120) NOT NULL,
	score INTEGER NOT NULL,
	steps_json TEXT NOT NULL,
	warnings_json TEXT NOT NULL,
	blocked_reasons_json TEXT NOT NULL,
	route_created_at TIMESTAMP NOT NULL,
	added_at TIMESTAMP NOT NULL,
	PRIMARY KEY (user_id, route_search_id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_routes_user_added
	ON favorite_routes (user_id, added_at ASC, route_search_id ASC);

CREATE TABLE IF NOT EXISTS favorite_route_stations (
	user_id VARCHAR(120) NOT NULL,
	route_search_id VARCHAR(120) NOT NULL,
	station_id VARCHAR(120) NOT NULL,
	PRIMARY KEY (user_id, route_search_id, station_id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_route_stations_station_user
	ON favorite_route_stations (station_id, user_id);

CREATE TABLE IF NOT EXISTS route_search_results (
	route_search_id VARCHAR(120) NOT NULL PRIMARY KEY,
	origin_station_id VARCHAR(120) NOT NULL,
	origin_station_name VARCHAR(120) NOT NULL,
	destination_station_id VARCHAR(120) NOT NULL,
	destination_station_name VARCHAR(120) NOT NULL,
	mobility_type VARCHAR(40) NOT NULL,
	status VARCHAR(40) NOT NULL,
	line_id VARCHAR(120) NOT NULL,
	line_name VARCHAR(120) NOT NULL,
	score INTEGER NOT NULL,
	steps_json TEXT NOT NULL,
	warnings_json TEXT NOT NULL,
	blocked_reasons_json TEXT NOT NULL,
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT chk_route_search_results_status
		CHECK (status IN ('FOUND', 'BLOCKED')),
	CONSTRAINT chk_route_search_results_mobility_type
		CHECK (mobility_type IN ('SENIOR', 'STROLLER', 'WHEELCHAIR', 'PREGNANT', 'TEMPORARY_INJURY', 'LUGGAGE'))
);

CREATE INDEX IF NOT EXISTS idx_route_search_results_created
	ON route_search_results (created_at DESC, route_search_id ASC);

CREATE TABLE IF NOT EXISTS route_feedbacks (
	feedback_id VARCHAR(120) NOT NULL PRIMARY KEY,
	route_search_id VARCHAR(120) NOT NULL,
	user_id VARCHAR(120) NOT NULL,
	rating VARCHAR(40) NOT NULL,
	comment VARCHAR(1000) NOT NULL,
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT chk_route_feedbacks_rating
		CHECK (rating IN ('HELPFUL', 'NOT_HELPFUL', 'BLOCKED_BY_REAL_WORLD'))
);

CREATE INDEX IF NOT EXISTS idx_route_feedbacks_user
	ON route_feedbacks (user_id);

CREATE INDEX IF NOT EXISTS idx_route_feedbacks_route_search
	ON route_feedbacks (route_search_id);

CREATE TABLE IF NOT EXISTS facility_reports (
	report_id VARCHAR(120) NOT NULL PRIMARY KEY,
	user_id VARCHAR(120) NOT NULL,
	station_id VARCHAR(120) NOT NULL,
	facility_id VARCHAR(120) NOT NULL,
	report_type VARCHAR(40) NOT NULL,
	description VARCHAR(1000),
	photo_file_name VARCHAR(255),
	photo_content_type VARCHAR(80),
	photo_data_base64 TEXT,
	latitude DECIMAL(10, 7),
	longitude DECIMAL(10, 7),
	duplicate_of_report_id VARCHAR(120),
	status VARCHAR(40) NOT NULL,
	created_at TIMESTAMP NOT NULL,
	reviewed_at TIMESTAMP,
	reviewed_by VARCHAR(120),
	CONSTRAINT fk_facility_reports_duplicate
		FOREIGN KEY (duplicate_of_report_id) REFERENCES facility_reports(report_id)
		ON DELETE SET NULL ON UPDATE CASCADE,
	CONSTRAINT chk_facility_reports_report_type
		CHECK (report_type IN ('BROKEN', 'UNDER_CONSTRUCTION', 'CLOSED', 'LOCATION_WRONG', 'INFORMATION_WRONG', 'RECOVERED')),
	CONSTRAINT chk_facility_reports_status
		CHECK (status IN ('SUBMITTED', 'DUPLICATE', 'UNDER_REVIEW', 'ACCEPTED', 'REJECTED', 'RESOLVED'))
);

CREATE INDEX IF NOT EXISTS idx_facility_reports_created
	ON facility_reports (created_at DESC, report_id ASC);

CREATE INDEX IF NOT EXISTS idx_facility_reports_user
	ON facility_reports (user_id);

CREATE INDEX IF NOT EXISTS idx_facility_reports_status_created
	ON facility_reports (status, created_at DESC, report_id ASC);

CREATE TABLE IF NOT EXISTS notification_settings (
	user_id VARCHAR(120) NOT NULL PRIMARY KEY,
	favorite_station_facility_alerts BOOLEAN NOT NULL,
	favorite_route_facility_alerts BOOLEAN NOT NULL,
	report_status_alerts BOOLEAN NOT NULL,
	data_quality_alerts BOOLEAN NOT NULL,
	updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notification_settings_updated
	ON notification_settings (updated_at DESC);

CREATE TABLE IF NOT EXISTS registered_devices (
	user_id VARCHAR(120) NOT NULL,
	platform VARCHAR(20) NOT NULL,
	device_token VARCHAR(255) NOT NULL,
	registered_at TIMESTAMP NOT NULL,
	PRIMARY KEY (user_id, platform, device_token),
	CONSTRAINT uq_registered_devices_platform_token UNIQUE (platform, device_token),
	CONSTRAINT chk_registered_devices_platform
		CHECK (platform IN ('ANDROID', 'IOS'))
);

CREATE INDEX IF NOT EXISTS idx_registered_devices_user_registered
	ON registered_devices (user_id, registered_at ASC, device_token ASC);

CREATE TABLE IF NOT EXISTS push_notification_outbox (
	notification_id VARCHAR(120) NOT NULL PRIMARY KEY,
	user_id VARCHAR(120) NOT NULL,
	platform VARCHAR(20) NOT NULL,
	device_token VARCHAR(255) NOT NULL,
	notification_type VARCHAR(60) NOT NULL,
	title VARCHAR(120) NOT NULL,
	body VARCHAR(1000) NOT NULL,
	status VARCHAR(40) NOT NULL,
	failure_reason VARCHAR(1000),
	created_at TIMESTAMP NOT NULL,
	CONSTRAINT chk_push_notification_outbox_platform
		CHECK (platform IN ('ANDROID', 'IOS')),
	CONSTRAINT chk_push_notification_outbox_type
		CHECK (notification_type IN ('FAVORITE_STATION_FACILITY', 'FAVORITE_ROUTE_FACILITY', 'REPORT_STATUS', 'DATA_QUALITY')),
	CONSTRAINT chk_push_notification_outbox_status
		CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
	CONSTRAINT chk_push_notification_outbox_failure_reason
		CHECK (failure_reason IS NULL OR status = 'FAILED')
);

ALTER TABLE push_notification_outbox
	DROP CONSTRAINT IF EXISTS chk_push_notification_outbox_status;

ALTER TABLE push_notification_outbox
	DROP CONSTRAINT IF EXISTS chk_push_notification_outbox_failure_reason;

ALTER TABLE push_notification_outbox
	ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(1000);

ALTER TABLE push_notification_outbox
	ADD CONSTRAINT chk_push_notification_outbox_status
		CHECK (status IN ('PENDING', 'SENT', 'FAILED'));

ALTER TABLE push_notification_outbox
	ADD CONSTRAINT chk_push_notification_outbox_failure_reason
		CHECK (failure_reason IS NULL OR status = 'FAILED');

CREATE INDEX IF NOT EXISTS idx_push_notification_outbox_user_created
	ON push_notification_outbox (user_id, created_at ASC, notification_id ASC);
