-- #419 QA 결정 추가 3: 시설 가동 상태 운영 테이블 (h2 테스트 방언).
CREATE TABLE facility_operational_status (
    facility_id VARCHAR(1024) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    source_code VARCHAR(32),
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE facility_operational_status ADD CONSTRAINT ck_h2_facility_operational_status_status
    CHECK (status IN ('OPERATING', 'OUT_OF_SERVICE'));

ALTER TABLE facility_operational_status ADD CONSTRAINT ck_h2_facility_operational_status_source
    CHECK (source IN ('SEOUL_METRO_FEED', 'ADMIN_VERIFIED'));

CREATE TABLE facility_status_feed_heartbeat (
    feed VARCHAR(64) PRIMARY KEY,
    last_success_at TIMESTAMP WITH TIME ZONE NOT NULL
);
