-- backend#500 / data#1084: admin "자동화 상태" 화면이 보여 줄 마지막 snapshot 1건 (h2 테스트 방언).
CREATE TABLE datapack_automation_status (
    id SMALLINT NOT NULL PRIMARY KEY,
    payload_json TEXT NOT NULL,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_datapack_automation_status_singleton CHECK (id = 1),
    CONSTRAINT chk_datapack_automation_status_size CHECK (CHAR_LENGTH(payload_json) <= 65536)
);
