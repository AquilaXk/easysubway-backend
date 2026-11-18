-- 관리자 표준 테이블(#1737) 계정별 저장된 뷰. postgresql 마이그레이션과 동일 DDL(H2 PostgreSQL 모드).
CREATE TABLE admin_saved_views (
    view_id VARCHAR(120) NOT NULL PRIMARY KEY,
    admin_login_id VARCHAR(120) NOT NULL,
    program_id VARCHAR(120) NOT NULL,
    name VARCHAR(120) NOT NULL,
    query_params VARCHAR(2000) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ux_admin_saved_views_owner_program_name UNIQUE (admin_login_id, program_id, name)
);

CREATE INDEX idx_admin_saved_views_owner_program
    ON admin_saved_views (admin_login_id, program_id);
