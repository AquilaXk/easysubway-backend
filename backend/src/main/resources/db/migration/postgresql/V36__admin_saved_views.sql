-- 관리자 표준 테이블(#1737) 계정별 저장된 뷰.
-- 소유자는 admin_users.login_id(주체 이름)로 키잉한다. 화면(program_id)별로 이름 있는 질의
-- 파라미터 스냅샷을 저장하고, 화면당 기본 뷰를 한 개 지정할 수 있다(기본 유일성은 서비스가 보장).
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

-- 화면당 기본 뷰는 최대 하나. 동시 make-default 경합까지 DB로 강제한다.
-- (H2는 partial index를 지원하지 않아 h2 마이그레이션에는 없으며, 그쪽은 서비스 계층 clearDefault가 보장한다.)
CREATE UNIQUE INDEX ux_admin_saved_views_single_default
    ON admin_saved_views (admin_login_id, program_id)
    WHERE is_default;
