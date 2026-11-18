CREATE TABLE IF NOT EXISTS transit_feed_info (
  id INTEGER NOT NULL PRIMARY KEY,
  feed_end_date VARCHAR(8) NOT NULL,
  CONSTRAINT chk_transit_feed_info_singleton CHECK (id = 1)
);
