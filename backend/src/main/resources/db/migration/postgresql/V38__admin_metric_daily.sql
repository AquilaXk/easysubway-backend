-- 통합 대시보드 재설계(#1739) 일별 지표 스냅샷.
-- 조회 시점 즉석 계산을 넘어서 추이·전일 대비를 볼 수 있도록 하루 1회 집계 값을 적재한다.
-- 같은 (지표 키, 날짜) 재실행은 upsert로 멱등하게 갱신한다(집계 배치가 보장).
-- dimensions는 세부 분해(예: 지역별)를 담는 JSON 텍스트이며 스칼라 지표는 NULL이다.
CREATE TABLE admin_metric_daily (
    metric_key VARCHAR(80) NOT NULL,
    metric_date DATE NOT NULL,
    metric_value DOUBLE PRECISION NOT NULL,
    dimensions VARCHAR(2000),
    PRIMARY KEY (metric_key, metric_date)
);

CREATE INDEX idx_admin_metric_daily_date ON admin_metric_daily (metric_date);
