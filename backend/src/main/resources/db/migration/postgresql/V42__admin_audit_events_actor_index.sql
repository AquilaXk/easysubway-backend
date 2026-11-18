-- 감사 로그 actor 필터·타임라인 조회 성능 인덱스 (#1747 CodeRabbit).
-- search/count/findForExport는 actor로 필터하고 (occurred_at, audit_id)로 정렬하며,
-- findActorContext는 같은 actor의 (occurred_at, audit_id) 튜플로 전후 이력을 찾는다.
-- 기존 idx_occurred_at / idx_type_occurred_at 는 actor 필터를 커버하지 못한다.
CREATE INDEX idx_admin_audit_events_actor_occurred_at
    ON admin_audit_events (actor, occurred_at, audit_id);
