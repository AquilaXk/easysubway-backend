-- #2275 운행 공지 게시 중단 lifecycle — soft-unpublish(h2 test dialect, compact form).
-- unpublished_at/unpublished_by로 상태를 남기고 row를 보존한다. 활성 조회는 unpublished 제외.
ALTER TABLE service_notice ADD COLUMN unpublished_at TIMESTAMP;
ALTER TABLE service_notice ADD COLUMN unpublished_by VARCHAR(255);
