CREATE TABLE admin_users (
    login_id VARCHAR(120) PRIMARY KEY NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    auth_method VARCHAR(40) NOT NULL,
    role VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    email VARCHAR(255),
    failed_login_count INTEGER DEFAULT 0 NOT NULL,
    credential_rotation_required BOOLEAN DEFAULT FALSE NOT NULL,
    bootstrap_managed BOOLEAN DEFAULT FALSE NOT NULL,
    password_changed_at TIMESTAMP NOT NULL,
    password_expires_at TIMESTAMP,
    locked_until TIMESTAMP,
    break_glass_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

ALTER TABLE admin_users ADD CONSTRAINT ck_h2_admin_users_auth_method
    CHECK (auth_method IN ('LOCAL', 'BREAK_GLASS'));

ALTER TABLE admin_users ADD CONSTRAINT ck_h2_admin_users_role
    CHECK (role IN ('ADMIN', 'OPERATOR_ADMIN'));

ALTER TABLE admin_users ADD CONSTRAINT ck_h2_admin_users_status
    CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED', 'PASSWORD_EXPIRED', 'CREDENTIAL_ROTATION_REQUIRED'));

ALTER TABLE admin_users ADD CONSTRAINT ck_h2_admin_users_failed_login_count
    CHECK (failed_login_count >= 0);

CREATE TABLE admin_login_audits (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL,
    login_id VARCHAR(120) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    auth_method VARCHAR(40) NOT NULL,
    reason VARCHAR(500)
);

ALTER TABLE admin_login_audits ADD CONSTRAINT ck_h2_admin_login_audits_auth_method
    CHECK (auth_method IN ('LOCAL', 'BREAK_GLASS'));

ALTER TABLE admin_login_audits ADD CONSTRAINT ck_h2_admin_login_audits_outcome
    CHECK (outcome IN ('FAILED', 'LOCKED', 'DISABLED', 'PASSWORD_EXPIRED', 'CREDENTIAL_ROTATION_REQUIRED', 'SUCCESS'));

CREATE INDEX idx_admin_login_audits_login_occurred_at
    ON admin_login_audits (login_id, occurred_at);
