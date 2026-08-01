-- CASCADE는 이 함수를 참조하는 트리거까지 함께 제거한다.
DROP FUNCTION guard_data_source_snapshot_lineage() CASCADE;
