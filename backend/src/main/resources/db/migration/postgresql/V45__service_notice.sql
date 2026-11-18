-- #1767 운행 공지(service_notice) — 온라인 overlay 도메인.
-- 실시간성 데이터라 데이터팩에 넣지 않는다(#1414 분리 원칙). 공개 API는 활성 공지만 방출한다.
CREATE TABLE service_notice (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    scope        VARCHAR(16)   NOT NULL,
    scope_value  VARCHAR(64),
    title        VARCHAR(255)  NOT NULL,
    body         VARCHAR(2000) NOT NULL,
    severity     VARCHAR(16)   NOT NULL,
    published_at TIMESTAMP     NOT NULL,
    expires_at   TIMESTAMP,
    published_by VARCHAR(255)  NOT NULL,
    CONSTRAINT chk_service_notice_scope CHECK (scope IN ('ALL', 'REGION', 'LINE')),
    CONSTRAINT chk_service_notice_severity CHECK (severity IN ('INFO', 'DISRUPTION'))
);
CREATE INDEX idx_service_notice_active ON service_notice (published_at, expires_at);
