CREATE TABLE admin_role_permissions (
    role_code VARCHAR(60) NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (role_code, permission_code),
    CHECK (role_code IN ('ADMIN_VIEWER', 'REPORT_REVIEWER', 'MASTER_EDITOR', 'FIELD_OPERATOR', 'DATA_OPERATOR', 'SECURITY_ADMIN', 'SUPER_ADMIN')),
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin'))
);

CREATE TABLE admin_user_roles (
    login_id VARCHAR(120) NOT NULL,
    role_code VARCHAR(60) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (login_id, role_code),
    CHECK (role_code IN ('ADMIN_VIEWER', 'REPORT_REVIEWER', 'MASTER_EDITOR', 'FIELD_OPERATOR', 'DATA_OPERATOR', 'SECURITY_ADMIN', 'SUPER_ADMIN')),
    CHECK (login_id = LOWER(TRIM(login_id)))
);

CREATE TABLE admin_menu_items (
    program_code VARCHAR(80) NOT NULL PRIMARY KEY,
    parent_program_code VARCHAR(80),
    display_name VARCHAR(120) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (parent_program_code) REFERENCES admin_menu_items(program_code)
);

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
VALUES
    ('ADMIN_VIEWER', 'admin.view', CURRENT_TIMESTAMP),
    ('REPORT_REVIEWER', 'admin.view', CURRENT_TIMESTAMP),
    ('REPORT_REVIEWER', 'admin.report.review', CURRENT_TIMESTAMP),
    ('MASTER_EDITOR', 'admin.view', CURRENT_TIMESTAMP),
    ('MASTER_EDITOR', 'admin.master.edit', CURRENT_TIMESTAMP),
    ('FIELD_OPERATOR', 'admin.view', CURRENT_TIMESTAMP),
    ('FIELD_OPERATOR', 'admin.field.operate', CURRENT_TIMESTAMP),
    ('DATA_OPERATOR', 'admin.view', CURRENT_TIMESTAMP),
    ('DATA_OPERATOR', 'admin.data.operate', CURRENT_TIMESTAMP),
    ('SECURITY_ADMIN', 'admin.view', CURRENT_TIMESTAMP),
    ('SECURITY_ADMIN', 'admin.security.audit', CURRENT_TIMESTAMP),
    ('SECURITY_ADMIN', 'admin.security.admin', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.view', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.report.review', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.master.edit', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.field.operate', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.data.operate', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.security.audit', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.security.admin', CURRENT_TIMESTAMP);

INSERT INTO admin_menu_items (program_code, parent_program_code, display_name, display_order, hidden)
VALUES
    ('a-dashboard', NULL, '통합 대시보드', 10, FALSE),
    ('a-stations', NULL, '역 목록', 20, FALSE),
    ('a-facilities', NULL, '시설 상태판', 30, FALSE),
    ('a-layout-editor', NULL, '역 구조·동선 편집', 40, FALSE),
    ('a-reports', NULL, '제보 검수 큐', 50, FALSE),
    ('a-quality', NULL, '데이터 품질', 60, FALSE),
    ('a-field-verifications', NULL, '현장 검증', 70, FALSE),
    ('a-collections', NULL, '데이터 수집', 80, FALSE),
    ('a-route-searches', NULL, '경로 검색 분석', 90, FALSE),
    ('a-route-feedback', NULL, '경로 피드백 분석', 100, FALSE),
    ('a-push', NULL, '푸시 알림', 110, FALSE),
    ('a-usage', NULL, '사용 현황', 120, FALSE),
    ('a-system', NULL, '시스템 상태', 130, FALSE);
