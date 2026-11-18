-- #474: Journey v3 검색 운영 분석 기록. 출발·도착역·세션·요청 ID 같은 개인정보가 될 수 있는 값과 정확한 검색 시각은 담지 않고 날짜(recorded_on)만 둔다.
-- 알 수 없는 값은 UNKNOWN, 해당하지 않는 값은 NOT_APPLICABLE로 명시해 성공 분류로 대체하지 않는다.
CREATE TABLE journey_v3_search_records (
    record_id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    recorded_on           DATE         NOT NULL,
    search_kind           VARCHAR(32)  NOT NULL,
    outcome               VARCHAR(32)  NOT NULL,
    http_status           INTEGER      NOT NULL,
    machine_code          VARCHAR(80),
    engine_version        VARCHAR(200) NOT NULL,
    mobility_profile      VARCHAR(40)  NOT NULL,
    alternative_categories VARCHAR(200) NOT NULL,
    stair_free_status     VARCHAR(32)  NOT NULL,
    CONSTRAINT chk_journey_v3_search_records_kind CHECK (search_kind IN ('DEPART_AT', 'DEPART_BETWEEN', 'ARRIVE_BY', 'LAST_CONNECTION')),
    CONSTRAINT chk_journey_v3_search_records_outcome CHECK (outcome IN ('FOUND', 'NO_ROUTE', 'TOO_COMPLEX', 'TIMEOUT', 'UNAVAILABLE', 'REJECTED', 'UNCLASSIFIED'))
);
CREATE INDEX idx_journey_v3_search_records_recorded_on ON journey_v3_search_records (recorded_on);
