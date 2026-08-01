-- COLUMN 키워드를 생략한 형태도 DEFAULT 없는 NOT NULL 컬럼 추가로 탐지되어야 한다.
ALTER TABLE admin_identities
    ADD required_value TEXT NOT NULL;
