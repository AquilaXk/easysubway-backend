-- #2401 admin_user_roles 부여 출처(provenance) 컬럼을 additive로 추가한다(h2 test dialect).
-- granted_by='bootstrap'은 env 지정 관리자 부팅 seed가 만든 role 할당임을 표시하며,
-- 수동 부여 행(granted_by NULL)은 revokeStaleBootstrapRoles의 회수 대상에서 제외된다.
ALTER TABLE admin_user_roles ADD COLUMN granted_by VARCHAR(40);
