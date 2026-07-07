-- #1694 Part C: best-effort 자동 promote 결과 기록(콜백 수신 시).
ALTER TABLE datapack_release_request ADD COLUMN promote_outcome VARCHAR(32);
ALTER TABLE datapack_release_request ADD COLUMN promote_detail VARCHAR(1024);
