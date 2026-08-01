-- CREATE TABLE IF NOT EXISTS는 운영에 테이블이 이미 있으면 no-op이므로 신규 테이블이 아니다.
-- 뒤따르는 제약·UNIQUE INDEX 추가는 구 canonical의 쓰기를 거부할 수 있다.
CREATE TABLE IF NOT EXISTS service_notice_reads (
    id BIGSERIAL PRIMARY KEY,
    notice_id BIGINT NOT NULL,
    admin_id BIGINT NOT NULL
);

ALTER TABLE service_notice_reads
    ADD CONSTRAINT service_notice_reads_unique UNIQUE (notice_id, admin_id);
