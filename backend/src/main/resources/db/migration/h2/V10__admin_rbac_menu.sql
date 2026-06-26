CREATE TABLE admin_role_permissions (
    created_at TIMESTAMP NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    role_code VARCHAR(60) NOT NULL,
    PRIMARY KEY (role_code, permission_code)
);

ALTER TABLE admin_role_permissions ADD CONSTRAINT ck_h2_admin_role_permissions_role
    CHECK (role_code IN ('ADMIN_VIEWER', 'REPORT_REVIEWER', 'MASTER_EDITOR', 'FIELD_OPERATOR', 'DATA_OPERATOR', 'SECURITY_ADMIN', 'SUPER_ADMIN'));

ALTER TABLE admin_role_permissions ADD CONSTRAINT ck_h2_admin_role_permissions_permission
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin'));

CREATE TABLE admin_user_roles (
    created_at TIMESTAMP NOT NULL,
    role_code VARCHAR(60) NOT NULL,
    login_id VARCHAR(120) NOT NULL,
    PRIMARY KEY (login_id, role_code)
);

ALTER TABLE admin_user_roles ADD CONSTRAINT ck_h2_admin_user_roles_role
    CHECK (role_code IN ('ADMIN_VIEWER', 'REPORT_REVIEWER', 'MASTER_EDITOR', 'FIELD_OPERATOR', 'DATA_OPERATOR', 'SECURITY_ADMIN', 'SUPER_ADMIN'));

ALTER TABLE admin_user_roles ADD CONSTRAINT ck_h2_admin_user_roles_login_id_canonical
    CHECK (login_id = LOWER(TRIM(login_id)));

CREATE TABLE admin_menu_items (
    hidden BOOLEAN DEFAULT FALSE NOT NULL,
    display_order INTEGER DEFAULT 0 NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    parent_program_code VARCHAR(80),
    program_code VARCHAR(80) PRIMARY KEY NOT NULL
);

ALTER TABLE admin_menu_items ADD CONSTRAINT ck_h2_admin_menu_items_order
    CHECK (display_order >= 0);

ALTER TABLE admin_menu_items ADD CONSTRAINT fk_h2_admin_menu_items_parent
    FOREIGN KEY (parent_program_code) REFERENCES admin_menu_items(program_code);

INSERT INTO admin_role_permissions (created_at, permission_code, role_code)
VALUES
    (CURRENT_TIMESTAMP, 'admin.view', 'ADMIN_VIEWER'),
    (CURRENT_TIMESTAMP, 'admin.view', 'REPORT_REVIEWER'),
    (CURRENT_TIMESTAMP, 'admin.report.review', 'REPORT_REVIEWER'),
    (CURRENT_TIMESTAMP, 'admin.view', 'MASTER_EDITOR'),
    (CURRENT_TIMESTAMP, 'admin.master.edit', 'MASTER_EDITOR'),
    (CURRENT_TIMESTAMP, 'admin.view', 'FIELD_OPERATOR'),
    (CURRENT_TIMESTAMP, 'admin.field.operate', 'FIELD_OPERATOR'),
    (CURRENT_TIMESTAMP, 'admin.view', 'DATA_OPERATOR'),
    (CURRENT_TIMESTAMP, 'admin.data.operate', 'DATA_OPERATOR'),
    (CURRENT_TIMESTAMP, 'admin.view', 'SECURITY_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.security.audit', 'SECURITY_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.security.admin', 'SECURITY_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.view', 'SUPER_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.report.review', 'SUPER_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.master.edit', 'SUPER_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.field.operate', 'SUPER_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.data.operate', 'SUPER_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.security.audit', 'SUPER_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.security.admin', 'SUPER_ADMIN');

INSERT INTO admin_menu_items (hidden, display_order, display_name, parent_program_code, program_code)
VALUES
    (FALSE, 10, '통합 대시보드', NULL, 'a-dashboard'),
    (FALSE, 20, '역 목록', NULL, 'a-stations'),
    (FALSE, 30, '시설 상태판', NULL, 'a-facilities'),
    (FALSE, 40, '역 구조·동선 편집', NULL, 'a-layout-editor'),
    (FALSE, 50, '제보 검수 큐', NULL, 'a-reports'),
    (FALSE, 60, '데이터 품질', NULL, 'a-quality'),
    (FALSE, 70, '현장 검증', NULL, 'a-field-verifications'),
    (FALSE, 80, '데이터 수집', NULL, 'a-collections'),
    (FALSE, 90, '경로 검색 분석', NULL, 'a-route-searches'),
    (FALSE, 100, '경로 피드백 분석', NULL, 'a-route-feedback'),
    (FALSE, 110, '푸시 알림', NULL, 'a-push'),
    (FALSE, 120, '사용 현황', NULL, 'a-usage'),
    (FALSE, 130, '시스템 상태', NULL, 'a-system');
