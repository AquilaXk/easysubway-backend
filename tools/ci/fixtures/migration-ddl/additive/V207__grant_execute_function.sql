-- 권한 부여 문법의 EXECUTE는 동적 SQL 실행이 아니므로 통과해야 한다.
GRANT EXECUTE ON FUNCTION guard_data_source_snapshot_lineage() TO easysubway_app;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO easysubway_app;
