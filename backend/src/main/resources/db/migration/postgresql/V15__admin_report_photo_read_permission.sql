ALTER TABLE admin_role_permissions DROP CONSTRAINT admin_role_permissions_permission_code_check;

ALTER TABLE admin_role_permissions ADD CONSTRAINT admin_role_permissions_permission_code_check
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.report.photo.read', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read', 'admin.batch.retry', 'admin.operations.manage'));

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
VALUES
    ('REPORT_REVIEWER', 'admin.report.photo.read', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.report.photo.read', CURRENT_TIMESTAMP)
ON CONFLICT (role_code, permission_code) DO NOTHING;
