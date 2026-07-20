-- #2401 admin_user_roles 부여 출처(provenance) 컬럼을 additive로 추가한다.
-- granted_by='bootstrap'은 env 지정 관리자 부팅 seed가 만든 role 할당임을 표시한다.
-- env에서 계정이 제거되면 revokeStaleBootstrapRoles가 이 표시 행만 회수하고,
-- 수동 부여 행(granted_by NULL)과 기존 행은 회수 대상에서 제외된다.
-- 순수 additive(nullable, DEFAULT/제약 없음)라 blue/green standby 부팅 윈도우에서 안전하다.
ALTER TABLE admin_user_roles ADD COLUMN granted_by VARCHAR(40);
