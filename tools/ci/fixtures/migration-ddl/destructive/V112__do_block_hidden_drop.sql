-- 파괴적 DDL이 DO 블록 본문 안에 숨어 있는 케이스. 본문을 재귀적으로 스캔해야 탐지된다.
DO $migration$
BEGIN
    RAISE NOTICE 'cleaning up legacy artifacts';
    DROP TABLE legacy_route_search_results;
END
$migration$;
