-- repeatable 스크립트는 버전이 없어 체크섬이 바뀔 때마다 재실행되므로 항상 스캔해야 한다.
DROP VIEW active_station_membership;

CREATE VIEW active_station_membership AS
SELECT station_id, line_id
FROM canonical_station_lines;
