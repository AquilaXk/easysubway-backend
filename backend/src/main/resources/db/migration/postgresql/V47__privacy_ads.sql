-- #1771 무추적 하우스/제휴 배너: 슬롯, 활성 소재, 일별 무식별 집계.
CREATE TABLE ad_placements (
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE ad_creatives (
    id              VARCHAR(64)   NOT NULL PRIMARY KEY,
    placement_id    VARCHAR(64)   NOT NULL,
    image_url       VARCHAR(1000) NOT NULL,
    landing_url     VARCHAR(1000) NOT NULL,
    advertiser_name VARCHAR(255)  NOT NULL,
    alt_text        VARCHAR(500)  NOT NULL,
    starts_at       TIMESTAMP     NOT NULL,
    ends_at         TIMESTAMP,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_ad_creative_placement FOREIGN KEY (placement_id) REFERENCES ad_placements(id),
    CONSTRAINT uq_ad_creative_placement UNIQUE (id, placement_id)
);
CREATE INDEX idx_ad_creatives_active ON ad_creatives (placement_id, enabled, starts_at, ends_at);

CREATE TABLE ad_event_daily (
    event_date  DATE        NOT NULL,
    placement_id VARCHAR(64) NOT NULL,
    creative_id  VARCHAR(64) NOT NULL,
    event_type   VARCHAR(16) NOT NULL,
    event_count  INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (event_date, placement_id, creative_id, event_type),
    CONSTRAINT fk_ad_event_placement FOREIGN KEY (placement_id) REFERENCES ad_placements(id),
    CONSTRAINT fk_ad_event_creative FOREIGN KEY (creative_id, placement_id) REFERENCES ad_creatives(id, placement_id),
    CONSTRAINT chk_ad_event_type CHECK (event_type IN ('IMPRESSION', 'CLICK')),
    CONSTRAINT chk_ad_event_count CHECK (event_count >= 0)
);
