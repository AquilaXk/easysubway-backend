-- #1947 관리자 광고 소재 lifecycle에서 사용하는 고정 placement.
INSERT INTO ad_placements (id, display_name, enabled)
VALUES ('route-result-bottom', '경로 결과 하단', TRUE),
       ('station-detail-bottom', '역 상세 하단', TRUE)
ON CONFLICT (id) DO NOTHING;
