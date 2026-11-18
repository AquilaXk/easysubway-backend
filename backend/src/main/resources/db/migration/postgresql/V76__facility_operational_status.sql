-- #419 QA 결정 추가 3: 시설 가동 상태는 읽기 전용 마스터 데이터와 분리된 운영 상태 테이블에 둔다.
-- facility_id는 data#835 smrt-elev 시설 id다. 미검증 시민 제보는 이 테이블에 쓰지 않는다.
CREATE TABLE facility_operational_status (
    facility_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    source TEXT NOT NULL,
    source_code TEXT,
    observed_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_facility_operational_status_status CHECK (status IN ('OPERATING', 'OUT_OF_SERVICE')),
    CONSTRAINT ck_facility_operational_status_source CHECK (source IN ('SEOUL_METRO_FEED', 'ADMIN_VERIFIED'))
);

-- 원천별 마지막 성공 수집 시각(상태 반영 커밋과 같은 트랜잭션에서만 옮긴다).
CREATE TABLE facility_status_feed_heartbeat (
    feed TEXT PRIMARY KEY,
    last_success_at TIMESTAMPTZ NOT NULL
);
