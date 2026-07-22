-- #2433 WP3: 오류 이벤트 조회 권한·메뉴 seed (h2 테스트 방언).
ALTER TABLE admin_role_permissions DROP CONSTRAINT ck_h2_admin_role_permissions_permission;

ALTER TABLE admin_role_permissions ADD CONSTRAINT ck_h2_admin_role_permissions_permission
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.report.photo.read', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read', 'admin.batch.run', 'admin.batch.retry', 'admin.operations.manage', 'admin.datapack.read', 'admin.datapack.source.run', 'admin.datapack.alias.review', 'admin.datapack.quarantine.review', 'admin.datapack.evidence.review', 'admin.datapack.override.request', 'admin.datapack.override.approve', 'admin.datapack.candidate.build', 'admin.datapack.staging.promote', 'admin.datapack.production.approve', 'admin.datapack.rollback', 'admin.datapack.audit.read', 'admin.errors.read'));

MERGE INTO admin_role_permissions (created_at, permission_code, role_code)
KEY (role_code, permission_code)
VALUES
    (CURRENT_TIMESTAMP, 'admin.errors.read', 'SECURITY_ADMIN'),
    (CURRENT_TIMESTAMP, 'admin.errors.read', 'SUPER_ADMIN');

INSERT INTO admin_menu_items (hidden, display_order, display_name, parent_program_code, program_code)
VALUES
    (FALSE, 145, '오류 이벤트', NULL, 'a-errors');
