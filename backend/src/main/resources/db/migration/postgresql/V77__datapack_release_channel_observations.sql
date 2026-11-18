-- #456: git 원본 release request 발행의 배포 채널 관측(관측 전용).
-- 승격·롤백 상태(datapack_release_channels)는 backend candidate 행을 전제하므로 섞지 않는다.
-- 서명된 release-request binding과 catalog/current.json 결속을 검증한 delivery만 이 행을 쓴다.
CREATE TABLE datapack_release_channel_observations (
    channel                  VARCHAR(32)     NOT NULL PRIMARY KEY,
    release_sequence         BIGINT          NOT NULL,
    manifest_sha256          datapack_sha256 NOT NULL,
    release_request_id       VARCHAR(255)    NOT NULL,
    binding_signature_sha256 datapack_sha256 NOT NULL,
    delivery_idempotency_key VARCHAR(400)    NOT NULL,
    workflow_run_url         VARCHAR(1000),
    observed_at              TIMESTAMP       NOT NULL,
    CONSTRAINT chk_datapack_release_channel_observations_channel CHECK (channel IN ('dev', 'staging', 'production')),
    CONSTRAINT chk_datapack_release_channel_observations_sequence CHECK (release_sequence > 0)
);
