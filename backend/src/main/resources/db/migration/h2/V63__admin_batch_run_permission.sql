ALTER TABLE data_collection_runs
    ADD COLUMN active_source VARCHAR(40);

UPDATE data_collection_runs
SET active_source = source
WHERE status = 'RUNNING';

CREATE UNIQUE INDEX ux_data_collection_runs_active_source
    ON data_collection_runs (active_source);

ALTER TABLE admin_role_permissions DROP CONSTRAINT ck_h2_admin_role_permissions_permission;

ALTER TABLE admin_role_permissions ADD CONSTRAINT ck_h2_admin_role_permissions_permission
    CHECK (permission_code IN ('admin.view', 'admin.report.review', 'admin.report.photo.read', 'admin.master.edit', 'admin.field.operate', 'admin.data.operate', 'admin.security.audit', 'admin.security.admin', 'admin.audit.read', 'admin.privacy-log.read', 'admin.batch.run', 'admin.batch.retry', 'admin.operations.manage', 'admin.datapack.read', 'admin.datapack.source.run', 'admin.datapack.alias.review', 'admin.datapack.quarantine.review', 'admin.datapack.evidence.review', 'admin.datapack.override.request', 'admin.datapack.override.approve', 'admin.datapack.candidate.build', 'admin.datapack.staging.promote', 'admin.datapack.production.approve', 'admin.datapack.rollback', 'admin.datapack.audit.read'));

MERGE INTO admin_role_permissions (created_at, permission_code, role_code)
KEY (role_code, permission_code)
VALUES
    (CURRENT_TIMESTAMP, 'admin.batch.run', 'DATA_OPERATOR'),
    (CURRENT_TIMESTAMP, 'admin.batch.run', 'SUPER_ADMIN');
