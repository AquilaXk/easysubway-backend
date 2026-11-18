-- #2433 WP3: 5xx SYSTEM/DEPENDENCY 오류 집계 테이블 (h2 테스트 방언).
-- 요청 본문·질의문자·요청머리글·사용자 식별자·예외 문구 원문은 저장하지 않는다.
CREATE TABLE error_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    first_occurred_at TIMESTAMP NOT NULL,
    last_occurred_at TIMESTAMP NOT NULL,
    code VARCHAR(64) NOT NULL,
    category VARCHAR(16) NOT NULL,
    http_status INTEGER NOT NULL,
    method VARCHAR(8) NOT NULL,
    path_pattern VARCHAR(255) NOT NULL,
    exception_class VARCHAR(255) NOT NULL,
    stack_hash CHAR(64) NOT NULL,
    sample_correlation_id VARCHAR(64) NOT NULL,
    occurrence_count BIGINT NOT NULL
);

ALTER TABLE error_events ADD CONSTRAINT ck_h2_error_events_category
    CHECK (category IN ('SYSTEM', 'DEPENDENCY'));

ALTER TABLE error_events ADD CONSTRAINT ck_h2_error_events_occurrence_count
    CHECK (occurrence_count >= 1);

ALTER TABLE error_events ADD CONSTRAINT ux_h2_error_events_stack_code_path
    UNIQUE (stack_hash, code, path_pattern);

CREATE INDEX idx_error_events_last_occurred_at
    ON error_events (last_occurred_at DESC);

CREATE INDEX idx_error_events_code
    ON error_events (code);
