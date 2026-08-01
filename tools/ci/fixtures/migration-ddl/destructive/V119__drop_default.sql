-- default 제거는 그 컬럼을 생략하는 구 인스턴스의 insert를 겹침 구간에서 실패시킨다.
ALTER TABLE service_notices ALTER COLUMN pinned DROP DEFAULT;
