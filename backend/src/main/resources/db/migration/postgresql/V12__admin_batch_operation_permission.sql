ALTER TABLE admin_role_permissions DROP CONSTRAINT admin_role_permissions_permission_code_check;

ALTER TABLE admin_role_permissions ADD CONSTRAINT admin_role_permissions_permission_code_check
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read', 'admin.batch.retry'));

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
VALUES
    ('DATA_OPERATOR', 'admin.batch.retry', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.batch.retry', CURRENT_TIMESTAMP);

INSERT INTO admin_menu_items (program_code, parent_program_code, display_name, display_order, hidden)
VALUES
    ('a-batches', NULL, '배치 운영', 85, FALSE);
