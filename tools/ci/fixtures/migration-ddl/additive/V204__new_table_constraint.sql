-- 같은 파일에서 새로 만든 테이블에 대한 제약·UNIQUE INDEX는 additive이므로 허용한다.
CREATE TABLE timetable_snapshot_reviews (
    id BIGSERIAL PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL
);

ALTER TABLE timetable_snapshot_reviews
    ADD CONSTRAINT timetable_snapshot_reviews_unique UNIQUE (snapshot_id, reviewer_id);

CREATE UNIQUE INDEX ux_timetable_snapshot_reviews_snapshot
    ON timetable_snapshot_reviews (snapshot_id);
