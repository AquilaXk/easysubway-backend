-- backend#500 / data#1084: admin "자동화 상태" 화면이 보여 줄 마지막 snapshot 1건.
-- data 레포 workflow가 게시한 검증된 JSON을 그대로 보관하고, 더 새로운 generated_at만 이전 값을 대체한다.
CREATE TABLE datapack_automation_status (
    id           SMALLINT     NOT NULL PRIMARY KEY,
    payload_json TEXT         NOT NULL,
    generated_at TIMESTAMPTZ  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_datapack_automation_status_singleton CHECK (id = 1),
    CONSTRAINT chk_datapack_automation_status_size CHECK (CHAR_LENGTH(payload_json) <= 65536)
);
