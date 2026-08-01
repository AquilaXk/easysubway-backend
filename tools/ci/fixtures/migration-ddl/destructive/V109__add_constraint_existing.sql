ALTER TABLE admin_role_permissions
    ADD CONSTRAINT admin_role_permissions_unique UNIQUE (role_code, permission_code);
