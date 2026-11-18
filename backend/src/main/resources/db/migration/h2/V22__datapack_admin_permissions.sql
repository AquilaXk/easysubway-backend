ALTER TABLE admin_role_permissions DROP CONSTRAINT ck_h2_admin_role_permissions_permission;

ALTER TABLE admin_role_permissions ADD CONSTRAINT ck_h2_admin_role_permissions_permission
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.report.photo.read', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read', 'admin.batch.retry', 'admin.operations.manage', 'admin.datapack.read', 'admin.datapack.source.run', 'admin.datapack.alias.review', 'admin.datapack.quarantine.review', 'admin.datapack.evidence.review', 'admin.datapack.override.request', 'admin.datapack.override.approve', 'admin.datapack.candidate.build', 'admin.datapack.staging.promote', 'admin.datapack.production.approve', 'admin.datapack.rollback', 'admin.datapack.audit.read'));

MERGE INTO admin_role_permissions (role_code, permission_code, created_at)
KEY (role_code, permission_code)
VALUES
    ('ADMIN_VIEWER', 'admin.datapack.read', CURRENT_TIMESTAMP),
    ('MASTER_EDITOR', 'admin.datapack.read', CURRENT_TIMESTAMP),
    ('MASTER_EDITOR', 'admin.datapack.alias.review', CURRENT_TIMESTAMP),
    ('MASTER_EDITOR', 'admin.datapack.quarantine.review', CURRENT_TIMESTAMP),
    ('MASTER_EDITOR', 'admin.datapack.evidence.review', CURRENT_TIMESTAMP),
    ('MASTER_EDITOR', 'admin.datapack.override.request', CURRENT_TIMESTAMP),
    ('FIELD_OPERATOR', 'admin.datapack.read', CURRENT_TIMESTAMP),
    ('FIELD_OPERATOR', 'admin.datapack.evidence.review', CURRENT_TIMESTAMP),
    ('FIELD_OPERATOR', 'admin.datapack.override.request', CURRENT_TIMESTAMP),
    ('DATA_OPERATOR', 'admin.datapack.read', CURRENT_TIMESTAMP),
    ('DATA_OPERATOR', 'admin.datapack.source.run', CURRENT_TIMESTAMP),
    ('DATA_OPERATOR', 'admin.datapack.candidate.build', CURRENT_TIMESTAMP),
    ('DATA_OPERATOR', 'admin.datapack.staging.promote', CURRENT_TIMESTAMP),
    ('SECURITY_ADMIN', 'admin.datapack.read', CURRENT_TIMESTAMP),
    ('SECURITY_ADMIN', 'admin.datapack.audit.read', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.read', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.source.run', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.alias.review', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.quarantine.review', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.evidence.review', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.override.request', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.override.approve', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.candidate.build', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.staging.promote', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.production.approve', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.rollback', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.datapack.audit.read', CURRENT_TIMESTAMP);
