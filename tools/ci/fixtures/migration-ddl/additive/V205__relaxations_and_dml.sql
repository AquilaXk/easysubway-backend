-- 완화(DROP CONSTRAINT/INDEX)와 DML은 구코드에 안전하므로 허용한다.
ALTER TABLE admin_role_permissions DROP CONSTRAINT admin_role_permissions_permission_code_check;

DROP INDEX IF EXISTS ux_data_collection_runs_running_source;

UPDATE data_collection_runs SET active_source = source WHERE status = 'RUNNING';

INSERT INTO admin_role_permissions (role_code, permission_code, created_at)
VALUES ('DATA_OPERATOR', 'admin.batch.run', CURRENT_TIMESTAMP)
ON CONFLICT (role_code, permission_code) DO NOTHING;
