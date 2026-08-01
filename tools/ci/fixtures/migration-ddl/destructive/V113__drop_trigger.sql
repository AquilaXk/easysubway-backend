-- 기존 마이그레이션이 만든 트리거를 제거하면 구 canonical의 쓰기 보증이 사라진다.
DROP TRIGGER IF EXISTS trg_data_source_snapshot_lineage ON datapack_source_snapshots;
