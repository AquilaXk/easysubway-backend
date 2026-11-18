CREATE TABLE IF NOT EXISTS transit_feed_info (
  id INT NOT NULL PRIMARY KEY,
  feed_end_date VARCHAR(8) NOT NULL,
  CONSTRAINT h2_transit_feed_info_singleton CHECK (id = 1)
);
