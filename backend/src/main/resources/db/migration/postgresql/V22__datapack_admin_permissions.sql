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

CREATE TEMPORARY TABLE datapack_admin_permission_role_seed (
    role_code VARCHAR(60) PRIMARY KEY,
    evidence_override_role BOOLEAN NOT NULL,
    alias_review_role BOOLEAN NOT NULL,
    data_operation_role BOOLEAN NOT NULL,
    audit_role BOOLEAN NOT NULL,
    super_role BOOLEAN NOT NULL
);

INSERT INTO datapack_admin_permission_role_seed (
    role_code,
    evidence_override_role,
    alias_review_role,
    data_operation_role,
    audit_role,
    super_role
)
VALUES
    ('ADMIN_VIEWER', FALSE, FALSE, FALSE, FALSE, FALSE),
    ('MASTER_EDITOR', TRUE, TRUE, FALSE, FALSE, FALSE),
    ('FIELD_OPERATOR', TRUE, FALSE, FALSE, FALSE, FALSE),
    ('DATA_OPERATOR', FALSE, FALSE, TRUE, FALSE, FALSE),
    ('SECURITY_ADMIN', FALSE, FALSE, FALSE, TRUE, FALSE),
    ('SUPER_ADMIN', FALSE, FALSE, FALSE, FALSE, TRUE);

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
SELECT role_code, 'admin.datapack.read', CURRENT_TIMESTAMP
FROM datapack_admin_permission_role_seed
ON CONFLICT (role_code, permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
SELECT role_seed.role_code, permission_seed.permission_code, CURRENT_TIMESTAMP
FROM datapack_admin_permission_role_seed role_seed
CROSS JOIN (
    VALUES
        ('admin.datapack.evidence.review'),
        ('admin.datapack.override.request')
) AS permission_seed(permission_code)
WHERE role_seed.evidence_override_role
ON CONFLICT (role_code, permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
SELECT role_seed.role_code, permission_seed.permission_code, CURRENT_TIMESTAMP
FROM datapack_admin_permission_role_seed role_seed
CROSS JOIN (
    VALUES
        ('admin.datapack.alias.review'),
        ('admin.datapack.quarantine.review')
) AS permission_seed(permission_code)
WHERE role_seed.alias_review_role
ON CONFLICT (role_code, permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
SELECT role_seed.role_code, permission_seed.permission_code, CURRENT_TIMESTAMP
FROM datapack_admin_permission_role_seed role_seed
CROSS JOIN (
    VALUES
        ('admin.datapack.source.run'),
        ('admin.datapack.candidate.build'),
        ('admin.datapack.staging.promote')
) AS permission_seed(permission_code)
WHERE role_seed.data_operation_role
ON CONFLICT (role_code, permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
SELECT role_code, 'admin.datapack.audit.read', CURRENT_TIMESTAMP
FROM datapack_admin_permission_role_seed
WHERE audit_role
ON CONFLICT (role_code, permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
SELECT DISTINCT role_seed.role_code, permission.permission_code, CURRENT_TIMESTAMP
FROM datapack_admin_permission_role_seed role_seed
CROSS JOIN admin_role_permissions permission
WHERE role_seed.super_role
  AND permission.permission_code LIKE 'admin.datapack.%'
ON CONFLICT (role_code, permission_code) DO NOTHING;

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
SELECT role_seed.role_code, permission_seed.permission_code, CURRENT_TIMESTAMP
FROM datapack_admin_permission_role_seed role_seed
CROSS JOIN (
    VALUES
        ('admin.datapack.override.approve'),
        ('admin.datapack.production.approve'),
        ('admin.datapack.rollback')
) AS permission_seed(permission_code)
WHERE role_seed.super_role
ON CONFLICT (role_code, permission_code) DO NOTHING;

DROP TABLE datapack_admin_permission_role_seed;
