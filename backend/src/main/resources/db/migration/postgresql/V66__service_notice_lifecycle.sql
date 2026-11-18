-- #2275 운행 공지 게시 중단 lifecycle — soft-unpublish.
-- 게시 중단을 row 삭제로 표현하면 이력 조회·audit 원자성·오류 결과가 어긋난다.
-- unpublished_at/unpublished_by로 상태를 남기고 row를 보존한다. 활성 조회는 unpublished를 제외한다.
ALTER TABLE service_notice ADD COLUMN unpublished_at TIMESTAMP;
ALTER TABLE service_notice ADD COLUMN unpublished_by VARCHAR(255);
