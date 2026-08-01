-- V64형: DO 블록 안 RAISE EXCEPTION 메시지 문자열에 'DROP TABLE' 같은 단어가 들어 있어도
-- 문자열 리터럴은 전처리에서 제거되므로 오탐을 내지 않아야 한다. 본문의 실제 DDL은 additive다.
CREATE TABLE data_collection_locks (
    source VARCHAR(40) PRIMARY KEY
);

DO $migration$
DECLARE
    duplicate_sources TEXT;
BEGIN
    SELECT STRING_AGG(source, ', ')
    INTO duplicate_sources
    FROM data_collection_locks;

    IF duplicate_sources IS NOT NULL THEN
        RAISE EXCEPTION 'refuse to DROP TABLE or TRUNCATE while duplicates exist: %', duplicate_sources
            USING HINT = 'This message must not be parsed as DDL.';
    END IF;
END
$migration$;
