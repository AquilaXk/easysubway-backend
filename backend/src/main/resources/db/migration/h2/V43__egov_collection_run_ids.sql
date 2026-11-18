-- 수집 run ID control-plane(#1821): eGovFrame fdl-idgnr Table 전략용 ID 테이블.
-- 운영성 수집 run ID(collection-<id>)만 이 테이블에서 발번한다. 보안 식별자(UUID·DB PK·토큰)는 대상 아님.
-- 병합순 확정 규약(#1821): 작성 시 V42로 예약했으나 병합 시점에 V42__admin_audit_events_actor_index가 선점 → 다음 가용 V43으로 rename(h2·postgresql 동시).
CREATE TABLE ids (
    table_name VARCHAR(20) NOT NULL PRIMARY KEY,
    next_id    NUMERIC(30) NOT NULL
);

INSERT INTO ids (table_name, next_id) VALUES ('COLLECTION_RUN_ID', 1);
