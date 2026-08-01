ALTER TABLE admin_identities
    ADD COLUMN mfa_secret VARCHAR(64) NOT NULL;
