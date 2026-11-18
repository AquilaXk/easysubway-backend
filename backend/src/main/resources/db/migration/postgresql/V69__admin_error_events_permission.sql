-- #2433 WP3: 오류 이벤트 조회 권한·메뉴 seed.
-- admin_role_permissions CHECK를 확장하는 contract 단계(DDL gate allowlist).
ALTER TABLE admin_role_permissions DROP CONSTRAINT admin_role_permissions_permission_code_check;

ALTER TABLE admin_role_permissions ADD CONSTRAINT admin_role_permissions_permission_code_check
    CHECK (permission_code IN (
        'admin.view',
        'admin.report.review',
        'admin.report.photo.read',
        'admin.master.edit',
        'admin.field.operate',
        'admin.data.operate',
        'admin.security.audit',
        'admin.security.admin',
        'admin.audit.read',
        'admin.privacy-log.read',
        'admin.batch.run',
        'admin.batch.retry',
        'admin.operations.manage',
        'admin.datapack.read',
        'admin.datapack.source.run',
        'admin.datapack.alias.review',
        'admin.datapack.quarantine.review',
        'admin.datapack.evidence.review',
        'admin.datapack.override.request',
        'admin.datapack.override.approve',
        'admin.datapack.candidate.build',
        'admin.datapack.staging.promote',
        'admin.datapack.production.approve',
        'admin.datapack.rollback',
        'admin.datapack.audit.read',
        'admin.errors.read'
    ));

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
VALUES
    ('SECURITY_ADMIN', 'admin.errors.read', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.errors.read', CURRENT_TIMESTAMP)
ON CONFLICT (role_code, permission_code) DO NOTHING;

INSERT INTO admin_menu_items (program_code, parent_program_code, display_name, display_order, hidden)
VALUES
    ('a-errors', NULL, '오류 이벤트', 145, FALSE);
