ALTER TABLE admin_role_permissions DROP CONSTRAINT ck_h2_admin_role_permissions_permission;

ALTER TABLE admin_role_permissions ADD CONSTRAINT ck_h2_admin_role_permissions_permission
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.report.photo.read', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read', 'admin.batch.retry', 'admin.operations.manage'));

MERGE INTO admin_role_permissions (role_code, permission_code, created_at)
KEY (role_code, permission_code)
VALUES
    ('REPORT_REVIEWER', 'admin.report.photo.read', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.report.photo.read', CURRENT_TIMESTAMP);
