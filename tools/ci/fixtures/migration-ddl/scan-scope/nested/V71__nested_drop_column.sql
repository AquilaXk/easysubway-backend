-- Flyway는 location을 재귀 탐색하므로 중첩 디렉토리 스크립트도 스캔해야 한다.
ALTER TABLE facility_reports DROP COLUMN legacy_note;
