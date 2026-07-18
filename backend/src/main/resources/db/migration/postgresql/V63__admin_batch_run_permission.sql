ALTER TABLE data_collection_runs
    ADD COLUMN active_source VARCHAR(40);

UPDATE data_collection_runs
SET active_source = source
WHERE status = 'RUNNING';

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
        'admin.datapack.audit.read'
    ));

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
VALUES
    ('DATA_OPERATOR', 'admin.batch.run', CURRENT_TIMESTAMP),
    ('SUPER_ADMIN', 'admin.batch.run', CURRENT_TIMESTAMP)
ON CONFLICT (role_code, permission_code) DO NOTHING;
