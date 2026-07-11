-- #1947 관리자 광고 소재 lifecycle에서 사용하는 고정 placement(h2 test dialect).
INSERT INTO ad_placements (id, display_name, enabled)
SELECT 'route-result-bottom', '경로 결과 하단', TRUE
WHERE NOT EXISTS (SELECT 1 FROM ad_placements WHERE id = 'route-result-bottom');

INSERT INTO ad_placements (id, display_name, enabled)
SELECT 'station-detail-bottom', '역 상세 하단', TRUE
WHERE NOT EXISTS (SELECT 1 FROM ad_placements WHERE id = 'station-detail-bottom');
