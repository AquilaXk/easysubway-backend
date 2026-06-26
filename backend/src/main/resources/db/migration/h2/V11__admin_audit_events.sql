ALTER TABLE admin_role_permissions DROP CONSTRAINT ck_h2_admin_role_permissions_permission;

ALTER TABLE admin_role_permissions ADD CONSTRAINT ck_h2_admin_role_permissions_permission
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read'));

CREATE TABLE admin_audit_events (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type VARCHAR(40) NOT NULL,
    actor VARCHAR(120) NOT NULL,
    role_permission VARCHAR(1000),
    request_id VARCHAR(120),
    client_ip VARCHAR(120),
    user_agent VARCHAR(300),
    target_type VARCHAR(120) NOT NULL,
    target_id VARCHAR(160),
    action VARCHAR(160) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    reason VARCHAR(500),
    occurred_at TIMESTAMP NOT NULL
);

ALTER TABLE admin_audit_events ADD CONSTRAINT ck_h2_admin_audit_events_type
    CHECK (event_type IN ('LOGIN', 'LOGIN_FAILURE', 'LOGOUT', 'ADMIN_ACTION', 'PRIVACY_READ', 'SYSTEM_CHANGE', 'BATCH_OPERATION', 'MASTER_DATA_CHANGE'));

ALTER TABLE admin_audit_events ADD CONSTRAINT ck_h2_admin_audit_events_outcome
    CHECK (outcome IN ('SUCCESS', 'FAILURE'));

CREATE INDEX idx_admin_audit_events_occurred_at
    ON admin_audit_events (occurred_at);

CREATE INDEX idx_admin_audit_events_type_occurred_at
    ON admin_audit_events (event_type, occurred_at);

INSERT INTO admin_role_permissions (created_at, permission_code, role_code)
VALUES
    (CURRENT_TIMESTAMP, 'admin.audit.read', 'SECURITY_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.privacy-log.read', 'SECURITY_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.audit.read', 'SUPER_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.privacy-log.read', 'SUPER_ADMIN');

INSERT INTO admin_menu_items (hidden, display_order, display_name, parent_program_code, program_code)
VALUES
    (FALSE, 140, '관리자 감사', NULL, 'a-audits'),
    (FALSE, 150, '개인정보 조회 로그', NULL, 'a-privacy-audits');
