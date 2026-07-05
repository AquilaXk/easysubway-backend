-- 장애를 역·노선에 선택적으로 연결한다. 역 허브(#1741)·알림 센터(#1738)에서 참조한다.
-- 기존 데이터는 NULL 허용(연결 없음).
ALTER TABLE admin_incidents ADD COLUMN station_id VARCHAR(40);
ALTER TABLE admin_incidents ADD COLUMN line_id VARCHAR(40);
