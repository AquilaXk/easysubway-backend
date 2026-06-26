ALTER TABLE admin_role_permissions DROP CONSTRAINT ck_h2_admin_role_permissions_permission;

ALTER TABLE admin_role_permissions ADD CONSTRAINT ck_h2_admin_role_permissions_permission
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read', 'admin.batch.retry'));

INSERT INTO admin_role_permissions (created_at, permission_code, role_code)
VALUES
    (CURRENT_TIMESTAMP, 'admin.batch.retry', 'DATA_OPERATOR'),
    (CURRENT_TIMESTAMP, 'admin.batch.retry', 'SUPER_ADMIN');

INSERT INTO admin_menu_items (hidden, display_order, display_name, parent_program_code, program_code)
VALUES
    (FALSE, 85, '배치 운영', NULL, 'a-batches');
