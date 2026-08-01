-- Flyway 명명 규칙에 맞지 않는 .sql은 실행 조건을 판정할 수 없어 fail closed 대상이다.
ALTER TABLE service_notices ALTER COLUMN published_at SET NOT NULL;
