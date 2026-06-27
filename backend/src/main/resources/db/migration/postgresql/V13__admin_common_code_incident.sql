ALTER TABLE admin_role_permissions DROP CONSTRAINT admin_role_permissions_permission_code_check;

ALTER TABLE admin_role_permissions ADD CONSTRAINT admin_role_permissions_permission_code_check
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read', 'admin.batch.retry', 'admin.operations.manage'));

ALTER TABLE admin_audit_events DROP CONSTRAINT admin_audit_events_event_type_check;

ALTER TABLE admin_audit_events ADD CONSTRAINT admin_audit_events_event_type_check
    CHECK (event_type IN ('LOGIN', 'LOGIN_FAILURE', 'LOGOUT', 'ADMIN_ACTION', 'PRIVACY_READ', 'SYSTEM_CHANGE', 'BATCH_OPERATION', 'COMMON_CODE_CHANGE', 'INCIDENT_CHANGE', 'MASTER_DATA_CHANGE'));

CREATE TABLE admin_common_code_groups (
    group_code CHARACTER VARYING(80) NOT NULL PRIMARY KEY,
    display_name CHARACTER VARYING(120) NOT NULL,
    description CHARACTER VARYING(500),
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE admin_common_codes (
    group_code CHARACTER VARYING(80) NOT NULL,
    code CHARACTER VARYING(80) NOT NULL,
    display_name CHARACTER VARYING(120) NOT NULL,
    description CHARACTER VARYING(500),
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (group_code, code),
    FOREIGN KEY (group_code) REFERENCES admin_common_code_groups(group_code)
);

CREATE TABLE admin_incidents (
    incident_id CHARACTER VARYING(40) NOT NULL PRIMARY KEY,
    severity CHARACTER VARYING(40) NOT NULL,
    status CHARACTER VARYING(40) NOT NULL,
    source CHARACTER VARYING(40) NOT NULL,
    summary CHARACTER VARYING(300) NOT NULL,
    owner CHARACTER VARYING(120) NOT NULL,
    opened_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,
    resolution CHARACTER VARYING(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CHECK ( -- NOSONAR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolved_at IS NULL AND resolution IS NULL)
    )
);

CREATE INDEX idx_admin_common_codes_group_enabled
    ON admin_common_codes (group_code, enabled, sort_order);

CREATE INDEX idx_admin_incidents_status_opened
    ON admin_incidents (status, opened_at);

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
SELECT role_code, 'admin.operations.manage', CURRENT_TIMESTAMP
FROM (
    SELECT 'DATA_OPERATOR' AS role_code
    UNION ALL SELECT 'SECURITY_ADMIN'
    UNION ALL SELECT 'SUPER_ADMIN'
) role_seed;

INSERT INTO admin_menu_items (program_code, parent_program_code, display_name, display_order, hidden)
VALUES
    ('a-codes', NULL, '공통코드', 87, FALSE),
    ('a-incidents', NULL, '장애관리', 88, FALSE);

INSERT INTO admin_common_code_groups (group_code, display_name, description, sort_order, enabled, created_at, updated_at)
VALUES
    ('REPORT_REJECTION_REASON', '신고 반려 사유', '제보 검수에서 반복 선택하는 반려 사유', 10, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('FACILITY_STATUS_REASON', '시설 변경 사유', '시설 상태 변경 사유', 20, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('BATCH_FAILURE_CATEGORY', '배치 실패 분류', '수집 배치 실패 원인 분류', 30, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('INCIDENT_SEVERITY', '장애 심각도', '운영 incident 심각도', 40, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('INCIDENT_STATUS', '장애 상태', '운영 incident 처리 상태', 50, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('INCIDENT_SOURCE', '장애 출처', 'incident 발생 출처', 60, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO admin_common_codes (group_code, code, display_name, description, sort_order, enabled, created_at, updated_at)
SELECT code_groups.group_code,
       code_seed.code,
       code_seed.display_name,
       code_seed.description,
       code_seed.sort_order,
       TRUE,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (
    SELECT 10 AS group_sort_order, 'DUPLICATE' AS code, '중복 제보' AS display_name, '이미 처리 중인 동일 제보' AS description, 10 AS sort_order
    UNION ALL SELECT 10, 'INSUFFICIENT', '정보 부족', '역·시설·사진 정보 부족', 20
    UNION ALL SELECT 20, 'INSPECTION', '정기 점검', '운영기관 정기 점검', 10
    UNION ALL SELECT 20, 'REPORT_CONFIRMED', '제보 확인', '제보 검수 후 상태 변경', 20
    UNION ALL SELECT 30, 'SOURCE_TIMEOUT', '원천 응답 지연', '원천 데이터 응답 시간 초과', 10
    UNION ALL SELECT 30, 'VALIDATION_ERROR', '검증 실패', '수집 산출물 검증 실패', 20
    UNION ALL SELECT 40, 'MAJOR', 'Major', '사용자 기능 영향', 10
    UNION ALL SELECT 40, 'MINOR', 'Minor', '운영 확인 필요', 20
    UNION ALL SELECT 50, 'OPEN', 'Open', '처리 전', 10
    UNION ALL SELECT 50, 'RESOLVED', 'Resolved', '해결됨', 20
    UNION ALL SELECT 60, 'HEALTH', 'Health', 'health 상태', 10
    UNION ALL SELECT 60, 'BATCH', 'Batch', '배치 실행', 20
) code_seed
JOIN admin_common_code_groups code_groups
    ON code_groups.sort_order = code_seed.group_sort_order;
